package gg.lakehouse.cctv.tape;

/**
 * Whether the anvil's current rename of a tape exceeds the label cap.
 * Written by the anvil update hook, read by the client to draw "Too long!".
 */
public final class TapeLabelState {
    public static volatile boolean anvilNameTooLong;

    private TapeLabelState() {
    }
}
