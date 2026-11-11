package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

class LightReachTest {
    private static LightField.Beam beam(double range) {
        return new LightField.Beam(new Vec3(.5, 20.5, .5), new Vec3(0, -1, 0), .5, range, 1, 15, 48);
    }

    @Test
    void completeWallLimitsWorkButOneHoleRetainsTheOriginalDistance() {
        Predicate<BlockPos> floor = p -> p.getY() == 0;
        assertEquals(19.501, LightReach.limit(new BlockPos(0, 20, 0), beam(256), floor), 1e-8);
        assertEquals(256, LightReach.limit(new BlockPos(0, 20, 0), beam(256),
                p -> floor.test(p) && !(p.getX() == 3 && p.getZ() == 2)));
    }

    @Test
    void extendingRangeReusesTheWallProofAndRemovingItInvalidatesTheProof() {
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean hole = new AtomicBoolean();
        LightReach cache = new LightReach(p -> {
            reads.incrementAndGet();
            return p.getY() == 0 && !(hole.get() && p.getX() == 3 && p.getZ() == 2);
        });
        BlockPos source = new BlockPos(0, 20, 0);
        double limit = cache.limit(source, beam(64));
        reads.set(0);
        assertEquals(limit, cache.limit(source, beam(256)));
        assertEquals(0, reads.get());
        cache.invalidate(new AABB(100, -10, 100, 101, -9, 101));
        assertEquals(limit, cache.limit(source, beam(256)));
        assertEquals(0, reads.get());
        hole.set(true);
        cache.invalidate(new AABB(new BlockPos(3, 0, 2)));
        assertEquals(256, cache.limit(source, beam(256)));
    }

    @Test
    void shortenedBoundsOnlyDiscardPointsActuallyBehindOpaqueGeometry() {
        Random random = new Random(31841);
        int shortened = 0;
        for (int trial = 0; trial < 200; trial++) {
            int axis = trial % 3, sign = trial % 2 == 0 ? 1 : -1;
            Vec3 origin = new Vec3(.3, .4, .6);
            double[] direction = {random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5};
            direction[axis] = sign;
            LightField.Beam beam = new LightField.Beam(origin, new Vec3(direction[0], direction[1], direction[2]),
                    .001 + random.nextDouble() * .7, 256, 1, 15, .02 + random.nextDouble() * 48);
            int coordinate = sign * (1 + random.nextInt(30));
            Predicate<BlockPos> wall = p -> (axis == 0 ? p.getX() : axis == 1 ? p.getY() : p.getZ()) == coordinate;
            BlockPos source = BlockPos.containing(origin);
            double limit = LightReach.limit(source, beam, wall);
            if (limit == beam.range()) continue;
            shortened++;
            for (int sample = 0; sample < 200; sample++) {
                double along = limit + random.nextDouble() * (beam.range() - limit);
                Vec3 u = beam.direction().cross(Math.abs(beam.direction().y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
                Vec3 v = beam.direction().cross(u);
                double angle = random.nextDouble() * Math.PI * 2;
                double width = Math.min(beam.radius(), Math.max(.08, along * beam.tangent())) * random.nextDouble() * .999;
                Vec3 target = origin.add(beam.direction().scale(along)).add(u.scale(width * Math.cos(angle)))
                        .add(v.scale(width * Math.sin(angle)));
                assertTrue(beam.contribution(target) > 0);
                Boolean blocked = BlockGetter.traverseBlocks(origin, target, wall, (test, p) ->
                        test.test(p) && Shapes.block().clip(origin, target, p) != null ? true : null, test -> null);
                assertEquals(Boolean.TRUE, blocked, "a visible point must never be discarded: " + trial);
            }
        }
        assertTrue(shortened > 100);
    }

    @Test
    void partialWallProofHandlesAClosedRoomAndReopensWhenAWallGetsAHole() {
        AtomicBoolean hole = new AtomicBoolean();
        Predicate<BlockPos> room = p -> p.getY() == 0 && Math.abs(p.getX()) <= 12 && Math.abs(p.getZ()) <= 12
                && !(hole.get() && p.getX() == 1 && p.getZ() == 0);
        LightReach reach = new LightReach(room);
        BlockPos source = new BlockPos(0, 20, 0);
        LightField.Beam beam = new LightField.Beam(new Vec3(.5, 20.5, .5), new Vec3(0, -1, 0), 1, 256, 1, 15, 48);
        AABB receiver = new AABB(-4, -31, -4, 4, -23, 4);
        assertEquals(256, reach.limit(source, beam)); // The whole beam cannot be closed by the finite floor.
        assertTrue(reach.hidden(source, beam, receiver));
        assertFalse(reach.hidden(source, beam, new AABB(-4, .5, -4, 4, 8, 4)));
        hole.set(true);
        reach.invalidate(new AABB(new BlockPos(1, 0, 0)));
        assertFalse(reach.hidden(source, beam, receiver));
    }

    @Test
    void partialProofNeverHidesALitPointThroughRoomOpenings() {
        Random random = new Random(16739);
        Predicate<BlockPos> room = p -> p.getX() >= -12 && p.getX() <= 12 && p.getZ() >= -12 && p.getZ() <= 12
                && p.getY() >= 0 && p.getY() <= 24
                && (p.getY() == 0 || p.getX() == -12 || p.getX() == 12 || p.getZ() == -12 || p.getZ() == 12)
                && !(p.getY() == 0 && p.getX() >= 2 && p.getX() <= 4 && p.getZ() >= 0 && p.getZ() <= 2);
        LightReach reach = new LightReach(room);
        BlockPos source = new BlockPos(0, 20, 0);
        LightField.Beam beam = new LightField.Beam(source.getCenter(), new Vec3(.3, -1, .2), 1, 256, 1, 15, 48);
        int hidden = 0;
        for (int trial = 0; trial < 500; trial++) {
            Vec3 center = source.getCenter().add(beam.direction().scale(24 + random.nextDouble() * 120))
                    .add(random.nextDouble() * 64 - 32, 0, random.nextDouble() * 64 - 32);
            AABB box = new AABB(center, center).inflate(2 + random.nextDouble() * 6);
            if (!reach.hidden(source, beam, box)) continue;
            hidden++;
            for (int sample = 0; sample < 50; sample++) {
                Vec3 point = new Vec3(box.minX + random.nextDouble() * box.getXsize(),
                        box.minY + random.nextDouble() * box.getYsize(), box.minZ + random.nextDouble() * box.getZsize());
                if (beam.contribution(point) == 0) continue;
                Boolean blocked = BlockGetter.traverseBlocks(beam.origin(), point, room, (test, p) ->
                        test.test(p) && Shapes.block().clip(beam.origin(), point, p) != null ? true : null, test -> null);
                assertEquals(Boolean.TRUE, blocked, "a point visible through a hole must remain: " + trial);
            }
        }
        assertTrue(hidden > 100);
    }

    @Test
    void closedRoomBoundsSurviveInteriorChangesButReopenAtAHole() {
        AtomicBoolean hole = new AtomicBoolean();
        Predicate<BlockPos> walls = p -> p.getX() >= -12 && p.getX() <= 12 && p.getZ() >= -12 && p.getZ() <= 12
                && p.getY() >= 0 && p.getY() <= 24
                && (p.getY() == 0 || p.getY() == 24 || Math.abs(p.getX()) == 12 || Math.abs(p.getZ()) == 12)
                && !(hole.get() && p.getY() == 0 && p.getX() == 1 && p.getZ() == 0);
        LightReach reach = new LightReach(p -> walls.test(p) || p.equals(new BlockPos(0, 22, 0)));
        BlockPos source = new BlockPos(0, 20, 0);
        LightField.Beam beam = new LightField.Beam(source.getCenter(), new Vec3(.2, -1, .1), 1, 256, 1, 15, 48);
        AABB domain = reach.bounds(source, beam, 2);
        assertTrue(domain.minY >= -1 && domain.minX >= -13 && domain.maxX <= 14);
        assertTrue(reach.hidden(source, beam, new AABB(-4, -30, -4, 4, -22, 4)));
        reach.invalidate(new AABB(new BlockPos(2, 10, 2)));
        assertEquals(domain, reach.bounds(source, beam, 2));
        hole.set(true);
        reach.invalidate(new AABB(new BlockPos(1, 0, 0)));
        assertTrue(reach.bounds(source, beam, 2).minY < -100);
    }
}
