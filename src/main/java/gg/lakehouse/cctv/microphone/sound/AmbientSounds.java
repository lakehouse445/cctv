package gg.lakehouse.cctv.microphone.sound;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import gg.lakehouse.cctv.microphone.MicrophoneRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sounds the server never emits because the client invents them locally:
 * rain patter, crackling fire and campfires, burning furnaces, popping
 * lava. Each listening microphone gets the client's treatment - nearby
 * emitter blocks found by a slow scan, rolled every tick with roughly the
 * client's animateTick odds, and rain sampled off the heightmap around the
 * mic while the weather runs.
 */
@Mod.EventBusSubscriber(modid = CCTV.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AmbientSounds {
    /** How often the block scan around each microphone refreshes, in ticks. */
    private static final int SCAN_INTERVAL = 100;
    private static final int SCAN_RANGE = 8;
    private static final int MAX_EMITTERS = 64;

    private enum Kind {
        FIRE, CAMPFIRE, FURNACE, BLAST_FURNACE, SMOKER, LAVA
    }

    private record Emitter(BlockPos pos, Kind kind) {
    }

    private static final class Scan {
        /** Not MIN_VALUE: gameTime minus it must not overflow on the first check. */
        long refreshedAt = -SCAN_INTERVAL;
        final List<Emitter> emitters = new ArrayList<>();
    }

    /** Server thread only; entries fall away with their microphones. */
    private static final Map<MicrophoneBlockEntity, Scan> SCANS = new WeakHashMap<>();

    private AmbientSounds() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        var microphones = MicrophoneRegistry.listening(level);
        if (microphones.isEmpty()) return;
        var random = level.getRandom();
        for (var microphone : microphones) {
            tickRain(level, microphone, random);
            tickEmitters(level, microphone, random);
        }
    }

    private static void tickRain(ServerLevel level, MicrophoneBlockEntity microphone, RandomSource random) {
        if (!level.isRaining() || random.nextInt(5) != 0) return;
        var pos = microphone.getBlockPos();
        int x = pos.getX() + random.nextInt(21) - 10;
        int z = pos.getZ() + random.nextInt(21) - 10;
        var surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, pos.getY(), z));
        if (!level.isRainingAt(surface)) return;
        SoundCapture.capture(level, x + 0.5, surface.getY() + 0.5, z + 0.5,
            SoundEvents.WEATHER_RAIN.getLocation(), 0.2F, 1);
    }

    private static void tickEmitters(ServerLevel level, MicrophoneBlockEntity microphone, RandomSource random) {
        var scan = SCANS.computeIfAbsent(microphone, key -> new Scan());
        long time = level.getGameTime();
        if (time - scan.refreshedAt >= SCAN_INTERVAL) {
            scan.refreshedAt = time;
            scan.emitters.clear();
            var center = microphone.getBlockPos();
            for (var pos : BlockPos.betweenClosed(
                center.offset(-SCAN_RANGE, -SCAN_RANGE, -SCAN_RANGE),
                center.offset(SCAN_RANGE, SCAN_RANGE, SCAN_RANGE))) {
                var kind = classify(level.getBlockState(pos));
                if (kind != null) {
                    scan.emitters.add(new Emitter(pos.immutable(), kind));
                    if (scan.emitters.size() >= MAX_EMITTERS) break;
                }
            }
        }
        for (var emitter : scan.emitters) {
            var pos = emitter.pos();
            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;
            switch (emitter.kind()) {
                case FIRE -> {
                    if (random.nextInt(40) == 0) {
                        SoundCapture.capture(level, x, y, z, SoundEvents.FIRE_AMBIENT.getLocation(),
                            1 + random.nextFloat(), random.nextFloat() * 0.7F + 0.3F);
                    }
                }
                case CAMPFIRE -> {
                    if (random.nextInt(30) == 0) {
                        SoundCapture.capture(level, x, y, z, SoundEvents.CAMPFIRE_CRACKLE.getLocation(),
                            0.5F + random.nextFloat(), random.nextFloat() * 0.7F + 0.6F);
                    }
                }
                case FURNACE -> {
                    if (random.nextInt(40) == 0) {
                        SoundCapture.capture(level, x, y, z, SoundEvents.FURNACE_FIRE_CRACKLE.getLocation(), 1, 1);
                    }
                }
                case BLAST_FURNACE -> {
                    if (random.nextInt(40) == 0) {
                        SoundCapture.capture(level, x, y, z, SoundEvents.BLASTFURNACE_FIRE_CRACKLE.getLocation(), 1, 1);
                    }
                }
                case SMOKER -> {
                    if (random.nextInt(40) == 0) {
                        SoundCapture.capture(level, x, y, z, SoundEvents.SMOKER_SMOKE.getLocation(), 1, 1);
                    }
                }
                case LAVA -> {
                    if (random.nextInt(100) == 0) {
                        SoundCapture.capture(level, x, y, z, SoundEvents.LAVA_POP.getLocation(),
                            0.2F + random.nextFloat() * 0.2F, 0.9F + random.nextFloat() * 0.15F);
                    } else if (random.nextInt(200) == 0) {
                        SoundCapture.capture(level, x, y, z, SoundEvents.LAVA_AMBIENT.getLocation(),
                            0.2F + random.nextFloat() * 0.2F, 0.9F + random.nextFloat() * 0.15F);
                    }
                }
            }
        }
    }

    @Nullable
    private static Kind classify(BlockState state) {
        if (state.getBlock() instanceof BaseFireBlock) return Kind.FIRE;
        if (state.getBlock() instanceof CampfireBlock
            && state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT)) {
            return Kind.CAMPFIRE;
        }
        if (state.getBlock() instanceof AbstractFurnaceBlock
            && state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT)) {
            if (state.getBlock() instanceof BlastFurnaceBlock) return Kind.BLAST_FURNACE;
            if (state.getBlock() instanceof SmokerBlock) return Kind.SMOKER;
            return Kind.FURNACE;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) return Kind.LAVA;
        return null;
    }
}
