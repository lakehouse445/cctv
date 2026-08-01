package gg.lakehouse.cctv.integration.voicechat;

/**
 * Averages each group of {@link #DECIMATION} 48 kHz samples down to one
 * 16 kHz sample; the boxcar average doubles as the anti-alias filter.
 * Output stays 16-bit: distance gain applies downstream, and attenuating
 * an already-8-bit signal left far voices ~5 bits of quantization noise.
 * No character EQ on top: DFPWM's slew-limited coder supplies all the lo-fi
 * intercom color on its own. Stateful — keep one per speaking player so
 * partial groups carry across packets.
 */
public class Downsampler {
    /** Every DECIMATION'th averaged sample is kept: 48 kHz in, 16 kHz out. */
    public static final int DECIMATION = 3;

    private int sum;
    private int count;

    /** 16-bit 48 kHz PCM in, 16-bit 16 kHz PCM out. */
    public short[] process(short[] pcm) {
        var out = new short[(count + pcm.length) / DECIMATION];
        int written = 0;
        for (short sample : pcm) {
            sum += sample;
            if (++count < DECIMATION) continue;
            int value = sum / DECIMATION;
            sum = 0;
            count = 0;
            out[written++] = (short) Math.max(-32767, Math.min(32767, value));
        }
        return out;
    }
}
