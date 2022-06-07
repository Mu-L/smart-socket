package org.smartboot.socket.buffer;

import sun.misc.Unsafe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author qinluo
 * @date 2022-06-06 18:51:47
 * @since 1.0.0
 */
public class FixedBufferPage extends BufferPage {

    private static final int CLEAN = 0;
    private static final int DIRTY = 1;
    private static final int FIXED_SIZE = 4096;

    private static long arrayOffset;
    private static int arrayScale;
    private static int shift;
    private static long cursorShift;

    private int size;
    private int part;
    private VirtualBuffer[] virtualBuffers;
    private int[] bitMap;
    private volatile boolean running;
    private static final Unsafe unsafe = UnSafeUtils.getUnsafe();
    private final LongAdder allocatedCnt = new LongAdder();
    private final LongAdder allocatedTimes = new LongAdder();
    private final LongAdder releasedCnt = new LongAdder();
    private final LongAdder releasedTimes = new LongAdder();
    private final LongAdder used = new LongAdder();
    private int cursor;

    FixedBufferPage(BufferPage[] poolPages, int size, boolean direct) {
        super(poolPages, 0, direct);

        int newsize = (size / FIXED_SIZE) * FIXED_SIZE;
        if (newsize < size) {
            newsize += FIXED_SIZE;
        }

        this.size = newsize;
        this.part = newsize / FIXED_SIZE;
        this.virtualBuffers = new VirtualBuffer[part];
        this.bitMap = new int[part];
        for (int i = 0; i < part; i++) {
            VirtualBuffer vb = new VirtualBuffer(this, allocate(direct), 0, FIXED_SIZE);
            virtualBuffers[i] = vb;
            vb.setIndex(i);
            this.bitMap[i] = CLEAN;
        }
        this.running = true;
    }

    private ByteBuffer allocate(boolean direct) {
        return direct ? ByteBuffer.allocateDirect(FixedBufferPage.FIXED_SIZE) : ByteBuffer.allocate(FixedBufferPage.FIXED_SIZE);
    }

    @Override
    public VirtualBuffer allocate(int size) {
        if (!running) {
            return null;
        }

        Thread thread = Thread.currentThread();
        if (thread instanceof FastBufferThread) {
            FastBufferThread fastBufferThread = (FastBufferThread) thread;
            if (poolPages[fastBufferThread.getPageIndex()] != this) {
                return poolPages[fastBufferThread.getPageIndex()].allocate(size);
            }
        }

        long escaped = System.nanoTime();
        allocatedCnt.add(1);
        if (used.sum() >= part) {
            return null;
        }
        VirtualBuffer allocated = null;
        used.add(1);
        int start = cursor;
        for (;;) {
            if (unsafe.compareAndSwapInt(this, cursorShift, start, start + 1)) {
                if (start < part && bitMap[start] == CLEAN && unsafe.compareAndSwapInt(bitMap, arrayOffset + ((long)start << shift), CLEAN, DIRTY)) {
                    allocated = virtualBuffers[start];
                    allocated.buffer().clear();
                    allocated.recycle();
                    return allocated;
                }
            }
            start = cursor;
            if (start > part) {
                cursor = 0;
                break;
            }

        }



        for (int i = 0; i < part; i++) {
            if (bitMap[i] == CLEAN && unsafe.compareAndSwapInt(bitMap, arrayOffset + ((long)i << shift), CLEAN, DIRTY)) {
                allocated = virtualBuffers[i];
                break;
            }
        }

        if (allocated != null) {
            allocated.buffer().clear();
            allocated.recycle();
        }

        allocatedTimes.add(System.nanoTime() - escaped);
        return allocated;
    }

    @Override
    void clean(VirtualBuffer cleanBuffer) {
        if (!running) {
            return;
        }

        if (cleanBuffer == null || cleanBuffer.getIndex() < 0) {
            return;
        }

        long time = System.nanoTime();
        releasedCnt.add(1);
        used.add(-1);
        int cleanIndex = cleanBuffer.getIndex();
        if (bitMap[cleanIndex] == CLEAN) {
            // Error invoke.
            System.out.println("Error clean in index " + cleanIndex);
            return;
        }

        if (unsafe.compareAndSwapInt(bitMap, arrayOffset + ((long)cleanIndex << shift), DIRTY, CLEAN)) {

        } else {
            System.out.println("failed to clean");
        }

        releasedTimes.add(System.nanoTime() - time);
    }

    @Override
    void tryClean() {
        // do nothing
    }

    @Override
    void release() {
        this.running = false;

        for (int i = 0; i < virtualBuffers.length; i++) {
            bitMap[i] = DIRTY;
            virtualBuffers[i] = null;
        }

    }

    @Override
    public String toString() {
        long ac = allocatedCnt.longValue();
        long at = allocatedTimes.longValue();
        long rc = releasedCnt.longValue();
        long rt = releasedTimes.longValue();


        return String.format("fixed-buffer-page size, allocatedTimes(%d)/allocatedCnt(%d) = %.10f, releaseTimes(%d)/releaseCnt(%d) = %.10f ",
                at, ac, at*1.0/ac, rt, rc, rt*1.0/rc);
    }

    static {
        try {
            arrayOffset = unsafe.arrayBaseOffset(int[].class);
            arrayScale = unsafe.arrayIndexScale(int[].class);
            shift = 31 - Integer.numberOfLeadingZeros(arrayScale);
            cursorShift = unsafe.objectFieldOffset(FixedBufferPage.class.getDeclaredField("cursor"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
