package gg.lakehouse.cctv.camera;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua-facing camera. getFrame returns 16-color frames as blit background
 * strings; camera_motion events fire while any computer is attached:
 * event, side, changedFraction.
 */
public class CameraPeripheral implements IPeripheral {
    private final CameraBlockEntity blockEntity;
    private final Set<IComputerAccess> computers = ConcurrentHashMap.newKeySet();

    public CameraPeripheral(CameraBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public String getType() {
        return "camera";
    }

    @Override
    public Object getTarget() {
        return blockEntity;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other || (other instanceof CameraPeripheral peripheral && peripheral.blockEntity == blockEntity);
    }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    boolean hasComputers() {
        return !computers.isEmpty();
    }

    void queueMotionEvent(double fraction) {
        for (var computer : computers) {
            computer.queueEvent("camera_motion", computer.getAttachmentName(), fraction);
        }
    }

    /** Fired whenever the set of players in the picture changes: event, side, {names}. */
    void queuePlayerEvent(List<String> names) {
        for (var computer : computers) {
            computer.queueEvent("camera_player", computer.getAttachmentName(), names);
        }
    }

    /**
     * The current picture, sized in terminal cells. text/fg/bg are blit-ready
     * rows (2x3 pixels per cell via the drawing characters); palette lists the
     * frame's 16 colors as 0xRRGGBB, to apply with setPaletteColour before
     * drawing: for i = 1, 16 do mon.setPaletteColour(2 ^ (i - 1), f.palette[i]) end.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getFrame(Optional<Integer> width, Optional<Integer> height) throws LuaException {
        int w = width.orElse(CameraBlockEntity.DEFAULT_WIDTH);
        int h = height.orElse(CameraBlockEntity.DEFAULT_HEIGHT);
        if (w < 1 || w > CameraBlockEntity.MAX_WIDTH) {
            throw new LuaException("Width must be 1-" + CameraBlockEntity.MAX_WIDTH);
        }
        if (h < 1 || h > CameraBlockEntity.MAX_HEIGHT) {
            throw new LuaException("Height must be 1-" + CameraBlockEntity.MAX_HEIGHT);
        }
        var frame = blockEntity.renderFrame(w, h);
        if (frame == null) throw new LuaException("Camera is not loaded");
        var palette = new ArrayList<Integer>(16);
        for (var color : frame.palette()) palette.add(color);
        var result = new HashMap<String, Object>();
        result.put("width", w);
        result.put("height", h);
        result.put("text", List.of(frame.text()));
        result.put("fg", List.of(frame.fg()));
        result.put("bg", List.of(frame.bg()));
        result.put("palette", palette);
        return result;
    }

    @LuaFunction(mainThread = true)
    public final double getYaw() {
        return blockEntity.getYaw();
    }

    @LuaFunction(mainThread = true)
    public final void setYaw(double degrees) throws LuaException {
        requireUnlocked();
        blockEntity.setYaw((float) degrees);
    }

    @LuaFunction(mainThread = true)
    public final double getPitch() {
        return blockEntity.getPitch();
    }

    @LuaFunction(mainThread = true)
    public final void setPitch(double degrees) throws LuaException {
        requireUnlocked();
        blockEntity.setPitch((float) degrees);
    }

    @LuaFunction(mainThread = true)
    public final double getZoom() {
        return blockEntity.getZoom();
    }

    @LuaFunction(mainThread = true)
    public final void setZoom(double level) throws LuaException {
        requireUnlocked();
        blockEntity.setZoom(level);
    }

    @LuaFunction(mainThread = true)
    public final boolean isLocked() {
        return blockEntity.isLocked();
    }

    @LuaFunction(mainThread = true)
    public final void setLocked(boolean locked) {
        blockEntity.setLocked(locked);
    }

    @LuaFunction(mainThread = true)
    public final String getColorMode() {
        return blockEntity.getColorMode().getName();
    }

    /** "bw" (default), "sepia", or "color". */
    @LuaFunction(mainThread = true)
    public final void setColorMode(String mode) throws LuaException {
        requireUnlocked();
        var parsed = ColorMode.byName(mode);
        if (parsed == null) throw new LuaException("Color mode must be bw, sepia or color");
        blockEntity.setColorMode(parsed);
    }

    @LuaFunction(mainThread = true)
    public final double getMotionThreshold() {
        return blockEntity.getMotionThreshold();
    }

    @LuaFunction(mainThread = true)
    public final void setMotionThreshold(double fraction) {
        blockEntity.setMotionThreshold(fraction);
    }

    @LuaFunction
    public final double getRange() {
        return CameraRaycaster.MAX_DISTANCE;
    }

    private void requireUnlocked() throws LuaException {
        if (blockEntity.isLocked()) throw new LuaException("Camera is locked");
    }
}
