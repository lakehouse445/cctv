package gg.lakehouse.cctv.integration.voicechat;

/**
 * Averages each group of {@link #DECIMATION} 48 kHz samples down to one
 * 16 kHz 8-bit sample; the boxcar average doubles as the anti-alias filter.
 * No character EQ on top: DFPWM's slew-limited coder supplies all the lo-fi
 * intercom color on its own. Stateful — keep one per speaking player so
 * partial groups carry across packets.
 */
public class Downsampler {
    /** Every DECIMATION'th averaged sample is kept: 48 kHz in, 16 kHz out. */
    public static final int DECIMATION = 3;

    private int sum;
    private int count;

    /** 16-bit 48 kHz PCM in, 8-bit signed 16 kHz PCM out. */
    public byte[] process(short[] pcm) {
        var out = new byte[(count + pcm.length) / DECIMATION];
        int written = 0;
        for (short sample : pcm) {
            sum += sample;
            if (++count < DECIMATION) continue;
            int value = sum / (DECIMATION * 256);
            sum = 0;
            count = 0;
            out[written++] = (byte) Math.max(-127, Math.min(127, value));
        }
        return out;
    }
}
