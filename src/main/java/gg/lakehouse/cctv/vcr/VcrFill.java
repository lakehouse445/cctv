package gg.lakehouse.cctv.vcr;

import net.minecraft.util.StringRepresentable;

public enum VcrFill implements StringRepresentable {
    EMPTY("empty"),
    FILLING("filling"),
    FULL("full");

    private final String name;

    VcrFill(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
