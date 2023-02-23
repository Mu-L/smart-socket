/*******************************************************************************
 * Copyright (c) 2017-2021, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: EnhanceAsynchronousSocketChannel.java
 * Date: 2021-07-29
 * Author: sandao (zhengjunweimail@163.com)
 *
 ******************************************************************************/

package org.smartboot.socket.enhance;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.ReadPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ShutdownChannelGroupException;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritePendingException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 模拟JDK7的AIO处理方式
 *
 * @author 三刀
 * @version V1.0 , 2018/5/24
 */
final class EnhanceAsynchronousSocketChannel extends AsynchronousSocketChannel {
    /**
     * 实际的Socket通道
     */
    private final SocketChannel channel;
    /**
     * 处理当前连接IO事件的资源组
     */
    private final EnhanceAsynchronousChannelGroup group;
    /**
     * 处理 read 事件的线程资源
     */
    private final EnhanceAsynchronousChannelGroup.Worker readWorker;
    /**
     * 处理 write 事件的线程资源
     */
    private final EnhanceAsynchronousChannelGroup.Worker commonWorker;
    /**
     * 用于接收 read 通道数据的缓冲区，经解码后腾出缓冲区以供下一批数据的读取
     */
    private ByteBuffer readBuffer;
    /**
     * 存放待输出数据的缓冲区
     */
    private ByteBuffer writeBuffer;

    /**
     * read 回调事件处理器
     */
    private CompletionHandler<Number, Object> readCompletionHandler;
    /**
     * write 回调事件处理器
     */
    private CompletionHandler<Number, Object> writeCompletionHandler;

    private FutureCompletionHandler<Void, Void> connectFuture;
    private FutureCompletionHandler<? extends Number, Object> readFuture;
    private FutureCompletionHandler<? extends Number, Object> writeFuture;
    /**
     * read 回调事件关联绑定的附件对象
     */
    private Object readAttachment;
    /**
     * write 回调事件关联绑定的附件对象
     */
    private Object writeAttachment;
    /**
     * 当前是否正在执行 write 操作
     */
    private boolean writePending;
    /**
     * 当前是否正在执行 read 操作
     */
    private boolean readPending;

    private int writeInvoker;

    private final boolean lowMemory;

    public EnhanceAsynchronousSocketChannel(EnhanceAsynchronousChannelGroup group, SocketChannel channel, boolean lowMemory) throws IOException {
        super(group.provider());
        this.group = group;
        this.channel = Objects.requireNonNull(channel);
        readWorker = group.getReadWorker();
        commonWorker = group.getCommonWorker();
        this.lowMemory = lowMemory;
    }

    @Override
    public void close() throws IOException {
        IOException exception = null;
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException e) {
            exception = e;
        }
        SelectionKey readSelectionKey = channel.keyFor(readWorker.selector);
        if (readSelectionKey != null) {
            readSelectionKey.cancel();
        }
        SelectionKey writeSelectionKey = channel.keyFor(commonWorker.selector);
        if (writeSelectionKey != null) {
            writeSelectionKey.cancel();
        }
        SelectionKey futureKey = group.futureKey(channel);
        if (futureKey != null) {
            futureKey.cancel();
        }
        if (exception != null) {
            throw exception;
        }
    }

    @Override
    public AsynchronousSocketChannel bind(SocketAddress local) throws IOException {
        channel.bind(local);
        return this;
    }

    @Override
    public <T> AsynchronousSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
        channel.setOption(name, value);
        return this;
    }

    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        return channel.getOption(name);
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return channel.supportedOptions();
    }

    @Override
    public AsynchronousSocketChannel shutdownInput() throws IOException {
        channel.shutdownInput();
        return this;
    }

    @Override
    public AsynchronousSocketChannel shutdownOutput() throws IOException {
        channel.shutdownOutput();
        return this;
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return channel.getRemoteAddress();
    }

    @Override
    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
        if (group.isTerminated()) {
            throw new ShutdownChannelGroupException();
        }
        if (channel.isConnected()) {
            throw new AlreadyConnectedException();
        }
        if (channel.isConnectionPending()) {
            throw new ConnectionPendingException();
        }
        doConnect(remote, attachment, (CompletionHandler<Void, Object>) handler);
    }

    @Override
    public Future<Void> connect(SocketAddress remote) {
        FutureCompletionHandler<Void, Void> connectFuture = new FutureCompletionHandler<>();
        connect(remote, null, connectFuture);
        this.connectFuture = connectFuture;
        return connectFuture;
    }

    @Override
    public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        read0(dst, timeout, unit, attachment, handler);
    }

    private <V extends Number, A> void read0(ByteBuffer readBuffer, long timeout, TimeUnit unit, A attachment, CompletionHandler<V, ? super A> handler) {
        if (readPending) {
            throw new ReadPendingException();
        }
        readPending = true;
        this.readBuffer = readBuffer;
        this.readAttachment = attachment;
        if (timeout > 0) {
            readFuture = new FutureCompletionHandler<>((CompletionHandler<Number, Object>) handler, readAttachment);
            readCompletionHandler = (CompletionHandler<Number, Object>) readFuture;
            group.getScheduledExecutor().schedule(readFuture, timeout, unit);
        } else {
            this.readCompletionHandler = (CompletionHandler<Number, Object>) handler;
        }
        doRead(readFuture != null);
    }

    @Override
    public Future<Integer> read(ByteBuffer readBuffer) {
        FutureCompletionHandler<Integer, Object> readFuture = new FutureCompletionHandler<>();
        this.readFuture = readFuture;
        read(readBuffer, 0, TimeUnit.MILLISECONDS, null, readFuture);
        return readFuture;
    }

    @Override
    public <A> void read(ByteBuffer[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        write0(src, timeout, unit, attachment, handler);
    }

    private <V extends Number, A> void write0(ByteBuffer writeBuffer, long timeout, TimeUnit unit, A attachment, CompletionHandler<V, ? super A> handler) {
        if (writePending) {
            throw new WritePendingException();
        }

        writePending = true;
        this.writeBuffer = writeBuffer;
        this.writeAttachment = attachment;
        if (timeout > 0) {
            writeFuture = new FutureCompletionHandler<>((CompletionHandler<Number, Object>) handler, writeAttachment);
            writeCompletionHandler = (CompletionHandler<Number, Object>) writeFuture;
            group.getScheduledExecutor().schedule(writeFuture, timeout, unit);
        } else {
            this.writeCompletionHandler = (CompletionHandler<Number, Object>) handler;
        }
        doWrite();
    }

    @Override
    public Future<Integer> write(ByteBuffer src) {
        FutureCompletionHandler<Integer, Object> writeFuture = new FutureCompletionHandler<>();
        this.writeFuture = writeFuture;
        write0(src, 0, TimeUnit.MILLISECONDS, null, writeFuture);
        return writeFuture;
    }

    @Override
    public <A> void write(ByteBuffer[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return channel.getLocalAddress();
    }

    public void doConnect(SocketAddress address, Object attachment, CompletionHandler<Void, Object> handler) {
        try {
            //此前通过Future调用,且触发了cancel
            if (connectFuture != null && connectFuture.isDone()) {
                resetConnect();
                return;
            }
            boolean connected = channel.isConnectionPending();
            if (connected || channel.connect(address)) {
                connected = channel.finishConnect();
            }
            channel.configureBlocking(false);
            if (connected) {
                resetConnect();
                handler.completed(null, attachment);
            } else {
                commonWorker.addRegister(selector -> {
                    try {
                        channel.register(selector, SelectionKey.OP_CONNECT, (Consumer<SelectionKey>) selectionKey -> doConnect(address, attachment, handler));
                    } catch (ClosedChannelException e) {
                        handler.failed(e, attachment);
                    }
                });
            }
        } catch (IOException e) {
            handler.failed(e, attachment);
        }

    }

    private void resetConnect() {
        connectFuture = null;
    }

    public void doRead(boolean direct) {
        try {
            //此前通过Future调用,且触发了cancel
            if (readFuture != null && readFuture.isDone()) {
                channel.keyFor(readWorker.selector).interestOps(channel.keyFor(readWorker.selector).interestOps() & ~SelectionKey.OP_READ);
                resetRead();
                return;
            }
            if (lowMemory && direct && readBuffer == null) {
                CompletionHandler<Number, Object> completionHandler = readCompletionHandler;
                Object attach = readAttachment;
                resetRead();
                completionHandler.completed(EnhanceAsynchronousChannelProvider.READABLE_SIGNAL, attach);
                return;
            }
            boolean directRead = direct || (Thread.currentThread() == readWorker.getWorkerThread() && readWorker.invoker++ < EnhanceAsynchronousChannelGroup.MAX_INVOKER);

            long readSize = 0;
            if (directRead) {
                readSize = channel.read(readBuffer);
            }

            //注册至异步线程
            if (readFuture != null && readSize == 0) {
                group.registerFuture(selector -> {
                    try {
                        channel.register(selector, SelectionKey.OP_READ, EnhanceAsynchronousSocketChannel.this);
                    } catch (ClosedChannelException e) {
                        e.printStackTrace();
                        doRead(true);
                    }
                }, SelectionKey.OP_READ);
                return;
            }
            //释放内存
            if (lowMemory && readSize == 0 && readBuffer.position() == 0) {
                readBuffer = null;
                readCompletionHandler.completed(EnhanceAsynchronousChannelProvider.READ_MONITOR_SIGNAL, readAttachment);
            } else if (readSize != 0 || !readBuffer.hasRemaining()) {
                CompletionHandler<Number, Object> completionHandler = readCompletionHandler;
                Object attach = readAttachment;
                resetRead();
                completionHandler.completed((int) readSize, attach);
                return;
            }
            SelectionKey readSelectionKey = channel.keyFor(readWorker.selector);
            if (readSelectionKey == null) {
                readWorker.addRegister(selector -> {
                    try {
                        channel.register(selector, SelectionKey.OP_READ, EnhanceAsynchronousSocketChannel.this);
                    } catch (ClosedChannelException e) {
                        readCompletionHandler.failed(e, readAttachment);
                    }
                });
            } else {
                group.interestOps(readWorker, readSelectionKey, SelectionKey.OP_READ);
            }

        } catch (Throwable e) {
            if (readCompletionHandler == null) {
                e.printStackTrace();
                try {
                    close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            } else {
                readCompletionHandler.failed(e, readAttachment);
            }
        }
    }

    private void resetRead() {
        readPending = false;
        readFuture = null;
        readCompletionHandler = null;
        readAttachment = null;
        readBuffer = null;
    }

    public void doWrite() {
        try {
            //此前通过Future调用,且触发了cancel
            if (writeFuture != null && writeFuture.isDone()) {
                resetWrite();
                return;
            }
            int invoker = 0;
            //防止无限递归导致堆栈溢出
            if (commonWorker.getWorkerThread() == Thread.currentThread()) {
                invoker = ++commonWorker.invoker;
            } else if (readWorker.getWorkerThread() != Thread.currentThread()) {
                invoker = ++writeInvoker;
            }
            int writeSize = 0;
            if (invoker < EnhanceAsynchronousChannelGroup.MAX_INVOKER) {
                writeSize = channel.write(writeBuffer);
            } else {
                writeInvoker = 0;
            }

            //注册至异步线程
            if (writeFuture != null && writeBuffer.hasRemaining()) {
                group.registerFuture(selector -> {
                    try {
                        channel.register(selector, SelectionKey.OP_WRITE, EnhanceAsynchronousSocketChannel.this);
                    } catch (ClosedChannelException e) {
                        e.printStackTrace();
                        doWrite();
                    }
                }, SelectionKey.OP_WRITE);
                return;
            }

            //EOF 或者全部输出完毕
            if (writeSize == -1 || !writeBuffer.hasRemaining()) {
                CompletionHandler<Number, Object> completionHandler = writeCompletionHandler;
                Object attach = writeAttachment;
                resetWrite();
                completionHandler.completed(writeSize == -1 ? -1 : writeBuffer.limit(), attach);
                return;
            }

            SelectionKey writeSelectionKey = channel.keyFor(commonWorker.selector);
            if (writeSelectionKey == null) {
                commonWorker.addRegister(selector -> {
                    try {
                        channel.register(selector, SelectionKey.OP_WRITE, EnhanceAsynchronousSocketChannel.this);
                    } catch (ClosedChannelException e) {
                        writeCompletionHandler.failed(e, writeAttachment);
                    }
                });
            } else {
                group.interestOps(commonWorker, writeSelectionKey, SelectionKey.OP_WRITE);
            }
        } catch (Throwable e) {
            if (writeCompletionHandler == null) {
                e.printStackTrace();
                try {
                    close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            } else {
                writeCompletionHandler.failed(e, writeAttachment);
            }
        }
    }

    private void resetWrite() {
        writePending = false;
        writeFuture = null;
        writeAttachment = null;
        writeCompletionHandler = null;
        writeBuffer = null;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }
}
