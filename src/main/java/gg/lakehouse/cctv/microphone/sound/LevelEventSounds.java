package gg.lakehouse.cctv.microphone.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Block;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-side port of the client's level-event-to-sound mapping
 * (LevelRenderer.levelEvent): splash potions, block breaks, doors driven by
 * redstone, dispensers, anvils and friends never go out as sound packets -
 * just an integer event id the client turns into audio. The microphones need
 * the same table. Volumes and pitches approximate the client's.
 */
public final class LevelEventSounds {
    private LevelEventSounds() {
    }

    public static void handle(ServerLevel level, int type, BlockPos pos, int data) {
        var random = ThreadLocalRandom.current();
        SoundEvent sound = null;
        float volume = 1;
        float pitch = 1;
        switch (type) {
            case 1000 -> sound = SoundEvents.DISPENSER_DISPENSE;
            case 1001 -> {
                sound = SoundEvents.DISPENSER_FAIL;
                pitch = 1.2F;
            }
            case 1002 -> {
                sound = SoundEvents.DISPENSER_LAUNCH;
                pitch = 1.2F;
            }
            case 1003 -> {
                sound = SoundEvents.ENDER_EYE_LAUNCH;
                pitch = 1.2F;
            }
            case 1004 -> {
                sound = SoundEvents.FIREWORK_ROCKET_SHOOT;
                pitch = 1.2F;
            }
            case 1009 -> {
                sound = SoundEvents.FIRE_EXTINGUISH;
                volume = 0.5F;
                pitch = 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F;
            }
            case 1010 -> sound = null; // jukebox: music discs are streams, deliberately skipped
            case 1011 -> sound = SoundEvents.IRON_DOOR_OPEN;
            case 1005 -> sound = SoundEvents.IRON_DOOR_CLOSE;
            case 1006 -> sound = SoundEvents.WOODEN_DOOR_OPEN;
            case 1012 -> sound = SoundEvents.WOODEN_DOOR_CLOSE;
            case 1007 -> sound = SoundEvents.WOODEN_TRAPDOOR_OPEN;
            case 1013 -> sound = SoundEvents.WOODEN_TRAPDOOR_CLOSE;
            case 1037 -> sound = SoundEvents.IRON_TRAPDOOR_OPEN;
            case 1036 -> sound = SoundEvents.IRON_TRAPDOOR_CLOSE;
            case 1008 -> sound = SoundEvents.FENCE_GATE_OPEN;
            case 1014 -> sound = SoundEvents.FENCE_GATE_CLOSE;
            case 1015 -> {
                sound = SoundEvents.GHAST_WARN;
                volume = 10;
            }
            case 1016 -> {
                sound = SoundEvents.GHAST_SHOOT;
                volume = 10;
            }
            case 1017 -> {
                sound = SoundEvents.ENDER_DRAGON_SHOOT;
                volume = 10;
            }
            case 1018 -> {
                sound = SoundEvents.BLAZE_SHOOT;
                volume = 2;
            }
            case 1019 -> {
                sound = SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR;
                volume = 2;
            }
            case 1020 -> {
                sound = SoundEvents.ZOMBIE_ATTACK_IRON_DOOR;
                volume = 2;
            }
            case 1021 -> {
                sound = SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR;
                volume = 2;
            }
            case 1022 -> {
                sound = SoundEvents.WITHER_BREAK_BLOCK;
                volume = 2;
            }
            case 1023 -> sound = SoundEvents.WITHER_SPAWN;
            case 1024 -> {
                sound = SoundEvents.WITHER_SHOOT;
                volume = 2;
            }
            case 1025 -> {
                sound = SoundEvents.BAT_TAKEOFF;
                volume = 0.05F;
            }
            case 1026 -> {
                sound = SoundEvents.ZOMBIE_INFECT;
                volume = 2;
            }
            case 1027 -> {
                sound = SoundEvents.ZOMBIE_VILLAGER_CONVERTED;
                volume = 2;
            }
            case 1028 -> sound = SoundEvents.ENDER_DRAGON_DEATH;
            case 1029 -> sound = SoundEvents.ANVIL_DESTROY;
            case 1030 -> sound = SoundEvents.ANVIL_USE;
            case 1031 -> {
                sound = SoundEvents.ANVIL_LAND;
                volume = 0.3F;
            }
            case 1032 -> sound = null; // portal travel plays only for the traveling player
            case 1033 -> sound = SoundEvents.CHORUS_FLOWER_GROW;
            case 1034 -> sound = SoundEvents.CHORUS_FLOWER_DEATH;
            case 1035 -> sound = SoundEvents.BREWING_STAND_BREW;
            case 1038 -> sound = SoundEvents.END_PORTAL_SPAWN;
            case 1039 -> sound = SoundEvents.PHANTOM_BITE;
            case 1040 -> {
                sound = SoundEvents.ZOMBIE_CONVERTED_TO_DROWNED;
                volume = 2;
            }
            case 1041 -> {
                sound = SoundEvents.HUSK_CONVERTED_TO_ZOMBIE;
                volume = 2;
            }
            case 1042 -> sound = SoundEvents.GRINDSTONE_USE;
            case 1043 -> sound = SoundEvents.BOOK_PAGE_TURN;
            case 1044 -> sound = SoundEvents.SMITHING_TABLE_USE;
            case 1045 -> sound = SoundEvents.POINTED_DRIPSTONE_LAND;
            case 1046 -> {
                sound = SoundEvents.POINTED_DRIPSTONE_DRIP_LAVA_INTO_CAULDRON;
                volume = 2;
            }
            case 1047 -> {
                sound = SoundEvents.POINTED_DRIPSTONE_DRIP_WATER_INTO_CAULDRON;
                volume = 2;
            }
            case 1048 -> {
                sound = SoundEvents.SKELETON_CONVERTED_TO_STRAY;
                volume = 2;
            }
            case 1500 -> sound = SoundEvents.COMPOSTER_FILL_SUCCESS;
            case 1501 -> {
                sound = SoundEvents.LAVA_EXTINGUISH;
                volume = 0.5F;
                pitch = 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F;
            }
            case 1502 -> {
                sound = SoundEvents.REDSTONE_TORCH_BURNOUT;
                volume = 0.5F;
                pitch = 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F;
            }
            case 1503 -> sound = SoundEvents.END_PORTAL_FRAME_FILL;
            case 2001 -> {
                // Block break: the sound belongs to the broken block's material.
                var state = Block.stateById(data);
                if (!state.isAir()) {
                    var soundType = state.getSoundType();
                    sound = soundType.getBreakSound();
                    volume = (soundType.getVolume() + 1) / 2;
                    pitch = soundType.getPitch() * 0.8F;
                }
            }
            case 2002, 2007 -> {
                sound = SoundEvents.SPLASH_POTION_BREAK;
                pitch = level.getRandom().nextFloat() * 0.1F + 0.9F;
            }
            case 2003 -> sound = SoundEvents.ENDER_EYE_DEATH;
            case 2005 -> sound = SoundEvents.BONE_MEAL_USE;
            case 2006 -> sound = SoundEvents.DRAGON_FIREBALL_EXPLODE;
            case 3000 -> {
                sound = SoundEvents.END_GATEWAY_SPAWN;
                volume = 10;
            }
            case 3001 -> {
                sound = SoundEvents.ENDER_DRAGON_GROWL;
                volume = 64;
                pitch = 0.8F + random.nextFloat() * 0.3F;
            }
            default -> sound = null;
        }
        if (sound == null) return;
        SoundCapture.capture(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            sound.getLocation(), volume, pitch);
    }
}
