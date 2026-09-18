package net.elfradio.h13dmrtx;

final class SoftwareAmbeEncoder implements AutoCloseable {
    static {
        System.loadLibrary("h13_ambe");
    }

    private long handle;

    SoftwareAmbeEncoder() {
        handle = nativeCreate();
        if (handle == 0) {
            throw new IllegalStateException("创建软件AMBE编码器失败");
        }
    }

    synchronized byte[] encode(short[] pcm) {
        if (handle == 0) {
            throw new IllegalStateException("软件AMBE编码器已经关闭");
        }
        return nativeEncodeBatch(handle, pcm);
    }

    static byte[] encode49BitPacked9(short[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % 160 != 0) {
            throw new IllegalArgumentException("49位编码PCM必须为160采样的非零倍数");
        }
        return nativeEncode49BitPacked9Batch(pcm);
    }

    static byte[] channelEncode49BitPacked9(byte[] packed49) {
        requirePacked49(packed49);
        return nativeChannelEncode49BitPacked9Batch(packed49);
    }

    private static void requirePacked49(byte[] packed49) {
        if (packed49 == null || packed49.length == 0
                || packed49.length % 9 != 0) {
            throw new IllegalArgumentException("49位参数必须为9字节帧的非零倍数");
        }
        for (int offset = 0; offset < packed49.length; offset += 9) {
            if ((packed49[offset + 6] & 0x7f) != 0
                    || packed49[offset + 7] != 0
                    || packed49[offset + 8] != 0) {
                throw new IllegalArgumentException("49位参数的填充位必须为零");
            }
        }
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native byte[] nativeEncodeBatch(long handle, short[] pcm);
    private static native byte[] nativeEncode49BitPacked9Batch(short[] pcm);
    private static native byte[] nativeChannelEncode49BitPacked9Batch(
            byte[] packed49);
}
