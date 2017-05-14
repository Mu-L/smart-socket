package net.vinote.smart.socket.extension.timer;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.vinote.smart.socket.service.filter.SmartFilter;
import net.vinote.smart.socket.transport.TransportSession;

/**
 * 服务器监测定时器
 *
 * @author Seer
 * @version QuickMonitorTimer.java, v 0.1 2015年3月18日 下午11:25:21 Seer Exp.
 */
public class QuickMonitorTimer<T> extends QuickTimerTask implements SmartFilter<T> {
	private Logger logger = LogManager.getLogger(QuickMonitorTimer.class);
	/** 当前周期内消息 流量监控 */
	private AtomicLong flow = new AtomicLong(0);
	/** 当前周期内接受消息数 */
	private AtomicInteger recMsgnum = new AtomicInteger(0);

	/** 当前周期内丢弃消息数 */
	private AtomicInteger discardNum = new AtomicInteger(0);

	/** 当前周期内处理消息数 */
	private AtomicInteger processMsgNum = new AtomicInteger(0);

	/** 当前积压待处理的消息数 */
	private AtomicInteger messageStorage = new AtomicInteger(0);

	private volatile long totleProcessMsgNum = 0;

	@Override
	protected long getDelay() {
		return getPeriod();
	}

	@Override
	protected long getPeriod() {
		return TimeUnit.MINUTES.toMillis(1);
	}

	public void processFilter(TransportSession<T> session, T d) {
		processMsgNum.incrementAndGet();
		messageStorage.decrementAndGet();
		totleProcessMsgNum++;
	}

	public void readFilter(TransportSession<T> session, T d) {
		int length = session.getAttribute(TransportSession.ATTRIBUTE_KEY_CUR_DATA_LENGTH);
		flow.addAndGet(length);
		recMsgnum.incrementAndGet();
		messageStorage.incrementAndGet();
	}

	public void receiveFailHandler(TransportSession<T> session, T d) {
		discardNum.incrementAndGet();
		messageStorage.decrementAndGet();
		// logger.info("HexData -->" + StringUtils.toHexString((byte[])d));
	}

	@Override
	public void beginWriteFilter(TransportSession<T> session, ByteBuffer d) {

	}

	@Override
	public void continueWriteFilter(TransportSession<T> session, ByteBuffer d) {

	}

	@Override
	public void finishWriteFilter(TransportSession<T> session, ByteBuffer d) {

	}

	@Override
	public void run() {
		long curFlow = flow.getAndSet(0);
		int curRecMsgnum = recMsgnum.getAndSet(0);
		int curDiscardNum = discardNum.getAndSet(0);
		int curProcessMsgNum = processMsgNum.getAndSet(0);
		logger.info("\r\nFlow of Message:\t\t" + curFlow * 1.0 / (1024 * 1024) + "(MB)" + "\r\nNumber of Message:\t"
			+ curRecMsgnum + "\r\nAvg Size of Message:\t" + (curRecMsgnum > 0 ? curFlow * 1.0 / curRecMsgnum : 0)
			+ "(B)" + "\r\nNumber of Discard:\t" + curDiscardNum + "\r\nNum of Process Msg:\t" + curProcessMsgNum
			+ "\r\nStorage of Message:\t" + messageStorage.get() + "\r\nTotal Num of Process Msg:\t"
			+ totleProcessMsgNum);
	}


}
