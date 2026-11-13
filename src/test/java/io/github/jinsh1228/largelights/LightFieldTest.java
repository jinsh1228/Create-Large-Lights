package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class LightFieldTest {
    private static LightField.Beam beam(int level) {
        return new LightField.Beam(new Vec3(.5, .5, .5), new Vec3(0, 0, 1), .5, 128, 1, level, 48);
    }

    private static LightField.Beam weighted(LightField.Beam beam, double strength, int level) {
        return new LightField.Beam(beam.origin(), beam.direction(), beam.tangent(), beam.range(), strength, level, beam.radius());
    }

    @Test
    void nearLightIsNarrowAndStrongAndDistantLightBroadAndWeaker() {
        LightField.Beam beam = beam(12);
        assertEquals(12, beam.contribution(new Vec3(.5, .5, 8.5)), 1e-9);
        assertEquals(6, beam.contribution(new Vec3(.5, .5, 32.5)), 1e-9);
        assertEquals(3, beam.contribution(new Vec3(.5, .5, 64.5)), 1e-9);
        assertEquals(0, beam.contribution(new Vec3(4.5, .5, 8.5)));
        assertTrue(beam.contribution(new Vec3(4.5, .5, 32.5)) > 0);
    }

    @Test
    void completeBeamBoundsRemainDarkDespiteAnAdjacentLitCell() {
        LightField.Field field = new LightField.Field((source, beam, target) -> true);
        field.update(BlockPos.ZERO, beam(15));
        assertTrue(field.sample(new BlockPos(3, 0, 8)) > 0);
        assertEquals(0, field.sample(new BlockPos(4, 0, 8))); // no propagation across the cone boundary
        assertEquals(0, field.sample(new BlockPos(0, 0, -1)));
        assertEquals(0, field.sample(new BlockPos(0, 0, 128)));
        assertEquals(0, field.sample(new BlockPos(49, 0, 100))); // fixture radius cap
        assertEquals(0, LightField.level(0));
    }

    @Test
    void overlappingLevelsAddBeforeSaturatingAndRemovingOneLeavesTheOther() {
        LightField.Field field = new LightField.Field((source, beam, target) -> true);
        BlockPos a = BlockPos.ZERO, b = new BlockPos(1, 0, 0), c = new BlockPos(2, 0, 0);
        BlockPos target = new BlockPos(0, 0, 8);
        field.update(a, beam(6));
        assertEquals(6, field.sample(target));
        field.update(b, beam(6));
        assertEquals(12, field.sample(target));
        field.update(c, beam(6));
        assertEquals(15, field.sample(target));
        field.update(c, null);
        assertEquals(12, field.sample(target));
        field.update(a, null);
        assertEquals(6, field.sample(target));
        field.update(b, null);
        assertEquals(0, field.sample(target));
    }

    @Test
    void floatingContributionsAddBeforeQuantizationAndFaintFarLightRemainsUsable() {
        LightField.Beam dim = new LightField.Beam(new Vec3(.5, .5, .5), new Vec3(0, 0, 1), .5, 300, .04, 15, 48);
        LightField.Field field = new LightField.Field((source, beam, target) -> true);
        field.update(BlockPos.ZERO, dim);
        assertEquals(1, field.sample(new BlockPos(0, 0, 8)));
        field.update(new BlockPos(1, 0, 0), dim);
        field.update(new BlockPos(2, 0, 0), dim);
        assertEquals(2, field.sample(new BlockPos(0, 0, 8))); // round(.6+.6+.6), not 1+1+1
        assertEquals(1, field.sample(new BlockPos(0, 0, 256)));
    }

    @Test
    void cachedOcclusionIsRecheckedOnlyAfterAffectedBlocksChange() {
        AtomicBoolean blocked = new AtomicBoolean();
        AtomicInteger traces = new AtomicInteger();
        LightField.Field field = new LightField.Field((source, beam, target) -> {
            traces.incrementAndGet();
            return !blocked.get();
        });
        field.update(BlockPos.ZERO, beam(6));
        BlockPos target = new BlockPos(0, 0, 8);
        assertEquals(6, field.sample(target));
        assertEquals(6, field.sample(target));
        assertEquals(1, traces.get());
        blocked.set(true);
        assertFalse(field.invalidate(new BlockPos(0, 0, 4)).isEmpty());
        assertEquals(0, field.sample(target));
        assertEquals(2, traces.get());
    }

    @Test
    void unchangedFixturesKeepCachesAndChangesOnlyAffectTheirOwnRegion() {
        LightField.Field field = new LightField.Field((source, beam, target) -> true);
        LightField.Beam first = beam(6);
        LightField.Beam distant = new LightField.Beam(new Vec3(512.5, .5, .5), new Vec3(0, 0, 1), .5, 128, 1, 5, 48);
        field.update(BlockPos.ZERO, first);
        field.update(new BlockPos(512, 0, 0), distant);
        assertEquals(6, field.sample(new BlockPos(0, 0, 8)));
        assertEquals(5, field.sample(new BlockPos(512, 0, 8)));
        long revision = field.revision;
        assertNull(field.update(BlockPos.ZERO, first));
        assertEquals(revision, field.revision);
        assertNotNull(field.update(BlockPos.ZERO, null));
        assertEquals(0, field.sample(new BlockPos(0, 0, 8)));
        assertEquals(5, field.sample(new BlockPos(512, 0, 8)));
    }

    @Test
    void cacheMemoryIsBoundedAndChunkReloadInvalidatesOcclusion() {
        LightField.Field field = new LightField.Field((source, beam, target) -> true);
        field.update(BlockPos.ZERO, new LightField.Beam(new Vec3(.5, .5, .5), new Vec3(0, 1, 0),
                .5, 10000, 1, 6, 48));
        for (int y = 0; y < 600; y++) {
            field.sample(new BlockPos(0, y * 16, 8));
        }
        assertEquals(LightField.CACHE_SECTIONS, field.sections.size());
        assertFalse(field.invalidateChunk(0, 0).isEmpty());
        assertTrue(field.sections.isEmpty());
    }

    @Test
    void bothFixtureKindsUseTheirRenderedLensOrigin() {
        BlockPos pos = new BlockPos(10, 64, -20);
        for (FloodlightKind kind : FloodlightKind.values()) {
            for (float[] aim : new float[][]{{0, 90}, {35, 15}, {-130, -45}}) {
                LightField.Beam beam = RealLight.aimed(pos, aim[0], aim[1], 35, 128, kind, 1, 15);
                var local = Aim.head(aim[0], aim[1]).transformPosition(new org.joml.Vector3f(0, 0, kind.lensZ + .01f));
                Vec3 rendered = Vec3.atLowerCornerOf(pos).add(local.x, local.y, local.z);
                assertTrue(beam.origin().distanceToSqr(rendered) < 1e-10);
            }
        }
    }

    @Test
    void sectionCandidatesRejectBehindBeyondAndOutsideTheRadiusButRetainTheNarrowCore() {
        LightField.Beam beam = new LightField.Beam(Vec3.ZERO, new Vec3(0, 0, 1), .5, 32, 1, 15, 4);
        assertTrue(beam.intersects(new AABB(-.1, -.1, 31.8, .1, .1, 31.9)));
        assertFalse(beam.intersects(new AABB(-1, -1, -2, 1, 1, -1)));
        assertFalse(beam.intersects(new AABB(-1, -1, 33, 1, 1, 34)));
        assertFalse(beam.intersects(new AABB(4.1, -.1, 20, 4.2, .1, 21)));
        LightField.Beam narrow = new LightField.Beam(Vec3.ZERO, new Vec3(0, 0, 1), .00001, 32, 1, 15, 4);
        assertTrue(narrow.intersects(new AABB(.07, -.001, .1, .079, .001, .2)));
        assertTrue(narrow.bounds().contains(.079, 0, .1));
        LightField.Beam capped = new LightField.Beam(Vec3.ZERO, new Vec3(0, 0, 1), .00001, 32, 1, 15, .02);
        assertFalse(capped.intersects(new AABB(.03, -.001, .1, .04, .001, .2)));
    }

    @Test
    void conservativeCandidatesRetainInteriorPointsForAxesNearAndFarAndCappedCones() {
        Random random = new Random(244);
        Vec3[] axes = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0),
                new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
        for (int i = 0; i < 10000; i++) {
            Vec3 direction = i % 2 == 0 ? axes[i / 2 % axes.length]
                    : new Vec3(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5).normalize();
            Vec3 origin = new Vec3(random.nextDouble() * 200 - 100, random.nextDouble() * 200 - 100,
                    random.nextDouble() * 200 - 100);
            double range = .1 + random.nextDouble() * 320;
            LightField.Beam beam = new LightField.Beam(origin, direction, Math.pow(10, random.nextDouble() * 5 - 4),
                    range, 1, 15, Math.pow(10, random.nextDouble() * 5 - 3));
            double along = range * (i % 3 == 0 ? .000001 : i % 3 == 1 ? .999999 : random.nextDouble());
            double width = Math.min(beam.radius(), Math.max(.08, along * beam.tangent()));
            Vec3 u = direction.cross(Math.abs(direction.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
            Vec3 v = direction.cross(u);
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = width * (i % 4 == 0 ? .999 : random.nextDouble());
            Vec3 point = origin.add(direction.scale(along)).add(u.scale(distance * Math.cos(angle)))
                    .add(v.scale(distance * Math.sin(angle)));
            AABB box = new AABB(point.x - random.nextDouble() * 16, point.y - random.nextDouble() * 16,
                    point.z - random.nextDouble() * 16, point.x + random.nextDouble() * 16,
                    point.y + random.nextDouble() * 16, point.z + random.nextDouble() * 16);
            assertTrue(beam.contribution(point) > 0, "generated point must be lit: " + i);
            assertTrue(beam.intersects(box), "candidate filter must retain a lit point: " + i);
        }
    }

    @Test
    void brightnessAndLevelChangesReuseVisibilityButPoseAndOcclusionRefreshIt() {
        AtomicInteger traces = new AtomicInteger();
        AtomicBoolean blocked = new AtomicBoolean();
        LightField.Field field = new LightField.Field((source, beam, target) -> {
            traces.incrementAndGet();
            return !blocked.get();
        });
        BlockPos target = new BlockPos(0, 0, 8);
        LightField.Beam beam = beam(6);
        field.update(BlockPos.ZERO, beam);
        assertEquals(6, field.sample(target));
        assertTrue(field.update(BlockPos.ZERO, weighted(beam, .5, 6)).weightsOnly());
        assertEquals(3, field.sample(target));
        field.update(BlockPos.ZERO, weighted(beam, .5, 10));
        assertEquals(5, field.sample(target));
        assertEquals(1, traces.get());
        LightField.Beam moved = new LightField.Beam(beam.origin().add(.01, 0, 0), beam.direction(), beam.tangent(),
                beam.range(), .5, 10, beam.radius());
        assertFalse(field.update(BlockPos.ZERO, moved).weightsOnly());
        assertEquals(5, field.sample(target));
        assertEquals(2, traces.get());
        blocked.set(true);
        field.invalidate(new BlockPos(0, 0, 4));
        assertEquals(0, field.sample(target));
        assertEquals(3, traces.get());
    }

    @Test
    void unfinishedSnapshotsKeepTheirWeightsAndNeverOverwriteTheCurrentLevelCache() {
        AtomicInteger traces = new AtomicInteger();
        LightField.Field field = new LightField.Field((source, beam, target) -> {
            traces.incrementAndGet();
            return true;
        });
        BlockPos first = new BlockPos(0, 0, 8), second = new BlockPos(0, 0, 9);
        LightField.Beam beam = beam(6);
        field.update(BlockPos.ZERO, beam);
        LightField.Snapshot snapshot = field.snapshot(SectionPos.asLong(first));
        assertEquals(6, snapshot.sample(first));
        field.update(BlockPos.ZERO, weighted(beam, .25, 6));
        assertEquals(2, field.sample(first));
        assertEquals(6, snapshot.sample(first));
        assertEquals(6, snapshot.sample(second));
        assertEquals(2, field.sample(first));
        assertEquals(2, field.sample(second));
        assertEquals(2, traces.get()); // One trace per cell, shared by old and current weights.
    }

    @Test
    void moreThanSixteenLightsReuseVisibilityWhenBrightnessChanges() {
        AtomicInteger traces = new AtomicInteger();
        LightField.Field field = new LightField.Field((source, beam, target) -> {
            traces.incrementAndGet();
            return source.getX() == 16;
        });
        BlockPos target = new BlockPos(0, 0, 8);
        LightField.Beam dim = weighted(beam(1), .01, 1);
        for (int i = 0; i < 17; i++) {
            field.update(new BlockPos(i, 0, 0), dim);
        }
        assertEquals(1, field.sample(target));
        assertEquals(17, traces.get());
        field.update(BlockPos.ZERO, weighted(dim, .02, 1));
        assertEquals(1, field.sample(target));
        assertEquals(17, traces.get());
        assertEquals(17, field.sections.get(SectionPos.asLong(target)).visibility.size());
    }

    @Test
    void aBlockTouchingTheConeBoundaryInvalidatesVisibilityEvenWhenItsCenterIsOutside() {
        AtomicBoolean blocked = new AtomicBoolean();
        AtomicInteger traces = new AtomicInteger();
        LightField.Field field = new LightField.Field((source, beam, target) -> {
            traces.incrementAndGet();
            return !blocked.get();
        });
        LightField.Beam beam = beam(6);
        BlockPos obstruction = new BlockPos(48, 0, 100), target = new BlockPos(0, 0, 8);
        assertFalse(beam.bounds().contains(obstruction.getCenter()));
        assertTrue(beam.intersectsBlock(obstruction));
        field.update(BlockPos.ZERO, beam);
        assertEquals(6, field.sample(target));
        blocked.set(true);
        assertFalse(field.invalidate(obstruction).isEmpty());
        assertEquals(0, field.sample(target));
        assertEquals(2, traces.get());
    }

    @Test
    void tileCandidatesPreserveTheCompletePointSampleAcrossMixedBeams() {
        Random random = new Random(5512);
        LightField.Field field = new LightField.Field((source, beam, target) -> true);
        for (int i = 0; i < 32; i++) {
            field.update(new BlockPos(i, 80, 0), new LightField.Beam(
                    new Vec3(random.nextDouble() * 30 - 15, 40, random.nextDouble() * 30 - 15),
                    new Vec3(random.nextDouble() - .5, -1, random.nextDouble() - .5),
                    .01 + random.nextDouble(), 128, .1, 15, 48));
        }
        long section = SectionPos.asLong(0, 1, 0);
        var snapshot = field.snapshot(section);
        for (int y = 16; y < 32; y += 4) for (int z = 0; z < 16; z += 4) for (int x = 0; x < 16; x += 4) {
            var tile = snapshot.tile(new AABB(x, y, z, x + 4, y + 4, z + 4));
            for (BlockPos pos : BlockPos.betweenClosed(x, y, z, x + 3, y + 3, z + 3)) {
                int expected = LightField.level(snapshot.entries().stream().mapToDouble(entry -> entry.beam().contribution(pos.getCenter())).sum());
                assertEquals(expected, tile.sample(pos));
            }
        }
    }

    @Test
    void rangeAndSpreadChangesReuseTheSameSourceToCellVisibility() {
        AtomicInteger traces = new AtomicInteger();
        LightField.Field field = new LightField.Field((source, beam, target) -> { traces.incrementAndGet(); return true; });
        LightField.Beam first = beam(6);
        BlockPos point = new BlockPos(0, 0, 8);
        field.update(BlockPos.ZERO, first);
        assertEquals(6, field.sample(point));
        field.update(BlockPos.ZERO, new LightField.Beam(first.origin(), first.direction(), .8, 256, 1, 6, 48));
        assertEquals(6, field.sample(point));
        assertEquals(1, traces.get());
    }
    @Test
    void brightnessChangesReuseSectionCandidatesAndKeepOldSnapshotsImmutable() {
        AtomicInteger bounds = new AtomicInteger();
        LightField.Field field = new LightField.Field(new LightField.Visibility() {
            public boolean visible(BlockPos source, LightField.Beam beam, BlockPos target) { return true; }
            public double range(BlockPos source, LightField.Beam beam) { bounds.incrementAndGet(); return beam.range(); }
        });
        field.update(BlockPos.ZERO, beam(12));
        BlockPos target = new BlockPos(0, 0, 8);
        long section = SectionPos.asLong(target);
        LightField.Snapshot original = field.snapshot(section);
        assertEquals(12, original.sample(target));
        int before = bounds.get();
        field.update(BlockPos.ZERO, weighted(beam(12), .5, 12));
        assertEquals(6, field.snapshot(section).sample(target));
        assertEquals(before, bounds.get());
        assertEquals(12, original.sample(target));
        field.invalidate(new BlockPos(0, 0, 4));
        field.snapshot(section);
        assertTrue(bounds.get() > before);
        field.update(BlockPos.ZERO, null);
        assertFalse(field.affects(section));
    }
    @Test
    void globalVisibilityBudgetEvictsOldMasksAndReleasesThemWithTheirSections() {
        AtomicInteger traces = new AtomicInteger();
        LightField.Field field = new LightField.Field((source, beam, target) -> { traces.incrementAndGet(); return true; });
        LightField.Beam dim = weighted(beam(1), 1.0 / 1024, 1);
        for (int i = 0; i < 128; i++) field.update(new BlockPos(i, 0, 0), dim);
        BlockPos first = null;
        for (int z = 24; z < 128; z += 16) for (int y = -40; y <= 40; y += 16) for (int x = -40; x <= 40; x += 16) {
            BlockPos pos = new BlockPos(x, y, z);
            if (dim.contribution(pos.getCenter()) <= 0) continue;
            if (first == null) first = pos;
            assertEquals(1, field.sample(pos));
        }
        assertEquals(LightField.VISIBILITY_MASKS, field.maskCount);
        assertEquals(field.maskCount, field.sections.values().stream().mapToInt(section -> section.visibility.size()).sum());
        int before = traces.get();
        field.update(BlockPos.ZERO, weighted(dim, 1.0 / 2048, 1));
        assertEquals(1, field.sample(first));
        assertTrue(traces.get() > before); // Evicted masks are recomputed, never treated as clear or blocked.
        field.forgetChunk(new net.minecraft.world.level.ChunkPos(first));
        assertEquals(field.maskCount, field.sections.values().stream().mapToInt(section -> section.visibility.size()).sum());
        field.invalidate(new BlockPos(0, 0, 8));
        assertEquals(0, field.maskCount);
        assertTrue(field.sections.isEmpty());
    }

    @Test
    void editingOldRayPathsWhileAimedAwayOrShortenedRefreshesThemWhenRestored() {
        for (boolean turn : new boolean[]{false, true}) {
            AtomicBoolean blocked = new AtomicBoolean();
            LightField.Field field = new LightField.Field((source, beam, target) -> !blocked.get());
            LightField.Beam original = beam(6);
            BlockPos target = new BlockPos(0, 0, 40);
            field.update(BlockPos.ZERO, original);
            assertTrue(field.sample(target) > 0);
            field.update(BlockPos.ZERO, new LightField.Beam(original.origin(), turn ? new Vec3(1, 0, 0) : original.direction(),
                    original.tangent(), turn ? original.range() : 8, 1, 6, original.radius()));
            blocked.set(true);
            field.invalidate(new BlockPos(0, 0, 20));
            assertEquals(0, field.maskCount);
            field.update(BlockPos.ZERO, original);
            assertEquals(0, field.sample(target), "old ray after " + (turn ? "turning" : "shortening"));
        }
    }

    @Test
    void chunkChangesAlsoRetireOldRaysOutsideTheCurrentRange() {
        AtomicBoolean blocked = new AtomicBoolean();
        LightField.Field field = new LightField.Field((source, beam, target) -> !blocked.get());
        LightField.Beam original = beam(6);
        BlockPos target = new BlockPos(0, 0, 40);
        field.update(BlockPos.ZERO, original);
        assertTrue(field.sample(target) > 0);
        field.update(BlockPos.ZERO, new LightField.Beam(original.origin(), original.direction(), original.tangent(),
                8, 1, 6, original.radius()));
        blocked.set(true);
        field.invalidateChunk(0, 1);
        field.update(BlockPos.ZERO, original);
        assertEquals(0, field.sample(target));
    }

    @Test
    void removingAShortenedFixtureReleasesMasksFromItsFormerRange() {
        LightField.Field field = new LightField.Field((source, beam, target) -> true);
        LightField.Beam original = beam(6);
        field.update(BlockPos.ZERO, original);
        assertTrue(field.sample(new BlockPos(0, 0, 40)) > 0);
        field.update(BlockPos.ZERO, new LightField.Beam(original.origin(), original.direction(), original.tangent(),
                8, 1, 6, original.radius()));
        assertEquals(1, field.maskCount);
        field.update(BlockPos.ZERO, null);
        assertEquals(0, field.maskCount);
    }

    @Test
    void unrelatedEditsKeepRaysFromEarlierGeometryReusable() {
        AtomicInteger traces = new AtomicInteger();
        LightField.Field field = new LightField.Field((source, beam, target) -> { traces.incrementAndGet(); return true; });
        LightField.Beam original = beam(6);
        BlockPos target = new BlockPos(0, 0, 40);
        field.update(BlockPos.ZERO, original);
        int before = field.sample(target);
        field.update(BlockPos.ZERO, new LightField.Beam(original.origin(), original.direction(), original.tangent(),
                8, 1, 6, original.radius()));
        assertTrue(field.invalidate(new BlockPos(512, 0, 20)).isEmpty());
        field.update(BlockPos.ZERO, original);
        assertEquals(before, field.sample(target));
        assertEquals(1, traces.get());
    }
}
