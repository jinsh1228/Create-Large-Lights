package io.github.jinsh1228.largelights;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.haruyq.createmediasystems.SableCompat;

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
    /** Ticks between looks at whether the beam changed (spread over floodlights by position). */
    static final int CHECK = 10;
    /** Ticks after which the faces are traced again even with the beam unchanged: blocks may have moved. */
    static final int REFRESH = 200;
    /** The most beam samples across, either way. */
    static final int MAX_GRID = 41;

    private RealLight() {
    }

    /** The light level of a beam at {@code strength} (0 to 1): off at 0, else at least 1. */
    public static int lightLevel(float strength) {
        return strength <= 0.001f ? 0 : Mth.clamp(Math.round(strength * 15), 1, 15);
    }

    /** Beam samples across, so that where it falls they're about {@code spacing} apart over {@code radius}. */
    static int grid(double radius, int spacing) {
        return Mth.clamp(Mth.ceil(2 * radius / spacing) + 1, 1, MAX_GRID);
    }

    /** The {@code grid}×{@code grid} samples of the unit disk (u, v), the middle first. */
    static List<double[]> disk(int grid) {
        List<double[]> samples = new ArrayList<>();
        samples.add(new double[]{0, 0});
        if (grid <= 1) {
            return samples;
        }
        for (int i = 0; i < grid; i++) {
            for (int j = 0; j < grid; j++) {
                double u = -1 + 2.0 * i / (grid - 1);
                double v = -1 + 2.0 * j / (grid - 1);
                if (u * u + v * v <= 1.0001 && (Math.abs(u) > 1e-9 || Math.abs(v) > 1e-9)) {
                    samples.add(new double[]{u, v});
                }
            }
        }
        return samples;
    }

    static void tick(Level level, BlockPos pos, FloodlightBlock.Entity light) {
        if (level.isClientSide || (level.getGameTime() + pos.hashCode()) % CHECK != 0) {
            return;
        }
        FloodlightBlock.Entity.Beam beam = light.beam();
        Vec3 center = Vec3.atCenterOf(pos);
        // On a moving Sable structure the beam sweeps the world: light blocks would trail behind it.
        boolean carried = !SableCompat.toWorld(level, pos, center).equals(center);
        int brightness = FloodlightConfig.REAL_LIGHT.get() && !carried ? lightLevel(beam.strength()) : 0;
        int spacing = FloodlightConfig.SPACING.get();
        // The aim with the console's pan and tilt; a group's motion moves the beam, not the light blocks.
        int signature = Objects.hash(brightness, beam.yaw(), beam.pitch(), beam.spread(), beam.range(), spacing,
                FloodlightConfig.MAX_LIGHTS.get());
        long now = level.getGameTime();
        if (signature == light.lightSignature && now - light.lightUpdated < REFRESH) {
            return;
        }
        light.lightSignature = signature;
        light.lightUpdated = now;
        Long2LongOpenHashMap wanted = brightness == 0 ? new Long2LongOpenHashMap()
                : footprint(level, pos, light, beam, spacing, FloodlightConfig.MAX_LIGHTS.get());
        if (apply(level, light.lights, wanted, brightness)) {
            light.changed();
        }
    }

    /** Where the beam wants light blocks: air just off the faces it falls on, one in each spacing-sized cell. */
    private static Long2LongOpenHashMap footprint(Level level, BlockPos pos, FloodlightBlock.Entity light,
                                                  FloodlightBlock.Entity.Beam beam, int spacing, int max) {
        var head = Aim.head(beam.yaw(), beam.pitch());
        Vec3 forward = Aim.direction(beam.yaw(), beam.pitch());
        org.joml.Vector3f r = head.transformDirection(new org.joml.Vector3f(1, 0, 0)).normalize();
        org.joml.Vector3f u = head.transformDirection(new org.joml.Vector3f(0, 1, 0)).normalize();
        Vec3 right = new Vec3(r.x, r.y, r.z);
        Vec3 up = new Vec3(u.x, u.y, u.z);
        double tangent = Math.tan(Math.toRadians(beam.spread()) / 2);
        // Just outside the floodlight's own block, whichever way it points.
        Vec3 from = Vec3.atCenterOf(pos).add(forward);
        double range = beam.range();
        BlockHitResult middle = clip(level, from, from.add(forward.scale(range)));
        double distance = middle.getType() == HitResult.Type.BLOCK ? middle.getLocation().distanceTo(from) : range;
        double radius = Math.min(distance * tangent, light.kind().radius);
        // cell → position: the first sample to land in a cell has it.
        Long2LongOpenHashMap wanted = new Long2LongOpenHashMap();
        for (double[] sample : disk(grid(radius, spacing))) {
            if (wanted.size() >= max) {
                break;
            }
            Vec3 aim = forward.add(right.scale(sample[0] * tangent)).add(up.scale(sample[1] * tangent)).normalize();
            BlockHitResult hit = sample[0] == 0 && sample[1] == 0 ? middle : clip(level, from, from.add(aim.scale(range)));
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos at = hit.getBlockPos().relative(hit.getDirection());
            if (at.equals(pos) || !level.isLoaded(at)) {
                continue;
            }
            long cell = BlockPos.asLong(Math.floorDiv(at.getX(), spacing), Math.floorDiv(at.getY(), spacing),
                    Math.floorDiv(at.getZ(), spacing));
            if (!wanted.containsKey(cell) && placeable(level.getBlockState(at), light.lights.contains(at.asLong()))) {
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

    /** Takes away every light block {@code light} placed, as it is broken. */
    static void clear(Level level, FloodlightBlock.Entity light) {
        for (long packed : light.lights) {
            BlockPos at = BlockPos.of(packed);
            if (level.isLoaded(at)) {
                remove(level, at);
            }
        }
        light.lights.clear();
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
