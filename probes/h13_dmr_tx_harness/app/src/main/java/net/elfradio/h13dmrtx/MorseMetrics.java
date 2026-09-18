package net.elfradio.h13dmrtx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MorseMetrics {
    private static final int SAMPLES_PER_FRAME = 160;
    private static final int MAX_LAG_FRAMES = 8;
    private static final double ACTIVE_INPUT_RMS = 1000.0;

    final int frames;
    final int bestLagFrames;
    final double envelopeCorrelation;
    final double activeRmsMean;
    final double inactiveRmsMean;
    final double activeInactiveRatio;
    final AudioMetrics activeAudio;

    private MorseMetrics(int frames, int bestLagFrames,
            double envelopeCorrelation, double activeRmsMean,
            double inactiveRmsMean, double activeInactiveRatio,
            AudioMetrics activeAudio) {
        this.frames = frames;
        this.bestLagFrames = bestLagFrames;
        this.envelopeCorrelation = envelopeCorrelation;
        this.activeRmsMean = activeRmsMean;
        this.inactiveRmsMean = inactiveRmsMean;
        this.activeInactiveRatio = activeInactiveRatio;
        this.activeAudio = activeAudio;
    }

    static MorseMetrics measure(short[] expectedPcm, short[] decodedPcm) {
        if (expectedPcm == null || decodedPcm == null
                || expectedPcm.length != decodedPcm.length
                || expectedPcm.length < SAMPLES_PER_FRAME * 20
                || expectedPcm.length % SAMPLES_PER_FRAME != 0) {
            throw new IllegalArgumentException("摩尔斯音频指标输入长度错误");
        }
        double[] expected = frameRms(expectedPcm);
        double[] decoded = frameRms(decodedPcm);
        int bestLag = -1;
        double bestCorrelation = -2.0;
        for (int lag = 0; lag <= MAX_LAG_FRAMES; ++lag) {
            double correlation = correlation(expected, decoded, lag);
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestLag = lag;
            }
        }
        List<Short> activeSamples = new ArrayList<>();
        double activeSum = 0.0;
        double inactiveSum = 0.0;
        int activeFrames = 0;
        int inactiveFrames = 0;
        for (int frame = 0; frame + bestLag < decoded.length; ++frame) {
            int decodedFrame = frame + bestLag;
            if (expected[frame] >= ACTIVE_INPUT_RMS) {
                activeSum += decoded[decodedFrame];
                activeFrames++;
                int start = decodedFrame * SAMPLES_PER_FRAME;
                for (int index = 0; index < SAMPLES_PER_FRAME; ++index) {
                    activeSamples.add(decodedPcm[start + index]);
                }
            } else {
                inactiveSum += decoded[decodedFrame];
                inactiveFrames++;
            }
        }
        if (activeFrames == 0 || inactiveFrames == 0) {
            throw new IllegalStateException("摩尔斯音频缺少按键或间隔区段");
        }
        short[] active = new short[activeSamples.size()];
        for (int index = 0; index < active.length; ++index) {
            active[index] = activeSamples.get(index);
        }
        double activeMean = activeSum / activeFrames;
        double inactiveMean = inactiveSum / inactiveFrames;
        AudioMetrics activeMetrics = AudioMetrics.measure(active, 8000, 800.0);
        return new MorseMetrics(expected.length, bestLag, bestCorrelation,
                activeMean, inactiveMean,
                activeMean / Math.max(1.0, inactiveMean), activeMetrics);
    }

    void requireRecognizableSos() {
        if (bestLagFrames < 3 || bestLagFrames > 5
                || envelopeCorrelation < 0.60
                || activeInactiveRatio < 3.30
                || activeAudio.rms < 1000.0
                || activeAudio.coherentAmplitude < activeAudio.rms * 0.12
                || activeAudio.dominantFrequency < 700.0
                || activeAudio.dominantFrequency > 900.0) {
            throw new IllegalStateException("软件AMBE摩尔斯音频硬门失败："
                    + report());
        }
    }

    void requireRecognizableSosSegment() {
        if (bestLagFrames < 3 || bestLagFrames > 5
                || envelopeCorrelation < 0.60
                || activeInactiveRatio < 3.30
                || activeAudio.rms < 1000.0
                || activeAudio.dominantFrequency < 700.0
                || activeAudio.dominantFrequency > 900.0) {
            throw new IllegalStateException("软件AMBE摩尔斯分段包络硬门失败："
                    + report());
        }
    }

    String report() {
        return String.format(Locale.US,
                "frames=%d\nbest_lag_frames=%d\nenvelope_correlation=%.9f\n"
                + "active_rms_mean=%.3f\ninactive_rms_mean=%.3f\n"
                + "active_inactive_ratio=%.6f\n[按键音频]\n%s",
                frames, bestLagFrames, envelopeCorrelation, activeRmsMean,
                inactiveRmsMean, activeInactiveRatio, activeAudio.report());
    }

    private static double[] frameRms(short[] pcm) {
        double[] result = new double[pcm.length / SAMPLES_PER_FRAME];
        for (int frame = 0; frame < result.length; ++frame) {
            double energy = 0.0;
            int start = frame * SAMPLES_PER_FRAME;
            for (int index = 0; index < SAMPLES_PER_FRAME; ++index) {
                double sample = pcm[start + index];
                energy += sample * sample;
            }
            result[frame] = Math.sqrt(energy / SAMPLES_PER_FRAME);
        }
        return result;
    }

    private static double correlation(double[] expected, double[] actual,
            int lag) {
        int count = expected.length - lag;
        double expectedMean = 0.0;
        double actualMean = 0.0;
        for (int index = 0; index < count; ++index) {
            expectedMean += expected[index];
            actualMean += actual[index + lag];
        }
        expectedMean /= count;
        actualMean /= count;
        double product = 0.0;
        double expectedEnergy = 0.0;
        double actualEnergy = 0.0;
        for (int index = 0; index < count; ++index) {
            double left = expected[index] - expectedMean;
            double right = actual[index + lag] - actualMean;
            product += left * right;
            expectedEnergy += left * left;
            actualEnergy += right * right;
        }
        if (expectedEnergy == 0.0 || actualEnergy == 0.0) {
            return -1.0;
        }
        return product / Math.sqrt(expectedEnergy * actualEnergy);
    }
}
