package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

class RealLightTest {
    @Test
    void lightSourceCentresStayInsideTheVisibleConeAndRange() {
        RealLight.Cast cast = new RealLight.Cast(new Vec3(0.5, 0.5, 0.5), new Vec3(0, 0, 1),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), 0.2, 10, 24, 15);
        assertTrue(cast.contains(new BlockPos(0, 0, 5)));
        assertFalse(cast.contains(new BlockPos(1, 0, 5))); // outer edge has zero shader exposure
        assertFalse(cast.contains(new BlockPos(2, 0, 5)));
        assertFalse(cast.contains(BlockPos.ZERO));
        assertFalse(cast.contains(new BlockPos(0, 0, -1)));
        assertFalse(cast.contains(new BlockPos(0, 0, 10)));
    }

    @Test
    void roundingASurfaceHitMustNotMoveALightSourceOutsideANarrowBeam() {
        // The beam reaches (.01, .01, .01); the adjacent air block's centre is off its axis.
        for (Vec3 from : new Vec3[]{new Vec3(.01, 10, .01), new Vec3(10, .01, .01), new Vec3(10, 10, .01)}) {
            Vec3 forward = new Vec3(.01, .01, .01).subtract(from).normalize();
            RealLight.Cast cast = new RealLight.Cast(from, forward, Vec3.ZERO, Vec3.ZERO, .001, 20, 24, 15);
            assertFalse(cast.contains(BlockPos.ZERO));
        }
    }

    @Test
    void bothFixtureKindsTraceFromTheirRenderedLens() {
        BlockPos pos = new BlockPos(10, 64, -20);
        for (FloodlightKind kind : FloodlightKind.values()) {
            for (float[] aim : new float[][]{{0, 90}, {35, 15}, {-130, -45}}) {
                RealLight.Cast cast = RealLight.Cast.aimed(pos, aim[0], aim[1], 35, 128, kind, 15);
                var point = Aim.head(aim[0], aim[1]).transformPosition(new org.joml.Vector3f(0, 0, kind.lensZ + .01f));
                Vec3 rendered = Vec3.atLowerCornerOf(pos).add(point.x, point.y, point.z);
                assertTrue(cast.from().distanceToSqr(rendered) < 1e-10);
            }
        }
    }

    @Test
    void lightFollowsStrength() {
        assertEquals(0, RealLight.lightLevel(0));
        // Redstone signal n of 15 lights at level n.
        for (int signal = 1; signal <= 15; signal++) {
            assertEquals(signal, RealLight.lightLevel(signal / 15f));
        }
        // Anything lit is at least level 1.
        assertEquals(1, RealLight.lightLevel(0.01f));
        assertEquals(15, RealLight.lightLevel(2));
    }

    @Test
    void samplesAreAboutASpacingApartWhereTheBeamFalls() {
        // A beam 40 blocks across, a light every 5 blocks: 9 samples across.
        assertEquals(9, RealLight.grid(20, 5));
        // A beam narrower than the spacing lights its middle only.
        assertEquals(2, RealLight.disk(RealLight.grid(0.5, 5)).length);
        assertEquals(RealLight.MAX_GRID, RealLight.grid(1000, 3));
    }

    @Test
    void diskStartsInTheMiddleAndStaysInside() {
        double[] disk = RealLight.disk(9);
        assertEquals(0, disk[0]);
        assertEquals(0, disk[1]);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < disk.length; i += 2) {
            assertTrue(disk[i] * disk[i] + disk[i + 1] * disk[i + 1] <= 1.0001);
            // Each sample once: the middle isn't added twice.
            assertTrue(seen.add(disk[i] + "," + disk[i + 1]));
        }
        assertEquals(2, RealLight.disk(1).length);
        // Made once, then shared.
        assertTrue(RealLight.disk(9) == disk);
    }

    @Test
    void distantFloorSamplesStayInsideTheFixtureRadius() {
        double tangent = Math.tan(Math.toRadians(70) / 2);
        double sampled = RealLight.sampleTangent(tangent, 100, 24);
        assertEquals(24, sampled * 100, 1e-9);
        for (double coordinate : RealLight.disk(RealLight.grid(24, 5))) {
            assertTrue(Math.abs(coordinate * sampled * 100) <= 24 + 1e-9);
        }
        // A nearby floor still uses the full selected beam angle.
        assertEquals(tangent, RealLight.sampleTangent(tangent, 10, 24), 1e-9);
    }

    @Test
    void floorSamplesDoNotLeaveGapsWhenTheLensCrossesASamplingCellBoundary() {
        // The demo's (-16, 177, -16) fixture, its lens 26.34 blocks above the floor, at 70 degrees.
        double radius = 26.34 * Math.tan(Math.toRadians(70) / 2);
        double[] disk = RealLight.disk(RealLight.grid(radius, 5));
        Long2LongOpenHashMap wanted = new Long2LongOpenHashMap();
        Vec3 center = new Vec3(-15.5, 151, -15.5);
        for (int i = 0; i < disk.length; i += 2) {
            BlockPos at = BlockPos.containing(center.x + disk[i] * radius, 151, center.z + disk[i + 1] * radius);
            RealLight.sample(wanted, at, 5, center, 256);
        }
        int nearest = Integer.MAX_VALUE;
        BlockPos checked = new BlockPos(-20, 151, -20);
        for (long packed : wanted.values()) {
            nearest = Math.min(nearest, checked.distManhattan(BlockPos.of(packed)));
        }
        // Taking the first disk sample per cell left this point at level 7 from this fixture alone.
        assertTrue(15 - nearest >= 10);

        // Even when the cell count hits the limit, later samples can improve an already selected cell.
        Long2LongOpenHashMap capped = new Long2LongOpenHashMap();
        RealLight.sample(capped, new BlockPos(-25, 151, -25), 5, center, 1);
        RealLight.sample(capped, new BlockPos(20, 151, 20), 5, center, 1);
        RealLight.sample(capped, new BlockPos(-21, 151, -21), 5, center, 1);
        assertEquals(1, capped.size());
        assertEquals(new BlockPos(-21, 151, -21), BlockPos.of(capped.values().iterator().nextLong()));
    }

    @Test
    void destroyingAFixtureDefersUnloadedLightsAndPersistsThem() {
        long near = new BlockPos(0, 64, 0).asLong();
        long far = new BlockPos(32, 64, 0).asLong();
        RealLight.Placed placed = new RealLight.Placed();
        placed.load(new long[]{near, far});
        RealLight.Pending pending = new RealLight.Pending();
        Set<Long> removed = new java.util.HashSet<>();

        RealLight.clear(placed, packed -> packed == near, removed::add, pending::add);
        assertEquals(Set.of(near), removed);
        assertEquals(0, placed.size());
        assertTrue(pending.isDirty());

        RealLight.Pending restored = RealLight.Pending.load(pending.save(new CompoundTag(), null), null);
        assertEquals(Set.of(far), restored.lights);
        restored.removeLoaded(packed -> false, removed::add);
        assertEquals(Set.of(near), removed);
        assertEquals(1, restored.lights.size());

        restored.removeLoaded(packed -> packed == far, removed::add);
        assertEquals(Set.of(near, far), removed);
        assertTrue(restored.lights.isEmpty());
        assertTrue(restored.isDirty());
    }

    @Test
    void aFreshOrReloadedFixtureAlwaysRecalculatesItsLight() {
        RealLight.Placed placed = new RealLight.Placed();
        // Subtracting Long.MIN_VALUE used to overflow and skip an initial signature of zero.
        assertTrue(placed.needsUpdate(0, 0));
        placed.signature = 0;
        placed.updated = 100;
        assertFalse(placed.needsUpdate(0, 110));
        assertTrue(placed.needsUpdate(0, 100 + RealLight.REFRESH));
        assertTrue(placed.needsUpdate(0, 99));
        placed.load(new long[0]);
        assertTrue(placed.needsUpdate(0, 110));
    }

    @Test
    void theRaySkipsItsFixtureButHitsAnAdjacentTopSlab() {
        BlockPos fixture = new BlockPos(0, 10, 0);
        BlockPos slab = fixture.below();
        TestBlocks blocks = new TestBlocks();
        blocks.states.put(fixture, Blocks.IRON_BLOCK.defaultBlockState());
        blocks.states.put(slab, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        RealLight.Cast cast = RealLight.Cast.aimed(fixture, 0, 90, 35, 20, FloodlightKind.FLOODLIGHT, 15);
        RealLight.TraceBlocks trace = new RealLight.TraceBlocks(blocks, pos -> true, fixture);
        BlockHitResult hit = trace.clip(new ClipContext(cast.from(), cast.from().add(cast.forward().scale(cast.range())),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        assertEquals(HitResult.Type.BLOCK, hit.getType());
        assertEquals(slab, hit.getBlockPos());
        assertEquals(10, hit.getLocation().y, 1e-6);
    }

    @Test
    void tracingNeverReadsBlocksInUnloadedChunks() {
        TestBlocks blocks = new TestBlocks();
        blocks.unloadedFromX = 16;
        RealLight.TraceBlocks trace = new RealLight.TraceBlocks(blocks, pos -> pos.getX() < 16, BlockPos.ZERO);
        BlockHitResult hit = trace.clip(new ClipContext(new Vec3(15.5, 64.5, 0.5), new Vec3(20.5, 64.5, 0.5),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        assertEquals(HitResult.Type.MISS, hit.getType());
        assertNull(trace.getBlockEntity(new BlockPos(16, 64, 0)));

        // An independently loaded chunk beyond the unknown terrain must not receive light through it.
        TestBlocks farBlocks = new TestBlocks();
        farBlocks.states.put(new BlockPos(20, 64, 0), Blocks.STONE.defaultBlockState());
        RealLight.TraceBlocks disconnected = new RealLight.TraceBlocks(farBlocks,
                pos -> pos.getX() < 16 || pos.getX() >= 20, BlockPos.ZERO);
        BlockHitResult stopped = disconnected.clip(new ClipContext(new Vec3(15.5, 64.5, 0.5), new Vec3(21.5, 64.5, 0.5),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        assertEquals(HitResult.Type.MISS, stopped.getType());
        assertEquals(16, stopped.getBlockPos().getX());
    }

    private static final class TestBlocks implements BlockGetter {
        final Map<BlockPos, BlockState> states = new HashMap<>();
        int unloadedFromX = Integer.MAX_VALUE;

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (pos.getX() >= unloadedFromX) {
                throw new AssertionError("Read an unloaded block: " + pos);
            }
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            getBlockState(pos);
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }
}
