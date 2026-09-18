#include <errno.h>
#include <jni.h>
#include <linux/serial.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <time.h>

#define UART_DIAG_SCHEMA 1
#define UART_DIAG_VALUE_COUNT 25
#define UART_DRAIN_SCHEMA 1
#define UART_DRAIN_VALUE_COUNT 16
#define UART_DRAIN_POLL_NS 200000L

static speed_t speed_to_baud(speed_t value) {
    switch (value) {
        case B57600: return 57600;
        case B115200: return 115200;
        case B230400: return 230400;
        case B460800: return 460800;
        default: return 0;
    }
}

static int file_descriptor_value(JNIEnv *env, jobject descriptor) {
    jclass cls = (*env)->GetObjectClass(env, descriptor);
    jfieldID field = (*env)->GetFieldID(env, cls, "descriptor", "I");
    if (field == NULL) {
        (*env)->ExceptionClear(env);
        field = (*env)->GetFieldID(env, cls, "fd", "I");
    }
    if (field == NULL) {
        return -1;
    }
    return (*env)->GetIntField(env, descriptor, field);
}

static int64_t monotonic_nanos(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }
    return (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
}

static int sleep_nanos(int64_t duration) {
    struct timespec request;
    struct timespec remaining;
    if (duration <= 0) {
        return 0;
    }
    request.tv_sec = duration / 1000000000LL;
    request.tv_nsec = duration % 1000000000LL;
    while (nanosleep(&request, &remaining) != 0) {
        if (errno != EINTR) {
            return -1;
        }
        request = remaining;
    }
    return 0;
}

static jlongArray new_long_array(JNIEnv *env, const jlong *values,
        jsize count) {
    jlongArray result = (*env)->NewLongArray(env, count);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, count, values);
    }
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_android_1serialport_1api_UartDiagnostics_nativeSnapshot(
        JNIEnv *env, jclass type, jobject descriptor) {
    (void) type;
    jlong values[UART_DIAG_VALUE_COUNT];
    memset(values, 0, sizeof(values));
    values[0] = UART_DIAG_SCHEMA;

    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) == 0) {
        values[1] = (jlong) now.tv_sec * 1000000000LL + now.tv_nsec;
    }

    int fd = file_descriptor_value(env, descriptor);
    if (fd < 0 || (*env)->ExceptionCheck(env)) {
        return NULL;
    }

    struct termios settings;
    memset(&settings, 0, sizeof(settings));
    errno = 0;
    int termios_result = ioctl(fd, TCGETS, &settings);
    values[2] = termios_result;
    values[3] = termios_result == 0 ? 0 : errno;
    if (termios_result == 0) {
        speed_t input_speed = cfgetispeed(&settings);
        speed_t output_speed = cfgetospeed(&settings);
        values[6] = (uint32_t) settings.c_iflag;
        values[7] = (uint32_t) settings.c_oflag;
        values[8] = (uint32_t) settings.c_cflag;
        values[9] = (uint32_t) settings.c_lflag;
        values[10] = (uint32_t) input_speed;
        values[11] = (uint32_t) output_speed;
        values[12] = speed_to_baud(input_speed);
        values[13] = speed_to_baud(output_speed);
    }

    struct serial_icounter_struct counters;
    memset(&counters, 0, sizeof(counters));
    errno = 0;
    int icount_result = ioctl(fd, TIOCGICOUNT, &counters);
    values[4] = icount_result;
    values[5] = icount_result == 0 ? 0 : errno;
    if (icount_result == 0) {
        values[14] = (uint32_t) counters.cts;
        values[15] = (uint32_t) counters.dsr;
        values[16] = (uint32_t) counters.rng;
        values[17] = (uint32_t) counters.dcd;
        values[18] = (uint32_t) counters.rx;
        values[19] = (uint32_t) counters.tx;
        values[20] = (uint32_t) counters.buf_overrun;
        values[21] = (uint32_t) counters.frame;
        values[22] = (uint32_t) counters.overrun;
        values[23] = (uint32_t) counters.parity;
        values[24] = (uint32_t) counters.brk;
    }

    return new_long_array(env, values, UART_DIAG_VALUE_COUNT);
}

JNIEXPORT jlongArray JNICALL
Java_android_1serialport_1api_UartDiagnostics_nativeDrainTransmitter(
        JNIEnv *env, jclass type, jobject descriptor, jint baud_rate,
        jint byte_count, jint timeout_ms) {
    (void) type;
    jlong values[UART_DRAIN_VALUE_COUNT];
    memset(values, 0, sizeof(values));
    values[0] = UART_DRAIN_SCHEMA;
    values[1] = monotonic_nanos();
    values[3] = -1;
    values[5] = -1;
    values[8] = -1;
    values[13] = -1;

    int fd = file_descriptor_value(env, descriptor);
    if (fd < 0 || (*env)->ExceptionCheck(env) || baud_rate <= 0
            || byte_count <= 0 || timeout_ms <= 0) {
        values[4] = EINVAL;
        values[2] = monotonic_nanos();
        return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
    }

    int64_t deadline = values[1] + (int64_t) timeout_ms * 1000000LL;
    int queued = -1;
    errno = 0;
    int outq_result = ioctl(fd, TIOCOUTQ, &queued);
    values[5] = outq_result;
    values[6] = outq_result == 0 ? 0 : errno;
    values[7] = queued;
    if (outq_result != 0) {
        values[4] = values[6];
        values[2] = monotonic_nanos();
        return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
    }

    while (queued != 0 && monotonic_nanos() < deadline) {
        if (sleep_nanos(UART_DRAIN_POLL_NS) != 0) {
            values[4] = errno;
            values[2] = monotonic_nanos();
            return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
        }
        values[11]++;
        errno = 0;
        outq_result = ioctl(fd, TIOCOUTQ, &queued);
        if (outq_result != 0) {
            values[4] = errno;
            values[2] = monotonic_nanos();
            return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
        }
    }
    if (queued != 0) {
        values[3] = -2;
        values[4] = ETIMEDOUT;
        values[8] = 0;
        values[10] = queued;
        values[2] = monotonic_nanos();
        return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
    }

    int64_t guard_nanos = ((int64_t) byte_count * 10LL * 1000000000LL
            + baud_rate - 1) / baud_rate;
    values[12] = guard_nanos;
    int64_t guard_deadline = values[1] + guard_nanos;
    int64_t guard_now = monotonic_nanos();
    int64_t guard_remaining = guard_deadline - guard_now;
    if (guard_now < 0 || guard_deadline > deadline
            || (guard_remaining > 0 && sleep_nanos(guard_remaining) != 0)) {
        values[3] = -2;
        values[4] = errno == 0 ? ETIMEDOUT : errno;
        values[2] = monotonic_nanos();
        return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
    }

    errno = 0;
    outq_result = ioctl(fd, TIOCOUTQ, &queued);
    values[8] = outq_result;
    values[9] = outq_result == 0 ? 0 : errno;
    values[10] = queued;
    if (outq_result != 0 || queued != 0) {
        values[4] = outq_result == 0 ? EBUSY : values[9];
        values[2] = monotonic_nanos();
        return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
    }

    int lsr = 0;
    errno = 0;
    int lsr_result = ioctl(fd, TIOCSERGETLSR, &lsr);
    values[13] = lsr_result;
    values[14] = lsr_result == 0 ? 0 : errno;
    values[15] = lsr;
    if (lsr_result != 0 && errno != ENOTTY && errno != EINVAL) {
        values[4] = errno;
        values[2] = monotonic_nanos();
        return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
    }
    if (lsr_result == 0 && (lsr & TIOCSER_TEMT) == 0) {
        values[4] = EBUSY;
        values[2] = monotonic_nanos();
        return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
    }

    values[3] = 0;
    values[4] = 0;
    values[2] = monotonic_nanos();
    return new_long_array(env, values, UART_DRAIN_VALUE_COUNT);
}
