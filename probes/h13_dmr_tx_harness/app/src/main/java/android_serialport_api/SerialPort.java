package android_serialport_api;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class SerialPort {
    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    static {
        System.loadLibrary("serial_port");
    }

    public SerialPort(File device, int baudRate, int flags) throws IOException {
        if (!device.canRead() || !device.canWrite()) {
            throw new SecurityException("Serial device is not readable and writable: " + device);
        }

        mFd = open(device.getAbsolutePath(), baudRate, flags);
        if (mFd == null) {
            throw new IOException("Native serial open returned null");
        }
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    public FileDescriptor getFileDescriptor() {
        return mFd;
    }

    private static native FileDescriptor open(String path, int baudRate, int flags);

    public native void close();

    public native void disableClock();

    public native void enableClock();
}
