package org.smartboot.socket.buffer;

import sun.misc.Unsafe;
import sun.nio.ch.DirectBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author qinluo
 * @date 2022-06-06 18:51:47
 * @since 1.0.0
 */
public class FixedBufferPage extends BufferPage {

    /**
     * Clean
     */
    private static final int CLEAN = 0;

    /**
     * Dirty
     */
    private static final int DIRTY = 1;
    private static final int FIXED_SIZE = 4096;

    private static final long ARRAY_BASE_OFFSET;
    private static final int ARRAY_SHIFT;
    private static final long CURSOR_OFFSET;
    private static final Unsafe UNSAFE = UnSafeUtils.getUnsafe();

    /**
     * The bufferPage's size.
     */
    private final int size;
    private final int part;
    private final VirtualBuffer[] virtualBuffers;
    private final int[] bitMap;
    private volatile boolean running;
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

        if (used.sum() >= part) {
            return null;
        }
        VirtualBuffer allocated = null;
        used.add(1);
        int start = cursor;
        for (;;) {
            if (UNSAFE.compareAndSwapInt(this, CURSOR_OFFSET, start, start + 1)) {
                if (start < part && bitMap[start] == CLEAN && UNSAFE.compareAndSwapInt(bitMap, ARRAY_BASE_OFFSET + ((long)start << ARRAY_SHIFT), CLEAN, DIRTY)) {
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

        // slow allocated
        for (int i = 0; i < part; i++) {
            if (bitMap[i] == CLEAN && UNSAFE.compareAndSwapInt(bitMap, ARRAY_BASE_OFFSET + ((long)i << ARRAY_SHIFT), CLEAN, DIRTY)) {
                allocated = virtualBuffers[i];
                break;
            }
        }

        if (allocated != null) {
            allocated.buffer().clear();
            allocated.recycle();
        }
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

        used.add(-1);
        int cleanIndex = cleanBuffer.getIndex();
        if (bitMap[cleanIndex] == CLEAN) {
            // Error invoke.
            return;
        }

        UNSAFE.compareAndSwapInt(bitMap, ARRAY_BASE_OFFSET + ((long)cleanIndex << ARRAY_SHIFT), DIRTY, CLEAN);
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
            ByteBuffer buffer = virtualBuffers[i].buffer();
            if (buffer instanceof DirectBuffer) {
                ((DirectBuffer)buffer).cleaner().clean();
            }
            virtualBuffers[i] = null;
        }
        // invoke super
        super.release();
    }

    @Override
    public String toString() {
        return "fixed-buffer-page, size = " + size + " used-buffers = " + used.sum();
    }

    static {
        try {
            ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(int[].class);
            int arrayScale = UNSAFE.arrayIndexScale(int[].class);
            ARRAY_SHIFT = 31 - Integer.numberOfLeadingZeros(arrayScale);
            CURSOR_OFFSET = UNSAFE.objectFieldOffset(FixedBufferPage.class.getDeclaredField("cursor"));
        } catch (Exception e) {
            throw new RuntimeException("unable to find object offset", e);
        }
    }
}
