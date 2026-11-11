package io.github.jinsh1228.largelights;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.SectionPos;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/** A conservative geometry bound: only a completely opaque cross-section can shorten a beam's work. */
public final class LightReach {
    private final Predicate<BlockPos> opaque;
    private final Map<Key, Reach> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<AABB, Boolean> rooms = new LinkedHashMap<>();

    private record Key(BlockPos source, Vec3 origin, Vec3 direction, double tangent, double radius) { }
    private static final class Reach {
        final double limit, searched;
        final AABB bounds;
        List<Wall> walls;
        boolean roomChecked;
        AABB room;
        final Long2ByteOpenHashMap sections = new Long2ByteOpenHashMap();
        Reach(double limit, double searched, AABB bounds) { this.limit = limit; this.searched = searched; this.bounds = bounds; }
    }
    private record Wall(int axis, int block, double plane) { }

    public LightReach(Predicate<BlockPos> opaque) {
        this.opaque = opaque;
    }

    public double limit(BlockPos source, LightField.Beam beam) {
        Key key = new Key(source.immutable(), beam.origin(), beam.direction(), beam.tangent(), beam.radius());
        Reach old = cache.get(key);
        if (old != null && (old.limit < old.searched || beam.range() <= old.searched)) return Math.min(beam.range(), old.limit);
        double limit = limit(source, beam, opaque);
        if (old == null && cache.size() >= 256) cache.remove(cache.keySet().iterator().next());
        cache.put(key, new Reach(limit, beam.range(), beam.bounds(limit).inflate(1)));
        return limit;
    }

    public void invalidate(AABB area) {
        rooms.keySet().removeIf(room -> room.inflate(1).intersects(area)
                && !(area.minX >= room.minX && area.maxX <= room.maxX && area.minY >= room.minY
                && area.maxY <= room.maxY && area.minZ >= room.minZ && area.maxZ <= room.maxZ));
        cache.entrySet().removeIf(entry -> entry.getValue().bounds.intersects(area)
                && !(entry.getValue().room != null && outside(entry.getValue().room.inflate(1), area))
                && !covered(entry.getKey().origin, area, entry.getValue().walls));
    }

    /** A verified closed room limits enumeration as well as receiver and shadow work. */
    public AABB bounds(BlockPos source, LightField.Beam beam, double padding) {
        double limit = limit(source, beam);
        Reach reach = cache.get(new Key(source, beam.origin(), beam.direction(), beam.tangent(), beam.radius()));
        room(source, beam, reach);
        AABB bounds = beam.bounds(limit);
        return reach.room == null ? bounds : bounds.intersect(reach.room.inflate(padding));
    }

    private static boolean outside(AABB room, AABB box) {
        return box.maxX < room.minX - 1e-5 || box.minX > room.maxX + 1e-5 || box.maxY < room.minY - 1e-5
                || box.minY > room.maxY + 1e-5 || box.maxZ < room.minZ - 1e-5 || box.minZ > room.maxZ + 1e-5;
    }

    private void room(BlockPos source, LightField.Beam beam, Reach reach) {
        if (reach.roomChecked) return;
        reach.roomChecked = true;
        double[] bounds = new double[6];
        for (Direction side : Direction.values()) {
            Vec3 end = beam.origin().add(side.getStepX() * beam.range(), side.getStepY() * beam.range(), side.getStepZ() * beam.range());
            var hit = BlockGetter.traverseBlocks(beam.origin(), end, source, (ignored, pos) -> {
                if (pos.equals(ignored) || !opaque.test(pos)) return null;
                var candidate = Shapes.block().clip(beam.origin(), end, pos);
                if (candidate == null || candidate.isInside()) return null;
                int axis = side.getAxis().ordinal(), a = (axis + 1) % 3, b = (axis + 2) % 3;
                // Skip isolated fixtures and furniture before testing a complete room boundary.
                return rectangle(axis, coordinate(pos, axis), coordinate(pos, a) - 2, coordinate(pos, a) + 2,
                        coordinate(pos, b) - 2, coordinate(pos, b) + 2, opaque) ? candidate : null;
            }, ignored -> null);
            if (hit == null || hit.isInside()) return;
            int axis = side.getAxis().ordinal();
            bounds[axis + (side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 3 : 0)] = coordinate(hit.getBlockPos(), axis)
                    + (side.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 1 : 0);
        }
        AABB room = new AABB(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        if (!room.contains(beam.origin())) return;
        if (!rooms.containsKey(room)) {
            for (int axis = 0; axis < 3; axis++) {
                int a = (axis + 1) % 3, b = (axis + 2) % 3;
                int minA = Mth.floor(minimum(room, a) + 1e-5), maxA = Mth.floor(maximum(room, a) - 1e-5);
                int minB = Mth.floor(minimum(room, b) + 1e-5), maxB = Mth.floor(maximum(room, b) - 1e-5);
                if (!rectangle(axis, Mth.floor(minimum(room, axis) - 1e-5), minA, maxA, minB, maxB, opaque)
                        || !rectangle(axis, Mth.floor(maximum(room, axis) + 1e-5), minA, maxA, minB, maxB, opaque)) return;
            }
            if (rooms.size() >= 64) rooms.remove(rooms.keySet().iterator().next());
            rooms.put(room, true);
        }
        reach.room = room;
    }

    /** A receiver box is omitted only when its entire projected footprint lies behind opaque blocks. */
    public boolean hidden(BlockPos source, LightField.Beam beam, AABB box) {
        limit(source, beam);
        Key key = new Key(source, beam.origin(), beam.direction(), beam.tangent(), beam.radius());
        Reach reach = cache.get(key);
        room(source, beam, reach);
        if (reach.room != null) return outside(reach.room, box);
        boolean section = box.getXsize() == box.getYsize() && box.getYsize() == box.getZsize()
                && (box.getXsize() == 16 || box.getXsize() == 20);
        double padding = (box.getXsize() - 16) / 2;
        section &= (box.minX + padding) % 16 == 0 && (box.minY + padding) % 16 == 0 && (box.minZ + padding) % 16 == 0;
        long id = section ? SectionPos.asLong(BlockPos.containing(box.getCenter())) : 0;
        byte known = section ? reach.sections.get(id) : 0;
        if (known != 0) return known == 1;
        if (reach.walls == null) reach.walls = walls(source, beam);
        boolean hidden = covered(beam.origin(), box, reach.walls);
        if (section) {
            if (reach.sections.size() >= 512) reach.sections.clear();
            reach.sections.put(id, hidden ? (byte) 1 : (byte) 2);
        }
        return hidden;
    }

    private List<Wall> walls(BlockPos source, LightField.Beam beam) {
        List<Wall> walls = new ArrayList<>();
        Vec3 d = beam.direction();
        Vec3 u = d.cross(Math.abs(d.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize(), v = d.cross(u);
        for (int i = -1; i < 8; i++) {
            double angle = i * Math.PI / 4;
            Vec3 aim = i < 0 ? d : d.add(u.scale(Math.cos(angle) * beam.tangent())).add(v.scale(Math.sin(angle) * beam.tangent()));
            Vec3 end = beam.origin().add(aim.scale(beam.range()));
            BlockGetter.traverseBlocks(beam.origin(), end, source, (ignored, pos) -> {
                if (pos.equals(ignored) || !opaque.test(pos)) return null;
                var hit = Shapes.block().clip(beam.origin(), end, pos);
                if (hit == null || hit.isInside()) return null;
                Direction face = hit.getDirection();
                int axis = face.getAxis().ordinal(), block = coordinate(pos, axis);
                Wall wall = new Wall(axis, block, block + (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0));
                if (!walls.contains(wall)) walls.add(wall);
                return true;
            }, ignored -> null);
        }
        return List.copyOf(walls);
    }

    private boolean covered(Vec3 origin, AABB box, List<Wall> walls) {
        if (walls == null) return false;
        for (Wall wall : walls) {
            int axis = wall.axis, a = (axis + 1) % 3, b = (axis + 2) % 3;
            double from = component(origin, axis), delta = wall.plane - from;
            double near = delta > 0 ? minimum(box, axis) : maximum(box, axis);
            if (delta == 0 || delta > 0 && near <= wall.plane + 1e-5 || delta < 0 && near >= wall.plane - 1e-5) continue;
            double minU = Double.POSITIVE_INFINITY, minV = minU, maxU = -minU, maxV = -minU;
            for (int corner = 0; corner < 8; corner++) {
                double scale = delta / (((corner & 1 << axis) == 0 ? minimum(box, axis) : maximum(box, axis)) - from);
                double u = component(origin, a) + scale * (((corner & 1 << a) == 0 ? minimum(box, a) : maximum(box, a)) - component(origin, a));
                double v = component(origin, b) + scale * (((corner & 1 << b) == 0 ? minimum(box, b) : maximum(box, b)) - component(origin, b));
                minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            if (rectangle(axis, wall.block, Mth.floor(minU - 1e-6), Mth.floor(maxU + 1e-6),
                    Mth.floor(minV - 1e-6), Mth.floor(maxV + 1e-6), opaque)) return true;
        }
        return false;
    }

    private static double minimum(AABB box, int axis) { return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ; }
    private static double maximum(AABB box, int axis) { return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ; }

    static double limit(BlockPos source, LightField.Beam beam, Predicate<BlockPos> opaque) {
        Vec3 end = beam.origin().add(beam.direction().scale(beam.range()));
        int[] attempts = {0};
        Double found = BlockGetter.traverseBlocks(beam.origin(), end, source, (ignored, pos) -> {
            if (pos.equals(ignored) || !opaque.test(pos)) return null;
            if (++attempts[0] > 8) return beam.range();
            var hit = Shapes.block().clip(beam.origin(), end, pos);
            if (hit == null || hit.isInside()) return null;
            double limit = plane(beam, pos, hit.getDirection(), opaque);
            return limit < beam.range() ? limit : null;
        }, ignored -> null);
        return found == null ? beam.range() : found;
    }

    private static double plane(LightField.Beam beam, BlockPos block, Direction face, Predicate<BlockPos> opaque) {
        int axis = face.getAxis().ordinal(), a = (axis + 1) % 3, b = (axis + 2) % 3;
        double d = component(beam.direction(), axis), abs = Math.abs(d), t = beam.tangent();
        double perpendicular = Math.sqrt(Math.max(0, 1 - d * d));
        double denominator = abs - t * perpendicular;
        if (denominator <= 1e-8) return beam.range(); // Some rays can pass parallel to this plane.
        double plane = coordinate(block, axis) + (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
        double delta = plane - component(beam.origin(), axis), distance = Math.abs(delta);
        double cap = Math.min(Math.max(distance / denominator,
                        (distance + Math.min(.08, beam.radius()) * perpendicular) / abs),
                (distance + beam.radius() * perpendicular) / abs) + .001;
        if (cap >= beam.range()) return beam.range();
        double[] u = footprint(beam, axis, a, delta), v = footprint(beam, axis, b, delta);
        int minU = Mth.floor(u[0] - 1e-6), maxU = Mth.floor(u[1] + 1e-6);
        int minV = Mth.floor(v[0] - 1e-6), maxV = Mth.floor(v[1] + 1e-6);
        return rectangle(axis, coordinate(block, axis), minU, maxU, minV, maxV, opaque) ? cap : beam.range();
    }

    private static boolean rectangle(int axis, int block, int minU, int maxU, int minV, int maxV, Predicate<BlockPos> opaque) {
        if ((long) (maxU - minU + 1) * (maxV - minV + 1) > 16384) return false;
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] xyz = new int[3];
        xyz[axis] = block;
        for (int y = minV; y <= maxV; y++) {
            for (int x = minU; x <= maxU; x++) {
                xyz[a] = x; xyz[b] = y;
                pos.set(xyz[0], xyz[1], xyz[2]);
                if (!opaque.test(pos)) return false;
            }
        }
        return true;
    }

    /** Bound both the angular cone and the radius-capped cylinder on an axis-aligned plane. */
    private static double[] footprint(LightField.Beam beam, int axis, int other, double delta) {
        double d = component(beam.direction(), axis), e = component(beam.direction(), other), t = beam.tangent();
        double q = 1 + t * t, aa = q * d * d - t * t, bb = -2 * q * d * e, cc = q * e * e - t * t;
        double root = Math.sqrt(Math.max(0, bb * bb - 4 * aa * cc));
        double first = delta * (-bb - root) / (2 * aa), second = delta * (-bb + root) / (2 * aa);
        double center = delta * e / d, radius = beam.radius() * Math.sqrt(1 + e * e / (d * d));
        double origin = component(beam.origin(), other);
        double core = Math.min(.08, beam.radius()) * Math.sqrt(1 + e * e / (d * d));
        return new double[]{origin + Math.max(Math.min(Math.min(first, second), center - core), center - radius),
                origin + Math.min(Math.max(Math.max(first, second), center + core), center + radius)};
    }

    private static double component(Vec3 vector, int axis) {
        return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
    }

    private static int coordinate(BlockPos pos, int axis) {
        return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
    }
}
