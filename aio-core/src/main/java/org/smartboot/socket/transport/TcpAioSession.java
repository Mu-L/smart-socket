/*******************************************************************************
 * Copyright (c) 2017-2019, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: TcpAioSession.java
 * Date: 2019-12-31
 * Author: sandao (zhengjunweimail@163.com)
 *
 ******************************************************************************/

package org.smartboot.socket.transport;


import org.smartboot.socket.DecoderException;
import org.smartboot.socket.MessageProcessor;
import org.smartboot.socket.NetMonitor;
import org.smartboot.socket.StateMachineEnum;
import org.smartboot.socket.buffer.BufferPage;
import org.smartboot.socket.buffer.VirtualBuffer;
import org.smartboot.socket.enhance.EnhanceAsynchronousChannelProvider;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * AIO传输层会话。
 *
 * <p>
 * AioSession为smart-socket最核心的类，封装{@link AsynchronousSocketChannel} API接口，简化IO操作。
 * </p>
 * <p>
 * 其中开放给用户使用的接口为：
 * <ol>
 * <li>{@link TcpAioSession#close()}</li>
 * <li>{@link TcpAioSession#close(boolean)}</li>
 * <li>{@link TcpAioSession#getAttachment()} </li>
 * <li>{@link TcpAioSession#getInputStream()} </li>
 * <li>{@link TcpAioSession#getInputStream(int)} </li>
 * <li>{@link TcpAioSession#getLocalAddress()} </li>
 * <li>{@link TcpAioSession#getRemoteAddress()} </li>
 * <li>{@link TcpAioSession#getSessionID()} </li>
 * <li>{@link TcpAioSession#isInvalid()} </li>
 * <li>{@link TcpAioSession#setAttachment(Object)}  </li>
 * </ol>
 *
 * </p>
 *
 * @author 三刀
 * @version V1.0.0
 */
final class TcpAioSession extends AioSession {
    /**
     * 读事件回调处理
     */
    private static final CompletionHandler<Integer, TcpAioSession> READ_COMPLETION_HANDLER = new CompletionHandler<Integer, TcpAioSession>() {
        @Override
        public void completed(Integer result, TcpAioSession aioSession) {
            try {
                aioSession.readCompleted(result);
            } catch (Throwable throwable) {
                failed(throwable, aioSession);
            }
        }

        @Override
        public void failed(Throwable exc, TcpAioSession aioSession) {
            try {
                aioSession.config.getProcessor().stateEvent(aioSession, StateMachineEnum.INPUT_EXCEPTION, exc);
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                aioSession.close(false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };
    /**
     * 写事件回调处理
     */
    private static final CompletionHandler<Integer, TcpAioSession> WRITE_COMPLETION_HANDLER = new CompletionHandler<Integer, TcpAioSession>() {
        @Override
        public void completed(Integer result, TcpAioSession aioSession) {
            try {
                aioSession.writeCompleted(result);
            } catch (Throwable throwable) {
                failed(throwable, aioSession);
            }
        }

        @Override
        public void failed(Throwable exc, TcpAioSession aioSession) {
            try {
                aioSession.config.getProcessor().stateEvent(aioSession, StateMachineEnum.OUTPUT_EXCEPTION, exc);
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                aioSession.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    /**
     * 底层通信channel对象
     */
    private final AsynchronousSocketChannel channel;
    /**
     * 输出流
     */
    private final WriteBuffer byteBuf;

    /**
     * 读缓冲。
     * <p>大小取决于AioQuickClient/AioQuickServer设置的setReadBufferSize</p>
     */
    private VirtualBuffer readBuffer;
    /**
     * 写缓冲
     */
    private VirtualBuffer writeBuffer;
    /**
     * 同步输入流
     */
    private InputStream inputStream;

    private final IoServerConfig config;

    /**
     * @param channel Socket通道
     */
    TcpAioSession(AsynchronousSocketChannel channel, IoServerConfig config, BufferPage writeBufferPage, Supplier<VirtualBuffer> readBufferSupplier) {
        this.channel = channel;
        this.config = config;
        this.readBufferSupplier = readBufferSupplier;
        byteBuf = new WriteBuffer(writeBufferPage, this::continueWrite, config.getWriteBufferSize(), config.getWriteBufferCapacity());
        //触发状态机
        config.getProcessor().stateEvent(this, StateMachineEnum.NEW_SESSION, null);
        doRead();
    }

    private final Supplier<VirtualBuffer> readBufferSupplier;

    void doRead() {
        this.readBuffer = readBufferSupplier.get();
        this.readBuffer.buffer().flip();
        signalRead();
    }

    /**
     * 触发AIO的写操作,
     * <p>需要调用控制同步</p>
     */
    void writeCompleted(int result) {
        NetMonitor monitor = config.getMonitor();
        if (monitor != null) {
            monitor.afterWrite(this, result);
        }
        VirtualBuffer writeBuffer = TcpAioSession.this.writeBuffer;
        TcpAioSession.this.writeBuffer = null;
        if (writeBuffer == null) {
            writeBuffer = byteBuf.poll();
        } else if (!writeBuffer.buffer().hasRemaining()) {
            writeBuffer.clean();
            writeBuffer = byteBuf.poll();
        }

        if (writeBuffer != null) {
            continueWrite(writeBuffer);
            return;
        }
        byteBuf.finishWrite();
        //此时可能是Closing或Closed状态
        if (status != SESSION_STATUS_ENABLED) {
            close();
        } else {
            //也许此时有新的消息通过write方法添加到writeCacheQueue中
            byteBuf.flush();
        }
    }

    /**
     * @return 输入流
     */
    public WriteBuffer writeBuffer() {
        return byteBuf;
    }

    @Override
    public ByteBuffer readBuffer() {
        return readBuffer.buffer();
    }

    @Override
    public void awaitRead() {
        modCount++;
    }

    /**
     * 是否立即关闭会话
     *
     * @param immediate true:立即关闭,false:响应消息发送完后关闭
     */
    public synchronized void close(boolean immediate) {
        //status == SESSION_STATUS_CLOSED说明close方法被重复调用
        if (status == SESSION_STATUS_CLOSED) {
//            System.out.println("ignore, session:" + getSessionID() + " is closed:");
            return;
        }
        status = immediate ? SESSION_STATUS_CLOSED : SESSION_STATUS_CLOSING;
        if (immediate) {
            try {
                byteBuf.close();
                if (readBuffer != null) {
                    readBuffer.clean();
                    readBuffer = null;
                }
                if (writeBuffer != null) {
                    writeBuffer.clean();
                    writeBuffer = null;
                }
            } finally {
                IOUtil.close(channel);
                config.getProcessor().stateEvent(this, StateMachineEnum.SESSION_CLOSED, null);
            }
        } else if ((writeBuffer == null || !writeBuffer.buffer().hasRemaining()) && byteBuf.isEmpty()) {
            close(true);
        } else {
            config.getProcessor().stateEvent(this, StateMachineEnum.SESSION_CLOSING, null);
            byteBuf.flush();
        }
    }


    void readCompleted(int result) {
        //释放缓冲区
        if (result == EnhanceAsynchronousChannelProvider.READ_MONITOR_SIGNAL) {
            this.readBuffer.clean();
            this.readBuffer = null;
            return;
        }
        if (result == EnhanceAsynchronousChannelProvider.READABLE_SIGNAL) {
            doRead();
            return;
        }
        // 接收到的消息进行预处理
        NetMonitor monitor = config.getMonitor();
        if (monitor != null) {
            monitor.afterRead(this, result);
        }
        this.eof = result == -1;
        if (SESSION_STATUS_CLOSED != status) {
            this.readBuffer.buffer().flip();
            signalRead();
        }
    }

    /**
     * 触发通道的读回调操作
     */
    public void signalRead() {
        int modCount = this.modCount;
        if (status == SESSION_STATUS_CLOSED) {
            return;
        }
        final ByteBuffer readBuffer = this.readBuffer.buffer();
        final MessageProcessor messageProcessor = config.getProcessor();
        while (readBuffer.hasRemaining() && status == SESSION_STATUS_ENABLED) {
            Object dataEntry;
            try {
                dataEntry = config.getProtocol().decode(readBuffer, this);
            } catch (Exception e) {
                messageProcessor.stateEvent(this, StateMachineEnum.DECODE_EXCEPTION, e);
                throw e;
            }
            if (dataEntry == null) {
                break;
            }

            //处理消息
            try {
                messageProcessor.process(this, dataEntry);
                if (modCount != this.modCount) {
                    return;
                }
            } catch (Exception e) {
                messageProcessor.stateEvent(this, StateMachineEnum.PROCESS_EXCEPTION, e);
            }
        }

        if (eof || status == SESSION_STATUS_CLOSING) {
            close(false);
            messageProcessor.stateEvent(this, StateMachineEnum.INPUT_SHUTDOWN, null);
            return;
        }
        if (status == SESSION_STATUS_CLOSED) {
            return;
        }

        byteBuf.flush();

        readBuffer.compact();
        //读缓冲区已满
        if (!readBuffer.hasRemaining()) {
            DecoderException exception = new DecoderException("readBuffer overflow. The current TCP connection will be closed. Please fix your " + config.getProtocol().getClass().getSimpleName() + "#decode bug.");
            messageProcessor.stateEvent(this, StateMachineEnum.DECODE_EXCEPTION, exception);
            throw exception;
        }

        //read from channel
        NetMonitor monitor = config.getMonitor();
        if (monitor != null) {
            monitor.beforeRead(this);
        }
        channel.read(readBuffer, 0L, TimeUnit.MILLISECONDS, this, READ_COMPLETION_HANDLER);
    }


    /**
     * 同步读取数据
     */
    private int synRead() throws IOException {
        ByteBuffer buffer = readBuffer.buffer();
        if (buffer.remaining() > 0) {
            return 0;
        }
        try {
            buffer.clear();
            int size = channel.read(buffer).get();
            buffer.flip();
            return size;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * 触发写操作
     *
     * @param writeBuffer 存放待输出数据的buffer
     */
    private void continueWrite(VirtualBuffer writeBuffer) {
        this.writeBuffer = writeBuffer;
        NetMonitor monitor = config.getMonitor();
        if (monitor != null) {
            monitor.beforeWrite(this);
        }
        channel.write(writeBuffer.buffer(), 0L, TimeUnit.MILLISECONDS, this, WRITE_COMPLETION_HANDLER);
    }

    /**
     * @return 本地地址
     * @throws IOException IO异常
     * @see AsynchronousSocketChannel#getLocalAddress()
     */
    public InetSocketAddress getLocalAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getLocalAddress();
    }

    /**
     * @return 远程地址
     * @throws IOException IO异常
     * @see AsynchronousSocketChannel#getRemoteAddress()
     */
    public InetSocketAddress getRemoteAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    /**
     * 断言当前会话是否可用
     *
     * @throws IOException IO异常
     */
    private void assertChannel() throws IOException {
        if (status == SESSION_STATUS_CLOSED || channel == null) {
            throw new IOException("session is closed");
        }
    }

    /**
     * 获得数据输入流对象。
     * <p>
     * faster模式下调用该方法会触发UnsupportedOperationException异常。
     * </p>
     * <p>
     * MessageProcessor采用异步处理消息的方式时，调用该方法可能会出现异常。
     * </p>
     *
     * @return 同步读操作的流对象
     * @throws IOException io异常
     */
    public InputStream getInputStream() throws IOException {
        return inputStream == null ? getInputStream(-1) : inputStream;
    }

    /**
     * 获取已知长度的InputStream
     *
     * @param length InputStream长度
     * @return 同步读操作的流对象
     * @throws IOException io异常
     */
    public InputStream getInputStream(int length) throws IOException {
        if (inputStream != null) {
            throw new IOException("pre inputStream has not closed");
        }
        synchronized (this) {
            if (inputStream == null) {
                inputStream = new InnerInputStream(length);
            }
        }
        return inputStream;
    }

    /**
     * 同步读操作的InputStream
     */
    private class InnerInputStream extends InputStream {
        /**
         * 当前InputSteam可读字节数
         */
        private int remainLength;

        InnerInputStream(int length) {
            this.remainLength = length >= 0 ? length : -1;
        }

        @Override
        public int read() throws IOException {
            if (remainLength == 0) {
                return -1;
            }
            ByteBuffer readBuffer = TcpAioSession.this.readBuffer.buffer();
            if (readBuffer.hasRemaining()) {
                remainLength--;
                return readBuffer.get();
            }
            if (synRead() == -1) {
                remainLength = 0;
            }
            return read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException();
            } else if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            } else if (len == 0) {
                return 0;
            }
            if (remainLength == 0) {
                return -1;
            }
            if (remainLength > 0 && remainLength < len) {
                len = remainLength;
            }
            ByteBuffer readBuffer = TcpAioSession.this.readBuffer.buffer();
            int size = 0;
            while (len > 0 && synRead() != -1) {
                int readSize = Math.min(readBuffer.remaining(), len);
                readBuffer.get(b, off + size, readSize);
                size += readSize;
                len -= readSize;
            }
            remainLength -= size;
            return size;
        }

        @Override
        public int available() throws IOException {
            if (remainLength == 0) {
                return 0;
            }
            if (synRead() == -1) {
                remainLength = 0;
                return remainLength;
            }
            ByteBuffer readBuffer = TcpAioSession.this.readBuffer.buffer();
            if (remainLength < -1) {
                return readBuffer.remaining();
            } else {
                return Math.min(remainLength, readBuffer.remaining());
            }
        }

        @Override
        public void close() {
            if (TcpAioSession.this.inputStream == InnerInputStream.this) {
                TcpAioSession.this.inputStream = null;
            }
        }
    }
}
