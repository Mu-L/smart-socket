package org.smartboot.socket.test;

import org.smartboot.socket.MessageProcessor;
import org.smartboot.socket.StateMachineEnum;
import org.smartboot.socket.transport.AioSession;

/**
 * @author 三刀
 * @version V1.0 , 2017/8/23
 */
public class IntegerServerProcessor implements MessageProcessor<Integer> {
    @Override
    public void process(AioSession<Integer> session, Integer msg) {
        Integer respMsg = msg + 1;
        System.out.println("receive data from client: " + msg + " ,rsp:" + (respMsg));
        session.getOutputStream().writeInt(respMsg);
    }

    @Override
    public void stateEvent(AioSession<Integer> session, StateMachineEnum stateMachineEnum, Throwable throwable) {

    }
}
