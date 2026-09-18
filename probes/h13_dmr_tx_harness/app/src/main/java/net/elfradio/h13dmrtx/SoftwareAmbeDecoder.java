package net.elfradio.h13dmrtx;

final class SoftwareAmbeDecoder implements AutoCloseable {
    static {
        System.loadLibrary("h13_ambe");
    }

    private long handle;

    SoftwareAmbeDecoder() {
        handle = nativeCreate();
        if (handle == 0) {
            throw new IllegalStateException("创建软件AMBE解码器失败");
        }
    }

    synchronized short[] decode49BitPacked9(byte[] packed49) {
        return decodeDetailed49BitPacked9(packed49).pcm;
    }

    synchronized DecodeResult decodeDetailed49BitPacked9(byte[] packed49) {
        if (handle == 0) {
            throw new IllegalStateException("软件AMBE解码器已经关闭");
        }
        requireFrames(packed49, "49位参数");
        int[] frameErrors = new int[packed49.length / 9];
        short[] pcm = nativeDecode49BitPacked9Batch(handle, packed49,
                frameErrors);
        return new DecodeResult(pcm, frameErrors);
    }

    static byte[] channelDecodeTo49BitPacked9(byte[] dmrFrames) {
        requireFrames(dmrFrames, "DMR码字");
        return nativeChannelDecodeTo49BitPacked9Batch(dmrFrames);
    }

    synchronized DecodeResult decodeDmrReference(byte[] dmrFrames) {
        if (handle == 0) {
            throw new IllegalStateException("软件AMBE解码器已经关闭");
        }
        requireFrames(dmrFrames, "DMR码字");
        int[] frameErrors = new int[dmrFrames.length / 9];
        short[] pcm = nativeDecodeDmrReferenceBatch(handle, dmrFrames,
                frameErrors);
        return new DecodeResult(pcm, frameErrors);
    }

    private static void requireFrames(byte[] frames, String name) {
        if (frames == null || frames.length == 0 || frames.length % 9 != 0) {
            throw new IllegalArgumentException(name + "必须为9字节帧的非零倍数");
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
    private static native byte[] nativeChannelDecodeTo49BitPacked9Batch(
            byte[] dmrFrames);
    private static native short[] nativeDecodeDmrReferenceBatch(long handle,
            byte[] dmrFrames, int[] frameErrors);
    private static native short[] nativeDecode49BitPacked9Batch(long handle,
            byte[] packed49, int[] frameErrors);

    static final class DecodeResult {
        final short[] pcm;
        final int[] frameErrors;

        DecodeResult(short[] pcm, int[] frameErrors) {
            this.pcm = pcm.clone();
            this.frameErrors = frameErrors.clone();
        }
    }
}
