package org.smartboot.socket.buffer;

/**
 * @author qinluo
 * @date 2022-06-06 18:44:30
 * @since 1.0.0
 */
public class DefaultBufferPageFactory implements BufferPageFactory {

    private static final BufferPage[] EMPTY = new BufferPage[0];
    private static final String KEY = "smart-socket-memory-optimized";


    @Override
    public BufferPage create(int size, boolean direct) {
        if (Boolean.getBoolean(KEY)) {
            return new FixedBufferPage(EMPTY, size, direct);
        }

        return new BufferPage(EMPTY, size, direct);
    }
}
