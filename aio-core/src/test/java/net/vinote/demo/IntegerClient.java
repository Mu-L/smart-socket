package net.vinote.demo;

import java.nio.channels.AsynchronousSocketChannel;

import org.smartboot.socket.transport.AioQuickClient;
import org.smartboot.socket.transport.AioSession;
import org.smartboot.socket.transport.IoServerConfig;
import org.smartboot.socket.transport.ReadCompletionHandler;
import org.smartboot.socket.transport.SessionFactory;
import org.smartboot.socket.transport.WriteCompletionHandler;

/**
 * Created by 三刀 on 2017/7/12.
 */
public class IntegerClient {
	
	static class ClientSessionFactory implements SessionFactory<Integer>{

		@Override
		public AioSession<Integer> newSession(AsynchronousSocketChannel channel, IoServerConfig<Integer> config,
				ReadCompletionHandler<Integer> readCompletionHandler,
				WriteCompletionHandler<Integer> writeCompletionHandler, boolean serverSession) {
			return new IntegerClientSession(channel, config, readCompletionHandler, writeCompletionHandler, serverSession);
		}

	}
	
    public static void main(String[] args) throws Exception {
    	
        AioQuickClient<Integer> aioQuickClient = new AioQuickClient<Integer>("localhost", 8899, new IntegerProtocol(), new ClientSessionFactory());
        aioQuickClient.start();
        Thread.sleep(1000);
        aioQuickClient.shutdown();
    }
}
