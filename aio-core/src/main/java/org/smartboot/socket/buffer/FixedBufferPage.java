package org.smartboot.socket.buffer;

import sun.misc.Unsafe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author qinluo
 * @date 2022-06-06 18:51:47
 * @since 1.0.0
 */
public class FixedBufferPage extends BufferPage {

    private static final int CLEAN = 0;
    private static final int DIRTY = 1;
    private static final int FIXED_SIZE = 1024;

    private static long arrayOffset;
    private static int arrayScale;
    private static int shift;

    private int size;
    private int part;
    private VirtualBuffer[] virtualBuffers;
    private int[] bitMap;
    private volatile boolean running;

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
            VirtualBuffer vb = new VirtualBuffer(this, allocate(direct), 0, 0);
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

        VirtualBuffer allocated = null;
        for (int i = 0; i < part; i++) {
            if (bitMap[i] == CLEAN && Unsafe.getUnsafe().compareAndSwapInt(bitMap, arrayOffset + shift << i, CLEAN, DIRTY)) {
                allocated = virtualBuffers[i];
            }
        }

        if  (allocated == null) {
            // slow allocate in other page.
//            for (BufferPage poolPage : poolPages) {
//                if ((allocated = poolPage.allocate(size)) != null) {
//                    return allocated;
//                }
//            }
            System.out.println("disabled allocate from other page.");

        }

        return null;
    }

    @Override
    void clean(VirtualBuffer cleanBuffer) {
        if (!running) {
            return;
        }

        if (cleanBuffer == null || cleanBuffer.getIndex() < 0) {
            return;
        }

        int cleanIndex = cleanBuffer.getIndex();
        if (bitMap[cleanIndex] == CLEAN) {
            // Error invoke.
            System.out.println("Error clean in index " + cleanIndex);
        }

        Unsafe.getUnsafe().compareAndSwapInt(bitMap, arrayOffset + shift << cleanIndex, DIRTY, CLEAN);

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
        return "fixed-buffer-page size " + this.size + " bitMap " + Arrays.toString(bitMap);
    }

    static {
        arrayOffset = Unsafe.getUnsafe().arrayBaseOffset(int[].class);
        arrayScale = Unsafe.getUnsafe().arrayIndexScale(int[].class);
        shift = 31 - Integer.numberOfLeadingZeros(arrayScale);
    }
}
