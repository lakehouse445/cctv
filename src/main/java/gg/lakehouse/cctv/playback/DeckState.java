package gg.lakehouse.cctv.playback;

import net.minecraft.util.StringRepresentable;

public enum DeckState implements StringRepresentable {
    EMPTY("empty"),
    FILLED("filled"),
    PLAYING("playing"),
    REWINDING("rewinding");

    private final String name;

    DeckState(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
