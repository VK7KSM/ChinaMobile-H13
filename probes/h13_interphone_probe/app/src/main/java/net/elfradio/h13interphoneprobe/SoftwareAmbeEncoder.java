package net.elfradio.h13interphoneprobe;

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
}
