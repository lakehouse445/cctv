package gg.lakehouse.cctv.vcr;

import javax.annotation.Nullable;

public enum RaidMode {
    /** Each recording lands whole on the first tape with room. */
    SPAN,
    /** Frames split round-robin across all tapes; one tape alone plays garbage. */
    STRIPE,
    /** Every tape gets a full copy; any single tape survives alone. */
    MIRROR;

    @Nullable
    public static RaidMode byName(String name) {
        for (var mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) return mode;
        }
        return null;
    }
}
