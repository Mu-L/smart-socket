/*
 * Copyright (c) 2017, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: ReadCompletionHandler.java
 * Date: 2017-11-25
 * Author: sandao
 */

package org.smartboot.socket.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartboot.socket.Plugin;
import org.smartboot.socket.StateMachineEnum;

import java.io.IOException;
import java.nio.channels.CompletionHandler;

/**
 * 读写事件回调处理类
 *
 * @author 三刀
 * @version V1.0.0
 */
public final class ReadCompletionHandler<T> implements CompletionHandler<Integer, AioSession<T>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReadCompletionHandler.class);

    @Override
    public void completed(final Integer result, final AioSession<T> aioSession) {
        // 接收到的消息进行预处理
        for (Plugin<T> h : aioSession.getServerConfig().getPlugins()) {
            h.readCompleted(aioSession, result);
        }
        aioSession.readFromChannel(result == -1);
    }

    @Override
    public void failed(Throwable exc, AioSession<T> aioSession) {
        if (exc instanceof IOException) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("session:{} will be closed,msg:{}", aioSession.getSessionID(), exc.getMessage());
            }
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("smart-socket read fail:", exc);
            }
        }

        try {
            aioSession.stateEvent(StateMachineEnum.INPUT_EXCEPTION, exc);
        } catch (Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
        try {
            aioSession.close();
        } catch (Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
    }
}