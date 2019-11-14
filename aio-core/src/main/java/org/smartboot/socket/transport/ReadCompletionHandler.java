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
import org.smartboot.socket.NetMonitor;
import org.smartboot.socket.StateMachineEnum;

import java.nio.channels.CompletionHandler;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 读写事件回调处理类
 *
 * @author 三刀
 * @version V1.0.0
 */
class ReadCompletionHandler<T> implements CompletionHandler<Integer, TcpAioSession<T>> {
    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ReadCompletionHandler.class);
    /**
     * 读回调资源信号量
     */
    private AtomicInteger semaphore;

    /**
     * 读会话缓存队列
     */
    private ConcurrentLinkedQueue<TcpAioSession<T>> cacheAioSessionQueue;

    /**
     * 应该可以不用volatile
     */
    private boolean needNotify = true;
    /**
     * 同步锁
     */
    private ReentrantLock lock = new ReentrantLock();
    /**
     * 非空条件
     */
    private final Condition notEmpty = lock.newCondition();

    ReadCompletionHandler() {
    }

    ReadCompletionHandler(AtomicInteger semaphore) {
        this.semaphore = semaphore;
        this.cacheAioSessionQueue = new ConcurrentLinkedQueue<>();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        TcpAioSession aioSession = cacheAioSessionQueue.poll();
                        if (aioSession != null) {
                            completed0(aioSession.getLastReadSize(), aioSession);
                            synchronized (this) {
                                this.wait(100);
                            }
                            continue;
                        }
                        if (!lock.tryLock()) {
                            synchronized (this) {
                                this.wait(100);
                            }
                            continue;
                        }
                        try {
                            needNotify = true;
                            notEmpty.await();
                        } finally {
                            lock.unlock();
                        }

                    } catch (InterruptedException e) {
                        LOGGER.error("", e);
                    }
                }
            }
        }, "smart-socket:watchman");
        t.setDaemon(true);
        t.setPriority(1);
        t.start();
    }


    @Override
    public void completed(final Integer result, final TcpAioSession<T> aioSession) {
        aioSession.setLastReadSize(result);
        if (semaphore != null && aioSession.getThreadReference() == null) {
            aioSession.setThreadReference(new AtomicReference<Thread>());
        }
        if (semaphore == null || aioSession.getThreadReference().get() == Thread.currentThread()) {
            runRingBufferTask();
            completed0(result, aioSession);
            return;
        }
        try {
            if (semaphore.getAndDecrement() > 0) {
                Thread thread = Thread.currentThread();
                aioSession.getThreadReference().set(thread);
                completed0(result, aioSession);
                runRingBufferTask();
                aioSession.getThreadReference().compareAndSet(thread, null);
                return;
            }
        } finally {
            semaphore.incrementAndGet();
        }

        cacheAioSessionQueue.offer(aioSession);
        if (needNotify && lock.tryLock()) {
            try {
                needNotify = false;
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 执行异步队列中的任务
     */
    private void runRingBufferTask() {
        if (cacheAioSessionQueue == null) {
            return;
        }
        TcpAioSession<T> aioSession = cacheAioSessionQueue.poll();
        if (aioSession == null) {
            return;
        }
        Thread thread = Thread.currentThread();
        do {
            aioSession.getThreadReference().set(thread);
            completed0(aioSession.getLastReadSize(), aioSession);
            aioSession.getThreadReference().compareAndSet(thread, null);
        } while ((aioSession = cacheAioSessionQueue.poll()) != null);
    }

    private void completed0(final Integer result, final TcpAioSession<T> aioSession) {
        try {
            // 接收到的消息进行预处理
            NetMonitor<T> monitor = aioSession.getServerConfig().getMonitor();
            if (monitor != null) {
                monitor.afterRead(aioSession, result);
            }
            aioSession.readFromChannel(result == -1);
        } catch (Exception e) {
            failed(e, aioSession);
        }
    }

    @Override
    public void failed(Throwable exc, TcpAioSession<T> aioSession) {
        try {
            aioSession.getServerConfig().getProcessor().stateEvent(aioSession, StateMachineEnum.INPUT_EXCEPTION, exc);
        } catch (Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
        try {
            aioSession.close(false);
        } catch (Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
    }
}