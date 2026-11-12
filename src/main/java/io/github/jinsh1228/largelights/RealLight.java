package io.github.jinsh1228.largelights;

import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import org.haruyq.createmediasystems.api.sublevel.SubLevels;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Registers illumination data and migrates the invisible light blocks left by earlier versions. */
@EventBusSubscriber(modid = CreateLargeLights.MOD_ID)
public final class RealLight {
    static final int CHECK = 10;
    static final int REFRESH = 200;

    private RealLight() {
    }

    /** Legacy ownership is retained only until its old light blocks have been removed safely. */
    public static final class Placed {
        final LongSet lights = new LongOpenHashSet();
        int signature;
        long updated = Long.MIN_VALUE;

        public int size() {
            return lights.size();
        }

        public long[] toArray() {
            return lights.toLongArray();
        }

        void load(long[] saved) {
            lights.clear();
            for (long packed : saved) {
                lights.add(packed);
            }
            updated = Long.MIN_VALUE;
        }

        boolean needsUpdate(int signature, long now) {
            return updated == Long.MIN_VALUE || signature != this.signature || now < updated || now - updated >= REFRESH;
        }
    }

    public static int lightLevel(float strength) {
        return strength <= .001f ? 0 : Mth.clamp(Math.round(strength * 15), 1, 15);
    }

    static boolean due(Level level, BlockPos pos) {
        return Math.floorMod(level.getGameTime() + pos.hashCode(), CHECK) == 0;
    }

    static boolean update(Level level, BlockPos pos, Placed placed, int signature, Supplier<LightField.Beam> beam) {
        boolean migrated = !placed.lights.isEmpty();
        if (migrated) {
            if (level.isClientSide) {
                placed.lights.clear();
            } else {
                clear(level, placed);
            }
        }
        long now = level.getGameTime();
        if (placed.needsUpdate(signature, now)) {
            placed.signature = signature;
            placed.updated = now;
            LightField.update(level, pos, beam.get());
        }
        return migrated && !level.isClientSide;
    }

    static boolean carried(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        return !SubLevels.toWorld(level, pos, center).equals(center);
    }

    static void tick(Level level, BlockPos pos, FloodlightBlock.Entity light) {
        if (due(level, pos)) {
            refresh(level, pos, light);
        }
    }

    static void refresh(Level level, BlockPos pos, FloodlightBlock.Entity light) {
        var owner = light.controller();
        if (owner != null && !level.isClientSide) {
            ConsoleTimers.check(owner, level);
        }
        FloodlightBlock.Entity.Beam beam = light.beam(owner);
        float strength = FloodlightConfig.REAL_LIGHT.get() && !carried(level, pos) ? beam.strength() : 0;
        int signature = signature(strength, beam.lightLimit(), beam.yaw(), beam.pitch(), beam.spread(), beam.range());
        if (update(level, pos, light.placed, signature, () -> aimed(pos, beam.yaw(), beam.pitch(), beam.spread(),
                beam.range(), light.kind(), strength, beam.lightLimit()))) {
            light.changed();
        }
    }

    static LightField.Beam aimed(BlockPos pos, float yaw, float pitch, float spread, double range,
                                 FloodlightKind kind, float strength, int limit) {
        Vec3 forward = Aim.direction(yaw, pitch);
        return new LightField.Beam(Vec3.atCenterOf(pos).add(forward.scale(kind.lensZ + .01)), forward,
                Math.tan(Math.toRadians(spread) / 2), range, strength, limit, kind.radius);
    }

    static int signature(float strength, int limit, float yaw, float pitch, float spread, float range) {
        int hash = Float.floatToIntBits(strength);
        hash = 31 * hash + limit;
        hash = 31 * hash + Float.floatToIntBits(yaw);
        hash = 31 * hash + Float.floatToIntBits(pitch);
        hash = 31 * hash + Float.floatToIntBits(spread);
        return 31 * hash + Float.floatToIntBits(range);
    }

    static void clear(Level level, Placed placed) {
        clear(placed, packed -> level.isLoaded(BlockPos.of(packed)), packed -> remove(level, BlockPos.of(packed)),
                packed -> {
                    if (level instanceof ServerLevel server) {
                        server.getDataStorage().computeIfAbsent(Pending.FACTORY, Pending.NAME).add(packed);
                    }
                });
    }

    static void clear(Placed placed, LongPredicate loaded, LongConsumer remove, LongConsumer defer) {
        for (long packed : placed.lights) {
            if (loaded.test(packed)) {
                remove.accept(packed);
            } else {
                defer.accept(packed);
            }
        }
        placed.lights.clear();
    }

    static final class Pending extends SavedData {
        static final String NAME = "create_large_lights_light_cleanup";
        static final Factory<Pending> FACTORY = new Factory<>(Pending::new, Pending::load);
        final LongSet lights = new LongOpenHashSet();

        void add(long packed) {
            if (lights.add(packed)) {
                setDirty();
            }
        }

        void removeLoaded(LongPredicate loaded, LongConsumer remove) {
            for (LongIterator it = lights.iterator(); it.hasNext(); ) {
                long packed = it.nextLong();
                if (loaded.test(packed)) {
                    remove.accept(packed);
                    it.remove();
                    setDirty();
                }
            }
        }

        static Pending load(CompoundTag tag, HolderLookup.Provider registries) {
            Pending pending = new Pending();
            for (long packed : tag.getLongArray("LightBlocks")) {
                pending.lights.add(packed);
            }
            return pending;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("LightBlocks", lights.toLongArray());
            return tag;
        }
    }

    @SubscribeEvent
    public static void cleanup(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && Math.floorMod(level.getGameTime(), CHECK) == 0) {
            Pending pending = level.getDataStorage().get(Pending.FACTORY, Pending.NAME);
            if (pending != null) {
                pending.removeLoaded(packed -> level.isLoaded(BlockPos.of(packed)),
                        packed -> remove(level, BlockPos.of(packed)));
            }
        }
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level) {
            // The chunk need not be FULL yet; this only touches our cached data.
            LightField.invalidateChunk(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            LightField.unloadChunk(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            LightField.clear(level);
        }
    }

    private static void remove(Level level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if (state.is(Blocks.LIGHT)) {
            level.setBlock(at, state.getValue(BlockStateProperties.WATERLOGGED)
                    ? Fluids.WATER.defaultFluidState().createLegacyBlock() : Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
        }
    }
}
