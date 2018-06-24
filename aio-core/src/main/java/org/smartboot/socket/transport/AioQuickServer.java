/*
 * Copyright (c) 2017, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: AioQuickServer.java
 * Date: 2017-11-25
 * Author: sandao
 */

package org.smartboot.socket.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Map;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartboot.socket.Plugin;
import org.smartboot.socket.Protocol;

/**
 * AIO服务端。
 *
 * <h2>示例：</h2>
 * <p>
 * <pre>
 * public class IntegerServer {
 *     public static void main(String[] args) throws IOException {
 *         AioQuickServer<Integer> server = new AioQuickServer<Integer>(8888, new IntegerProtocol(), new IntegerServerProcessor());
 *         server.start();
 *     }
 * }
 * </pre>
 * </p>
 *
 * @author 三刀
 * @version V1.0.0
 */
public class AioQuickServer<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AioQuickServer.class);
    /**
     * Server端服务配置。
     * <p>调用AioQuickServer的各setXX()方法，都是为了设置config的各配置项</p>
     */
    protected IoServerConfig<T> config = new IoServerConfig<>();
    /**
     * 读回调事件处理
     */
    protected ReadCompletionHandler<T> aioReadCompletionHandler = new ReadCompletionHandler<>();
    /**
     * 写回调事件处理
     */
    protected WriteCompletionHandler<T> aioWriteCompletionHandler = new WriteCompletionHandler<>();
    private AsynchronousServerSocketChannel serverSocketChannel = null;
    private AsynchronousChannelGroup asynchronousChannelGroup;
    
    /**
     * 设置服务端启动必要参数配置
     *
     * @param port             绑定服务端口号
     * @param protocol         协议编解码
     * @param sessionFactory   session工厂
     */
    public AioQuickServer(int port, Protocol<T> protocol, SessionFactory<T> sessionFactory) {
        config.setPort(port);
        config.setProtocol(protocol);
        config.setSessionFactory(sessionFactory);
    }

    /**
     * @param host             绑定服务端Host地址
     * @param port             绑定服务端口号
     * @param protocol         协议编解码
     * @param sessionFactory   session工厂
     */
    public AioQuickServer(String host, int port, Protocol<T> protocol, SessionFactory<T> sessionFactory) {
        this(port, protocol, sessionFactory);
        config.setHost(host);
    }

    /**
     * 启动Server端的AIO服务
     *
     * @throws IOException
     */
    public void start() throws IOException {
        if (config.isBannerEnabled()) {
            LOGGER.info(IoServerConfig.BANNER + "\r\n :: smart-socket ::\t(" + IoServerConfig.VERSION + ")");
        }
        start0();
    }

    /**
     * 内部启动逻辑
     *
     * @throws IOException
     */
    protected final void start0() throws IOException {
        try {
            asynchronousChannelGroup = AsynchronousChannelGroup.withFixedThreadPool(config.getThreadNum(), new ThreadFactory() {
                byte index = 0;

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "smart-socket:AIO-" + (++index));
                }
            });
            this.serverSocketChannel = AsynchronousServerSocketChannel.open(asynchronousChannelGroup);
            //set socket options
            if (config.getSocketOptions() != null) {
                for (Map.Entry<SocketOption<Object>, Object> entry : config.getSocketOptions().entrySet()) {
                    this.serverSocketChannel.setOption(entry.getKey(), entry.getValue());
                }
            }
            //bind host
            if (config.getHost() != null) {
                serverSocketChannel.bind(new InetSocketAddress(config.getHost(), config.getPort()), 1000);
            } else {
                serverSocketChannel.bind(new InetSocketAddress(config.getPort()), 1000);
            }
            serverSocketChannel.accept(serverSocketChannel, new CompletionHandler<AsynchronousSocketChannel, AsynchronousServerSocketChannel>() {
                @Override
                public void completed(final AsynchronousSocketChannel channel, AsynchronousServerSocketChannel serverSocketChannel) {
                    serverSocketChannel.accept(serverSocketChannel, this);
                    createSession(channel);
                }

                @Override
                public void failed(Throwable exc, AsynchronousServerSocketChannel serverSocketChannel) {
                    LOGGER.error("smart-socket server accept fail", exc);
                }
            });
        } catch (IOException e) {
            shutdown();
            throw e;
        }
        
        //start the plugins
        for(Plugin<T> plugin : config.getPlugins()) {
        	plugin.start();
        }
        
        LOGGER.info("smart-socket server started on port {}", config.getPort());
        LOGGER.info("smart-socket server config is {}", config);
    }

    /**
     * 为每个新建立的连接创建AIOSession对象
     *
     * @param channel
     */
    protected void createSession(AsynchronousSocketChannel channel) {
        //连接成功则构造AIOSession对象
    	AioSession<T> session = config.getSessionFactory().newSession(channel, config, aioReadCompletionHandler, aioWriteCompletionHandler, true);
        session.initSession();
    }

    /**
     * 停止服务端
     */
    public final void shutdown() {
        try {
            if (serverSocketChannel != null) {
                serverSocketChannel.close();
                serverSocketChannel = null;
            }
        } catch (IOException e) {
            LOGGER.warn(e.getMessage(), e);
        }
        if (asynchronousChannelGroup != null) {
            try {
				asynchronousChannelGroup.shutdownNow();
			} catch (IOException e) {
				LOGGER.warn(e.getMessage(), e);
			}
            asynchronousChannelGroup = null;
        }
        
        //close the plugins
        for(Plugin<T> plugin : config.getPlugins()) {
        	plugin.stop();
        }
    }

    /**
     * 设置处理线程数量
     *
     * @param num 线程数
     */
    public final AioQuickServer<T> setThreadNum(int num) {
        this.config.setThreadNum(num);
        return this;
    }


    /**
     * 设置插件,执行顺序以数组中的顺序为准
     *
     * @param plugins 插件数组
     */
    @SafeVarargs
    public final AioQuickServer<T> setPlugins(Plugin<T>... plugins) {
        this.config.setPlugins(plugins);
        return this;
    }


    /**
     * 设置输出队列缓冲区长度
     *
     * @param size 缓存队列长度
     */
    public final AioQuickServer<T> setWriteQueueSize(int size) {
        this.config.setWriteQueueSize(size);
        return this;
    }

    /**
     * 设置读缓存区大小
     *
     * @param size 单位：byte
     */
    public final AioQuickServer<T> setReadBufferSize(int size) {
        this.config.setReadBufferSize(size);
        return this;
    }

    /**
     * 是否启用控制台Banner打印
     *
     * @param bannerEnabled true:启用，false:禁用
     */
    public final AioQuickServer<T> setBannerEnabled(boolean bannerEnabled) {
        config.setBannerEnabled(bannerEnabled);
        return this;
    }

    /**
     * 是否启用DirectByteBuffer
     *
     * @param directBuffer true:启用，false:禁用
     */
    public final AioQuickServer<T> setDirectBuffer(boolean directBuffer) {
        config.setDirectBuffer(directBuffer);
        return this;
    }

    /**
     * 设置Socket的TCP参数配置。
     * <p>
     * AIO客户端的有效可选范围为：<br/>
     * 2. StandardSocketOptions.SO_RCVBUF<br/>
     * 4. StandardSocketOptions.SO_REUSEADDR<br/>
     * </p>
     *
     * @param socketOption 配置项
     * @param value        配置值
     * @return
     */
    public final <V> AioQuickServer<T> setOption(SocketOption<V> socketOption, V value) {
        config.setOption(socketOption, value);
        return this;
    }

    /**
     * 是否启用流控，默认：true。
     * <p>
     * 流控功能是服务端的这一种自我保护机制，用户可根据如下场景描述决定是否启用该功能。
     * <ol>
     * <li>场景一：客户端pull模式，客户端发送请求消息以获取服务端的响应，若客户端接收能力不足会导致服务端出现消息积压，建议启用流控功能。</li>
     * <li>场景二：服务端push模式，服务端主动推送消息至客户端，此类场景下若触发流控会导致服务端无法接收客户端的消息，建议关闭流控功能。</li>
     * </ol>
     * </p>
     *
     * @param flowControlEnabled 是否启用流控
     * @return
     */
    public final AioQuickServer<T> setFlowControlEnabled(boolean flowControlEnabled) {
        config.setFlowControlEnabled(flowControlEnabled);
        return this;
    }
}
