package net.vinote.demo;

import org.smartboot.plugin.AioMonitor;
import org.smartboot.socket.transport.AioQuickServer;
import org.smartboot.socket.transport.AioSession;
import org.smartboot.socket.transport.IoServerConfig;
import org.smartboot.socket.transport.ReadCompletionHandler;
import org.smartboot.socket.transport.SessionFactory;
import org.smartboot.socket.transport.WriteCompletionHandler;

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.TimeUnit;

/**
 * Created by 三刀 on 2017/7/12.
 */
public class IntegerServer {
    public static void main(String[] args) throws IOException {
        AioQuickServer<Integer> server = new AioQuickServer<Integer>(8899, new IntegerProtocol(), new SessionFactory<Integer>() {

			@Override
			public AioSession<Integer> newSession(AsynchronousSocketChannel channel, IoServerConfig<Integer> config,
					ReadCompletionHandler<Integer> readCompletionHandler,
					WriteCompletionHandler<Integer> writeCompletionHandler, boolean serverSession) {
				return new IntegerServerSession(channel, config, readCompletionHandler, writeCompletionHandler, serverSession);
			}
        	
        }).setPlugins(new AioMonitor<>(5, TimeUnit.SECONDS));
        server.start();
    }
}
