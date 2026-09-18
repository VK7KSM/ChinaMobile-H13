package net.elfradio.h13dmrtx;

import java.util.Locale;

final class AudioMetrics {
    final int samples;
    final double rms;
    final double coherentAmplitude;
    final double dominantFrequency;
    final double dominantAmplitude;
    final double activeRatio;

    private AudioMetrics(int samples, double rms, double coherentAmplitude,
            double dominantFrequency, double dominantAmplitude,
            double activeRatio) {
        this.samples = samples;
        this.rms = rms;
        this.coherentAmplitude = coherentAmplitude;
        this.dominantFrequency = dominantFrequency;
        this.dominantAmplitude = dominantAmplitude;
        this.activeRatio = activeRatio;
    }

    static AudioMetrics measure(short[] pcm, int sampleRate,
            double targetFrequency) {
        if (pcm == null || pcm.length < sampleRate / 5 || sampleRate <= 0) {
            throw new IllegalArgumentException("软件音频指标输入不足");
        }
        int skip = Math.min(sampleRate / 5, pcm.length / 4);
        int count = pcm.length - skip;
        double sumSquares = 0.0;
        int active = 0;
        for (int index = skip; index < pcm.length; index++) {
            double value = pcm[index];
            sumSquares += value * value;
            if (Math.abs(value) >= 16) {
                active++;
            }
        }
        double target = amplitudeAt(pcm, skip, sampleRate, targetFrequency);
        double bestFrequency = 0.0;
        double bestAmplitude = -1.0;
        for (double frequency = 200.0; frequency <= 1800.0;
                frequency += 10.0) {
            double amplitude = amplitudeAt(pcm, skip, sampleRate, frequency);
            if (amplitude > bestAmplitude) {
                bestAmplitude = amplitude;
                bestFrequency = frequency;
            }
        }
        return new AudioMetrics(count, Math.sqrt(sumSquares / count), target,
                bestFrequency, bestAmplitude, active / (double) count);
    }

    void requireTone800() {
        if (rms < 100.0 || coherentAmplitude < rms * 0.35
                || dominantFrequency < 760.0 || dominantFrequency > 840.0
                || activeRatio < 0.20) {
            throw new IllegalStateException("软件解码800赫兹硬门失败: "
                    + report());
        }
    }

    void requireSoftwareAmbeTone800() {
        if (rms < 100.0 || coherentAmplitude < rms * 0.08
                || dominantFrequency < 760.0 || dominantFrequency > 840.0
                || dominantAmplitude < rms * 0.25 || activeRatio < 0.20) {
            throw new IllegalStateException("软件AMBE解码800赫兹硬门失败: "
                    + report());
        }
    }

    String report() {
        return String.format(Locale.US,
                "samples=%d\nrms=%.3f\ncoherent_800=%.3f\n"
                + "dominant_hz=%.1f\ndominant_amplitude=%.3f\n"
                + "active_ratio=%.6f\n",
                samples, rms, coherentAmplitude, dominantFrequency,
                dominantAmplitude, activeRatio);
    }

    private static double amplitudeAt(short[] pcm, int start, int sampleRate,
            double frequency) {
        double sine = 0.0;
        double cosine = 0.0;
        int count = pcm.length - start;
        for (int index = start; index < pcm.length; index++) {
            double phase = 2.0 * Math.PI * frequency * (index - start)
                    / sampleRate;
            sine += pcm[index] * Math.sin(phase);
            cosine += pcm[index] * Math.cos(phase);
        }
        return 2.0 * Math.hypot(sine, cosine) / count;
    }
}
