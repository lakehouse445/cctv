package gg.lakehouse.cctv.microphone;

/**
 * DFPWM1a encoder, the mirror of ComputerCraft's speaker codec: one bit per
 * sample tracking a charging predictor, unpacked Lua-side by
 * cc.audio.dfpwm.make_decoder. Stateful — keep one per audio stream so the
 * predictor stays continuous across chunks.
 */
public class DfpwmEncoder {
    private static final int PREC = 10;

    private int charge;
    private int strength;
    private boolean previousBit;

    /** 8-bit signed PCM in, one bit per sample out, LSB first; length must be a multiple of 8. */
    public byte[] encode(byte[] pcm) {
        var out = new byte[pcm.length / 8];
        for (int i = 0; i < out.length; i++) {
            int packed = 0;
            for (int j = 0; j < 8; j++) {
                int level = pcm[i * 8 + j];
                boolean bit = level > charge || (level == charge && charge == 127);
                int target = bit ? 127 : -128;
                int nextCharge = charge + ((strength * (target - charge) + (1 << (PREC - 1))) >> PREC);
                if (nextCharge == charge && nextCharge != target) nextCharge += bit ? 1 : -1;
                int strengthTarget = bit == previousBit ? (1 << PREC) - 1 : 0;
                int nextStrength = strength;
                if (strength != strengthTarget) nextStrength += bit == previousBit ? 1 : -1;
                if (nextStrength < 1 << (PREC - 7)) nextStrength = 1 << (PREC - 7);
                charge = nextCharge;
                strength = nextStrength;
                previousBit = bit;
                packed = (packed >> 1) | (bit ? 128 : 0);
            }
            out[i] = (byte) packed;
        }
        return out;
    }
}
