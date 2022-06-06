package org.smartboot.socket.buffer;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * @author qinluo
 * @version 1.0.0
 * @date 2020-01-06 11:38
 */
public class UnSafeUtils {



    public static Unsafe getUnsafe() {
        return UnsafeHolder.unsafe;
    }


    private static class UnsafeHolder {
        private static Unsafe unsafe;

        static {
            try {
                unsafe = getUnsafe();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static Unsafe getUnsafe() throws Exception {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            boolean accessible = unsafeField.isAccessible();
            unsafeField.setAccessible(true);
            Unsafe us = (Unsafe) unsafeField.get(Unsafe.class);
            unsafeField.setAccessible(accessible);
            return us;
        }
    }
}
