/*
 * Copyright (c) 2017, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: IoServerConfig.java
 * Date: 2017-11-25
 * Author: sandao
 */

package org.smartboot.socket.transport;

import java.net.SocketOption;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartboot.socket.Plugin;
import org.smartboot.socket.Protocol;
import org.smartboot.socket.StateMachineEnum;

/**
 * Quickly服务端/客户端配置信息 T:解码后生成的对象类型
 *
 * @author 三刀
 * @version V1.0.0
 */
public final class IoServerConfig<T> {

    public static final String BANNER = "                               _                           _             _   \n" +
            "                              ( )_                        ( )           ( )_ \n" +
            "  ___   ___ ___     _ _  _ __ | ,_)     ___    _      ___ | |/')    __  | ,_)\n" +
            "/',__)/' _ ` _ `\\ /'_` )( '__)| |     /',__) /'_`\\  /'___)| , <   /'__`\\| |  \n" +
            "\\__, \\| ( ) ( ) |( (_| || |   | |_    \\__, \\( (_) )( (___ | |\\`\\ (  ___/| |_ \n" +
            "(____/(_) (_) (_)`\\__,_)(_)   `\\__)   (____/`\\___/'`\\____)(_) (_)`\\____)`\\__)";

    public static final String VERSION = "v1.3.11";
    /**
     * 消息队列缓存大小
     */
    private int writeQueueSize = 0;

    /**
     * 消息体缓存大小,字节
     */
    private int readBufferSize = 512;

    /**
     * 远程服务器IP
     */
    private String host;


    /**
     * 服务器消息拦截器
     */
    private Set<Plugin<T>> plugins = new HashSet<>();

    /**
     * 服务器端口号
     */
    private int port = 8888;

    /**
     * 协议编解码
     */
    private Protocol<T> protocol;

    private boolean directBuffer;

    /**
     * 服务器处理线程数
     */
    private int threadNum = Runtime.getRuntime().availableProcessors() + 1;

    private float limitRate = 0.9f;

    private float releaseRate = 0.6f;
    /**
     * 流控指标线
     */
    private int flowLimitLine = (int) (writeQueueSize * limitRate);

    /**
     * 释放流控指标线
     */
    private int releaseLine = (int) (writeQueueSize * releaseRate);

    /**
     * 是否启用控制台banner
     */
    private boolean bannerEnabled = true;

    /**
     * 是否启用流控功能
     */
    private boolean flowControlEnabled = true;

    /**
     * Socket 配置
     */
    private Map<SocketOption<Object>, Object> socketOptions;

    /**
     * Session 工厂
     * @return
     */
    private SessionFactory<T> sessionFactory;
    
    /**
     * 默认的Session工厂 
     */
    private final SessionFactory<T> defaultFactory = new SessionFactory<T>() {

		final Logger logger = LoggerFactory.getLogger(AioSession.class);

		@Override
		public AioSession<T> newSession(AsynchronousSocketChannel channel, IoServerConfig<T> config,
										ReadCompletionHandler<T> readCompletionHandler, WriteCompletionHandler<T> writeCompletionHandler,
										boolean serverSession) {
			
			
			
			return new AioSession<T>(channel, config, readCompletionHandler, writeCompletionHandler, serverSession) {
				
				@Override
				protected void process(T msg) throws Exception {
					logger.info("process:{}, msg:{}", toString(), msg);					
				}

				@Override
				protected void stateEvent(StateMachineEnum stateMachineEnum, Throwable throwable) {
					switch (stateMachineEnum) {
				    case NEW_SESSION:
				    	logger.info("new session:{}, throwable:{}", toString(), throwable);
				        break;
				    case INPUT_SHUTDOWN:
				    	logger.info("input shutdown:{}, throwable:{}", toString(), throwable);
				        break;
				    case INPUT_EXCEPTION:
				    	logger.info("input exception:{}, throwable:{}", toString(), throwable);
				        break;
				    case OUTPUT_EXCEPTION:
				    	logger.info("output exception:{}, throwable:{}", toString(), throwable);
				        break;
				    case SESSION_CLOSING:
				    	logger.info("session closing:{}, throwable:{}", toString(), throwable);
				        break;
				    case SESSION_CLOSED:
				    	logger.info("session closed:{{}}, throwable:{}", getSessionID(), throwable);
				        break;
				    case FLOW_LIMIT:
				    	logger.info("flow limit:{}, throwable:{}", toString(), throwable);
				        break;
				    case RELEASE_FLOW_LIMIT:
				    	logger.info("release flow limit:{}, throwable:{}", toString(), throwable);
				        break;
					default:
						break;
				}
				}
				
			};
		}
    	
    };
    
    public final String getHost() {
        return host;
    }

    public final void setHost(String host) {
        this.host = host;
    }

    public final int getPort() {
        return port;
    }

    public final void setPort(int port) {
        this.port = port;
    }

    public final int getThreadNum() {
        return threadNum;
    }

    public final void setThreadNum(int threadNum) {
        this.threadNum = threadNum;
    }

    public final Set<Plugin<T>> getPlugins() {
        return Collections.unmodifiableSet(plugins);
    }

    public final void addPlugin(Plugin<T> plugin) {
    	if(plugin != null) {
    		this.plugins.add(plugin);
    	}
    }
    
    public final void setPlugins(Plugin<T>[] plugins) {
        if (plugins != null) {
        	this.plugins.clear();
        	this.plugins.addAll(Arrays.asList(plugins));
        }
    }

    public Protocol<T> getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol<T> protocol) {
        this.protocol = protocol;
    }

    public int getWriteQueueSize() {
        return writeQueueSize;
    }

    public void setWriteQueueSize(int writeQueueSize) {
        this.writeQueueSize = writeQueueSize;
        flowLimitLine = (int) (writeQueueSize * limitRate);
        releaseLine = (int) (writeQueueSize * releaseRate);
    }

    public int getReadBufferSize() {
        return readBufferSize;
    }

    public void setReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
    }

    int getFlowLimitLine() {
        return flowLimitLine;
    }

    int getReleaseLine() {
        return releaseLine;
    }

    public boolean isBannerEnabled() {
        return bannerEnabled;
    }

    public void setBannerEnabled(boolean bannerEnabled) {
        this.bannerEnabled = bannerEnabled;
    }

    public boolean isDirectBuffer() {
        return directBuffer;
    }

    public void setDirectBuffer(boolean directBuffer) {
        this.directBuffer = directBuffer;
    }

    public Map<SocketOption<Object>, Object> getSocketOptions() {
        return socketOptions;
    }

    public void setOption(SocketOption socketOption, Object f) {
        if (socketOptions == null) {
            socketOptions = new HashMap<>();
        }
        socketOptions.put(socketOption, f);
    }

    public boolean isFlowControlEnabled() {
        return flowControlEnabled;
    }

    public void setFlowControlEnabled(boolean flowControlEnabled) {
        this.flowControlEnabled = flowControlEnabled;
    }

    public void setSessionFactory(SessionFactory<T> factory) {
    	this.sessionFactory = factory;
    }
    
    public SessionFactory<T> getSessionFactory() {
    	if(this.sessionFactory != null) {
    		return this.sessionFactory;
    	}else {
    		return this.defaultFactory;
    	}
    }
    
    @Override
    public String toString() {
        return "IoServerConfig{" +
                "writeQueueSize=" + writeQueueSize +
                ", readBufferSize=" + readBufferSize +
                ", host='" + host + '\'' +
                ", plugins=" + Arrays.toString(plugins.toArray(new Plugin[plugins.size()])) +
                ", port=" + port +
                ", protocol=" + protocol +
                ", directBuffer=" + directBuffer +
                ", threadNum=" + threadNum +
                ", limitRate=" + limitRate +
                ", releaseRate=" + releaseRate +
                ", flowLimitLine=" + flowLimitLine +
                ", releaseLine=" + releaseLine +
                ", bannerEnabled=" + bannerEnabled +
                ", flowControlEnabled=" + flowControlEnabled +
                ", socketOptions=" + socketOptions +
                '}';
    }
}
