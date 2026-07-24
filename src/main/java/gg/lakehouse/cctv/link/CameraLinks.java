package gg.lakehouse.cctv.link;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.ForgeComputerCraftAPI;
import dan200.computercraft.api.network.wired.WiredElement;
import dan200.computercraft.api.network.wired.WiredNode;
import gg.lakehouse.cctv.camera.CameraBlockEntity;
import gg.lakehouse.cctv.network.ClientboundCameraLinksPacket;
import gg.lakehouse.cctv.network.PacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Device links: a camera or microphone joined to a wired modem without
 * cables, carried by an unbroken run of solid blocks between them. The
 * device joins the modem's wired network as a real peripheral through its
 * own invisible network node. Each modem holds at most {@link #MODEM_LIMIT}
 * linked devices — a digital limit only; physical peripherals are
 * unaffected. When the solid path breaks, the link reroutes; after
 * {@link #MAX_FAILS} consecutive failed reroutes the device drops off the
 * network.
 */
public final class CameraLinks extends SavedData {
    public static final int MODEM_LIMIT = 6;
    private static final int MAX_FAILS = 5;
    private static final int MAX_PATH_VISITS = 4096;

    public static final class Link {
        public BlockPos modem;
        /** The linked device: a camera or a microphone. */
        public BlockPos camera;
        public List<BlockPos> path;
        public int fails;
        /** Consecutive fast-tick checks with no path; meters fail strikes. */
        int brokenChecks;

        Link(BlockPos modem, BlockPos camera, List<BlockPos> path) {
            this.modem = modem;
            this.camera = camera;
            this.path = path;
        }
    }

    private final List<Link> links = new ArrayList<>();
    /** Live network attachments by camera position; rebuilt as chunks load. */
    private final Map<Long, WiredNode> nodes = new HashMap<>();

    public static CameraLinks get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CameraLinks::load, CameraLinks::new, "cctv_camera_links");
    }

    public List<Link> links() {
        return links;
    }

    // === Linking ===

    /** The linkable peripheral at a position — a camera's or a microphone's — or null. */
    @Nullable
    public static dan200.computercraft.api.peripheral.IPeripheral linkable(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        if (entity instanceof CameraBlockEntity camera) return camera.peripheral();
        if (entity instanceof gg.lakehouse.cctv.microphone.MicrophoneBlockEntity microphone) {
            return microphone.peripheral();
        }
        return null;
    }

    /** Creates or moves a link; returns an error message or null on success. */
    @Nullable
    public String link(ServerLevel level, BlockPos device, BlockPos modem) {
        if (linkable(level, device) == null) return "That is not a camera or microphone";
        if (wiredElement(level, modem) == null) return "That is not a wired modem";
        long count = links.stream().filter(link -> link.modem.equals(modem) && !link.camera.equals(device)).count();
        if (count >= MODEM_LIMIT) return "That modem already holds " + MODEM_LIMIT + " devices";
        var path = findPath(level, device, modem);
        if (path == null) return "No unbroken run of solid blocks connects them";

        unlinkDevice(level, device);
        links.add(new Link(modem.immutable(), device.immutable(), path));
        setDirty();
        sync(level);
        return null;
    }

    public boolean unlinkDevice(ServerLevel level, BlockPos device) {
        boolean removed = links.removeIf(link -> {
            if (!link.camera.equals(device)) return false;
            detach(link);
            return true;
        });
        if (removed) {
            setDirty();
            sync(level);
        }
        return removed;
    }

    public boolean unlinkModem(ServerLevel level, BlockPos modem) {
        boolean removed = links.removeIf(link -> {
            if (!link.modem.equals(modem)) return false;
            detach(link);
            return true;
        });
        if (removed) {
            setDirty();
            sync(level);
        }
        return removed;
    }

    // === Path search: BFS through solid blocks ===

    @Nullable
    private static List<BlockPos> findPath(ServerLevel level, BlockPos from, BlockPos to) {
        var visited = new HashSet<Long>();
        var cameFrom = new HashMap<Long, Long>();
        var queue = new ArrayDeque<BlockPos>();
        for (var direction : Direction.values()) {
            var start = from.relative(direction);
            if (solid(level, start) && visited.add(start.asLong())) queue.add(start);
        }
        while (!queue.isEmpty() && visited.size() < MAX_PATH_VISITS) {
            var at = queue.poll();
            if (at.distManhattan(to) == 1) {
                var path = new ArrayList<BlockPos>();
                var walk = at.asLong();
                while (true) {
                    path.add(BlockPos.of(walk));
                    var previous = cameFrom.get(walk);
                    if (previous == null) break;
                    walk = previous;
                }
                return path;
            }
            for (var direction : Direction.values()) {
                var next = at.relative(direction);
                if (!solid(level, next) || !visited.add(next.asLong())) continue;
                cameFrom.put(next.asLong(), at.asLong());
                queue.add(next);
            }
        }
        return null;
    }

    /** The block's wired network element on any side, or null. */
    @Nullable
    public static WiredElement wiredElement(ServerLevel level, BlockPos pos) {
        for (var direction : Direction.values()) {
            var element = ForgeComputerCraftAPI.getWiredElementAt(level, pos, direction).resolve().orElse(null);
            if (element != null) return element;
        }
        return null;
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).isSolidRender(level, pos);
    }

    private static boolean pathValid(ServerLevel level, List<BlockPos> path) {
        for (var pos : path) {
            if (!solid(level, pos)) return false;
        }
        return true;
    }

    // === Maintenance ===

    public void tick(ServerLevel level) {
        boolean changed = false;
        var iterator = links.iterator();
        while (iterator.hasNext()) {
            var link = iterator.next();
            if (!level.isLoaded(link.camera) || !level.isLoaded(link.modem)) continue;

            var peripheral = linkable(level, link.camera);
            var element = wiredElement(level, link.modem);
            if (peripheral == null || element == null) {
                detach(link);
                iterator.remove();
                changed = true;
                continue;
            }

            if (pathValid(level, link.path)) {
                link.fails = 0;
                link.brokenChecks = 0;
            } else {
                var fresh = findPath(level, link.camera, link.modem);
                if (fresh != null) {
                    link.path = fresh;
                    link.fails = 0;
                    link.brokenChecks = 0;
                    changed = true;
                } else {
                    // Checks run every 5 ticks for instant visual rerouting,
                    // but a fail strike lands only every 8th check, keeping
                    // the original ~10 second grace before disconnecting.
                    link.brokenChecks++;
                    if (link.brokenChecks % 8 == 1) link.fails++;
                    if (link.fails > MAX_FAILS) {
                        detach(link);
                        iterator.remove();
                        changed = true;
                        continue;
                    }
                }
            }

            attach(level, link, peripheral, element);
        }
        if (changed) {
            setDirty();
            sync(level);
        }
    }

    private void attach(ServerLevel level, Link link,
                        dan200.computercraft.api.peripheral.IPeripheral peripheral, WiredElement modemElement) {
        var existing = nodes.get(link.camera.asLong());
        if (existing != null) return;
        var element = new WiredElement() {
            private final WiredNode node = ComputerCraftAPI.createWiredNodeForElement(this);

            @Override
            public Level getLevel() {
                return level;
            }

            @Override
            public Vec3 getPosition() {
                return Vec3.atCenterOf(link.camera);
            }

            @Override
            public String getSenderID() {
                return "cctv_camera_link";
            }

            @Override
            public WiredNode getNode() {
                return node;
            }
        };
        var node = element.getNode();
        modemElement.getNode().getNetwork().connect(modemElement.getNode(), node);
        var name = peripheral.getType() + "_" + Integer.toHexString(link.camera.hashCode());
        node.getNetwork().updatePeripherals(node, Map.of(name, peripheral));
        nodes.put(link.camera.asLong(), node);
    }

    private void detach(Link link) {
        var node = nodes.remove(link.camera.asLong());
        if (node != null) node.getNetwork().remove(node);
    }

    // === Sync to clients (path rendering) ===

    public void sync(ServerLevel level) {
        PacketHandler.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),
            ClientboundCameraLinksPacket.of(links));
    }

    public void syncTo(net.minecraft.server.level.ServerPlayer player) {
        PacketHandler.sendTo(player, ClientboundCameraLinksPacket.of(links));
    }

    // === Persistence ===

    private static CameraLinks load(CompoundTag tag) {
        var data = new CameraLinks();
        for (var entry : tag.getList("Links", Tag.TAG_COMPOUND)) {
            var compound = (CompoundTag) entry;
            var path = new ArrayList<BlockPos>();
            for (long value : compound.getLongArray("Path")) path.add(BlockPos.of(value));
            var link = new Link(BlockPos.of(compound.getLong("Modem")), BlockPos.of(compound.getLong("Camera")), path);
            link.fails = compound.getInt("Fails");
            data.links.add(link);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        var list = new ListTag();
        for (var link : links) {
            var compound = new CompoundTag();
            compound.putLong("Modem", link.modem.asLong());
            compound.putLong("Camera", link.camera.asLong());
            compound.putLongArray("Path", link.path.stream().mapToLong(BlockPos::asLong).toArray());
            compound.putInt("Fails", link.fails);
            list.add(compound);
        }
        tag.put("Links", list);
        return tag;
    }
}
