package io.github.jinsh1228.largelights;


import org.haruyq.createmediasystems.SableCompat;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The light a floodlight casts into the world itself: invisible light blocks (minecraft:light) in the air just off
 * the faces its beam falls on, a few blocks apart, as bright as the beam. Each floodlight keeps the ones it placed
 * (Entity.lights) and takes them away when its beam moves off them, goes out, or the floodlight is broken. It never
 * replaces anything but air or still water, and never takes away a light block it didn't place.
 */
public final class RealLight {
    /** Ticks between looks at whether the beam changed (spread over lights by position). */
    static final int CHECK = 10;
    /** Ticks after which the faces are traced again even with the beam unchanged: blocks may have moved. */
    static final int REFRESH = 200;
    /** The most beam samples across, either way. */
    static final int MAX_GRID = 41;
    /** Each grid's disk samples (u, v pairs), made once. */
    private static final double[][] DISKS = new double[MAX_GRID + 1][];

    private RealLight() {
    }

    /** The light blocks one light placed, and what it last placed them for. */
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
        }
    }

    /**
     * A beam as it lights the world: from {@code from} (outside the light's own block) along {@code forward},
     * {@code tangent} wide, {@code range} long, laid over at most {@code radius} blocks either way across the surface
     * it falls on, at light level {@code brightness}.
     */
    record Cast(Vec3 from, Vec3 forward, Vec3 right, Vec3 up, double tangent, double range, int radius, int brightness) {
        /** Aimed by yaw and pitch (Aim), from just outside the block at {@code pos}. */
        static Cast aimed(BlockPos pos, float yaw, float pitch, float spread, double range, int radius, int brightness) {
            var head = Aim.head(yaw, pitch);
            org.joml.Vector3f f = head.transformDirection(new org.joml.Vector3f(0, 0, 1)).normalize();
            org.joml.Vector3f r = head.transformDirection(new org.joml.Vector3f(1, 0, 0)).normalize();
            org.joml.Vector3f u = head.transformDirection(new org.joml.Vector3f(0, 1, 0)).normalize();
            Vec3 forward = new Vec3(f.x, f.y, f.z);
            return new Cast(Vec3.atCenterOf(pos).add(forward), forward, new Vec3(r.x, r.y, r.z), new Vec3(u.x, u.y, u.z),
                    Math.tan(Math.toRadians(spread) / 2), range, radius, brightness);
        }
    }

    /** The light level of a beam at {@code strength} (0 to 1): off at 0, else at least 1. */
    public static int lightLevel(float strength) {
        return strength <= 0.001f ? 0 : Mth.clamp(Math.round(strength * 15), 1, 15);
    }

    /** Beam samples across, so that where it falls they're about {@code spacing} apart over {@code radius}. */
    static int grid(double radius, int spacing) {
        return Mth.clamp(Mth.ceil(2 * radius / spacing) + 1, 1, MAX_GRID);
    }

    /** The {@code grid}×{@code grid} samples of the unit disk, as (u, v) pairs, the middle first. Shared: not to change. */
    static double[] disk(int grid) {
        grid = Mth.clamp(grid, 1, MAX_GRID);
        double[] disk = DISKS[grid];
        if (disk != null) {
            return disk;
        }
        DoubleArrayList samples = new DoubleArrayList();
        samples.add(0);
        samples.add(0);
        for (int i = 0; grid > 1 && i < grid; i++) {
            for (int j = 0; j < grid; j++) {
                double u = -1 + 2.0 * i / (grid - 1);
                double v = -1 + 2.0 * j / (grid - 1);
                if (u * u + v * v <= 1.0001 && (Math.abs(u) > 1e-9 || Math.abs(v) > 1e-9)) {
                    samples.add(u);
                    samples.add(v);
                }
            }
        }
        disk = samples.toDoubleArray();
        DISKS[grid] = disk;
        return disk;
    }

    /** Whether it is this light's turn to look (every CHECK ticks, spread over lights by position). */
    static boolean due(Level level, BlockPos pos) {
        return !level.isClientSide && Math.floorMod(level.getGameTime() + pos.hashCode(), CHECK) == 0;
    }

    /**
     * Places {@code placed}'s light blocks for {@code cast} (or takes them all away, at brightness 0), if its
     * {@code signature} changed or REFRESH ticks have passed; true if any changed. The cast is only made then.
     */
    static boolean update(Level level, BlockPos pos, Placed placed, int signature, java.util.function.Supplier<Cast> cast) {
        long now = level.getGameTime();
        if (signature == placed.signature && now - placed.updated < REFRESH) {
            return false;
        }
        placed.signature = signature;
        placed.updated = now;
        Cast beam = cast.get();
        int spacing = FloodlightConfig.SPACING.get();
        Long2LongOpenHashMap wanted = beam.brightness() == 0 ? new Long2LongOpenHashMap()
                : footprint(level, pos, placed, beam, spacing, FloodlightConfig.MAX_LIGHTS.get());
        return apply(level, placed.lights, wanted, beam.brightness());
    }

    /** Whether the block at {@code pos} rides a moving Sable structure: its light blocks would trail behind it. */
    static boolean carried(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        return !SableCompat.toWorld(level, pos, center).equals(center);
    }

    static void tick(Level level, BlockPos pos, FloodlightBlock.Entity light) {
        if (!due(level, pos)) {
            return;
        }
        // The quick look at its console; now and then CMS's full one, which follows a console that moved.
        boolean full = Math.floorMod(level.getGameTime() + pos.hashCode(), REFRESH) < CHECK;
        var owner = full ? light.controller() : light.quickController();
        FloodlightBlock.Entity.Beam beam = light.beam(owner);
        int brightness = FloodlightConfig.REAL_LIGHT.get() && !carried(level, pos)
                ? Math.min(lightLevel(beam.strength()), beam.lightLimit()) : 0;
        // The aim with the console's pan and tilt; a group's motion moves the beam, not the light blocks.
        int signature = signature(brightness, beam.yaw(), beam.pitch(), beam.spread(), beam.range());
        if (update(level, pos, light.placed, signature, () -> Cast.aimed(pos, beam.yaw(), beam.pitch(), beam.spread(),
                beam.range(), light.kind().radius, brightness))) {
            light.changed();
        }
    }

    /** What a cast depends on, and the config: when it changes, the light blocks are placed again. */
    static int signature(int brightness, float yaw, float pitch, float spread, float range) {
        int hash = brightness;
        hash = 31 * hash + Float.floatToIntBits(yaw);
        hash = 31 * hash + Float.floatToIntBits(pitch);
        hash = 31 * hash + Float.floatToIntBits(spread);
        hash = 31 * hash + Float.floatToIntBits(range);
        hash = 31 * hash + FloodlightConfig.SPACING.get();
        return 31 * hash + FloodlightConfig.MAX_LIGHTS.get();
    }

    /** Where the beam wants light blocks: air just off the faces it falls on, one in each spacing-sized cell. */
    private static Long2LongOpenHashMap footprint(Level level, BlockPos pos, Placed placed, Cast cast, int spacing,
                                                  int max) {
        Vec3 from = cast.from();
        Vec3 forward = cast.forward();
        double tangent = cast.tangent();
        double range = cast.range();
        BlockHitResult middle = clip(level, from, from.add(forward.scale(range)));
        double distance = middle.getType() == HitResult.Type.BLOCK ? middle.getLocation().distanceTo(from) : range;
        double radius = Math.min(distance * tangent, cast.radius());
        // cell → position: the first sample to land in a cell has it.
        Long2LongOpenHashMap wanted = new Long2LongOpenHashMap();
        double[] disk = disk(grid(radius, spacing));
        for (int i = 0; i < disk.length && wanted.size() < max; i += 2) {
            BlockHitResult hit;
            if (i == 0) {
                hit = middle;
            } else {
                double u = disk[i] * tangent;
                double v = disk[i + 1] * tangent;
                double x = forward.x + cast.right().x * u + cast.up().x * v;
                double y = forward.y + cast.right().y * u + cast.up().y * v;
                double z = forward.z + cast.right().z * u + cast.up().z * v;
                double scale = range / Math.sqrt(x * x + y * y + z * z);
                hit = clip(level, from, from.add(x * scale, y * scale, z * scale));
            }
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos at = hit.getBlockPos().relative(hit.getDirection());
            if (at.equals(pos) || !level.isLoaded(at)) {
                continue;
            }
            long cell = BlockPos.asLong(Math.floorDiv(at.getX(), spacing), Math.floorDiv(at.getY(), spacing),
                    Math.floorDiv(at.getZ(), spacing));
            if (!wanted.containsKey(cell) && placeable(level.getBlockState(at), placed.lights.contains(at.asLong()))) {
                wanted.put(cell, at.asLong());
            }
        }
        return wanted;
    }

    private static BlockHitResult clip(Level level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
    }

    /** Air, still water, or a light block this floodlight placed. */
    static boolean placeable(BlockState state, boolean ours) {
        return state.isAir() || state.is(Blocks.LIGHT) && ours
                || state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    /**
     * Places the {@code wanted} light blocks at {@code brightness} and takes away those placed before that aren't
     * wanted (unless their chunk is unloaded: they're taken later). {@code placed} is kept up to date.
     */
    static boolean apply(Level level, LongSet placed, Long2LongOpenHashMap wanted, int brightness) {
        LongSet keep = new LongOpenHashSet(wanted.values());
        boolean changed = false;
        for (LongIterator it = placed.iterator(); it.hasNext(); ) {
            long packed = it.nextLong();
            if (keep.contains(packed)) {
                continue;
            }
            BlockPos at = BlockPos.of(packed);
            if (!level.isLoaded(at)) {
                continue;
            }
            remove(level, at);
            it.remove();
            changed = true;
        }
        for (long packed : keep) {
            BlockPos at = BlockPos.of(packed);
            BlockState state = level.getBlockState(at);
            boolean ours = placed.contains(packed);
            if (!placeable(state, ours)) {
                // Built over since: no longer ours to look after.
                changed |= placed.remove(packed);
                continue;
            }
            boolean water = state.is(Blocks.LIGHT) ? state.getValue(BlockStateProperties.WATERLOGGED)
                    : state.is(Blocks.WATER);
            BlockState lit = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, brightness)
                    .setValue(BlockStateProperties.WATERLOGGED, water);
            if (state != lit) {
                level.setBlock(at, lit, Block.UPDATE_CLIENTS);
            }
            changed |= placed.add(packed);
        }
        return changed;
    }

    /** Takes away every light block {@code placed} holds, as its light is broken. */
    static void clear(Level level, Placed placed) {
        for (long packed : placed.lights) {
            BlockPos at = BlockPos.of(packed);
            if (level.isLoaded(at)) {
                remove(level, at);
            }
        }
        placed.lights.clear();
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
