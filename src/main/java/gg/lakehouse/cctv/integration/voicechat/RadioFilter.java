package gg.lakehouse.cctv.integration.voicechat;

import java.util.Random;

/**
 * The intercom sound from spec section 7: telephone-band EQ (~300-3400 Hz),
 * a soft-clip crunch, and faint hiss. Stateful — keep one per speaking player
 * so the filters are continuous across packets. Strength config comes later.
 */
public class RadioFilter {
    private static final double HIGH_PASS = 0.961; // one-pole @ ~300 Hz, 48 kHz
    private static final double LOW_PASS = 0.31;   // one-pole @ ~3.4 kHz, 48 kHz

    private final Random random = new Random();
    private double highPassPrevIn;
    private double highPassPrevOut;
    private double lowPassPrev;

    /** 16-bit PCM in, radio-flavoured 8-bit signed PCM out. */
    public byte[] process(short[] pcm) {
        var out = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            double x = pcm[i] / 32768.0;
            double highPassed = HIGH_PASS * (highPassPrevOut + x - highPassPrevIn);
            highPassPrevIn = x;
            highPassPrevOut = highPassed;
            lowPassPrev += LOW_PASS * (highPassed - lowPassPrev);
            double y = lowPassPrev * 1.6;                    // make-up gain
            y += (random.nextDouble() - 0.5) * 0.006;        // faint hiss
            y = Math.tanh(y * 1.8);                          // soft clip
            int sample = (int) Math.round(y * 127);
            out[i] = (byte) Math.max(-127, Math.min(127, sample));
        }
        return out;
    }
}
