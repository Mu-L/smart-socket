package org.smartboot.socket.buffer;

/**
 * @author qinluo
 * @date 2022-06-06 18:39:32
 * @since 1.0.0
 */
public interface BufferPageFactory {

    /**
     * Create a BufferPage instance.
     *
     * @param size    size
     * @param direct  use directly memory
     * @return bufferPage
     */
    BufferPage create(int size, boolean direct);

}
