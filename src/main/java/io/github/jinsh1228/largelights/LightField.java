package io.github.jinsh1228.largelights;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/** Additive floodlight illumination. It neither places blocks nor propagates into cells outside its beams. */
public final class LightField {
    static final int CACHE_SECTIONS = 512;
    static final int VISIBILITY_SOURCES = 16;
    private static final Map<Level, Field> LEVELS = new WeakHashMap<>();
    private static volatile BiConsumer<Level, AABB> changed = (level, bounds) -> { };

    private LightField() {
    }

    /** A reference level at 16 blocks, falling as 1/distance, with a soft cone edge and range fade. */
    public record Beam(Vec3 origin, Vec3 direction, double tangent, double range, double strength,
                       int maxLevel, double radius) {
        public Beam {
            direction = direction.normalize();
            strength = Mth.clamp(strength, 0, 1);
            maxLevel = Mth.clamp(maxLevel, 0, 15);
        }

        boolean active() {
            return maxLevel > 0 && strength > 0 && tangent > 0 && range > 0 && radius > 0
                    && Double.isFinite(origin.x + origin.y + origin.z + direction.x + direction.y + direction.z
                    + tangent + range + strength + radius);
        }

        boolean sameGeometry(Beam other) {
            return origin.equals(other.origin) && direction.equals(other.direction) && tangent == other.tangent
                    && range == other.range && radius == other.radius;
        }

        public double contribution(Vec3 point) {
            return contribution(point.x, point.y, point.z);
        }

        double contribution(double x, double y, double z) {
            x -= origin.x;
            y -= origin.y;
            z -= origin.z;
            double along = x * direction.x + y * direction.y + z * direction.z;
            if (along <= 0 || along >= range) {
                return 0;
            }
            double squared = x * x + y * y + z * z;
            double width = Math.min(radius, Math.max(0.08, along * tangent));
            double radial = Math.sqrt(Math.max(0, squared - along * along)) / width;
            if (radial >= 1) {
                return 0;
            }
            double edge = Mth.clamp((1 - radial) / 0.25, 0, 1);
            double reach = Mth.clamp((range - along) / Math.max(1, range * 0.2), 0, 1);
            return maxLevel * strength * Math.min(1, 16 / Math.sqrt(squared))
                    * edge * edge * (3 - 2 * edge) * reach;
        }

        AABB bounds() {
            Vec3 end = origin.add(direction.scale(range));
            double width = Math.min(radius, Math.max(0.08, range * tangent));
            return new AABB(Math.min(origin.x, end.x), Math.min(origin.y, end.y), Math.min(origin.z, end.z),
                    Math.max(origin.x, end.x), Math.max(origin.y, end.y), Math.max(origin.z, end.z)).inflate(width);
        }

        /** Conservative rejection only: exact per-cell contribution and world occlusion remain unchanged. */
        public boolean intersects(AABB box) {
            return intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        boolean intersectsSection(long section) {
            int x = SectionPos.x(section) << 4, y = SectionPos.y(section) << 4, z = SectionPos.z(section) << 4;
            return intersects(x, y, z, x + 16, y + 16, z + 16);
        }

        boolean intersectsBlock(BlockPos pos) {
            return intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        }

        private boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            double x = (minX + maxX) * .5 - origin.x;
            double y = (minY + maxY) * .5 - origin.y;
            double z = (minZ + maxZ) * .5 - origin.z;
            double hx = (maxX - minX) * .5, hy = (maxY - minY) * .5, hz = (maxZ - minZ) * .5;
            double along = x * direction.x + y * direction.y + z * direction.z;
            double extent = hx * Math.abs(direction.x) + hy * Math.abs(direction.y) + hz * Math.abs(direction.z);
            if (along + extent < -1e-7 || along - extent > range + 1e-7) {
                return false;
            }
            x -= along * direction.x;
            y -= along * direction.y;
            z -= along * direction.z;
            double radial = Math.sqrt(x * x + y * y + z * z);
            if (radial < 1e-9) {
                return true;
            }
            x /= radial;
            y /= radial;
            z /= radial;
            double lower = radial - hx * Math.abs(x) - hy * Math.abs(y) - hz * Math.abs(z);
            if (lower > radius + 1e-7) {
                return false;
            }
            // A supporting plane of |perpendicular| - tangent * along bounds the whole box from below.
            // The 0.08-wide core is a union with the cone, both clipped by the radius cap above.
            double cone = radial - tangent * along - hx * Math.abs(x - tangent * direction.x)
                    - hy * Math.abs(y - tangent * direction.y) - hz * Math.abs(z - tangent * direction.z);
            return lower <= 0.08 + 1e-7 || cone <= 1e-7;
        }
    }

    public static void setChangeListener(BiConsumer<Level, AABB> listener) {
        changed = listener;
    }

    private static Field field(Level level, boolean create) {
        synchronized (LEVELS) {
            Field found = LEVELS.get(level);
            if (found == null && create) {
                WeakReference<Level> reference = new WeakReference<>(level);
                found = new Field((source, beam, target) -> {
                    Level world = reference.get();
                    return world != null && visible(world, source, beam.origin, target.getCenter());
                });
                LEVELS.put(level, found);
            }
            return found;
        }
    }

    /** Returns only CLL's level. Do not read vanilla light here: this is called by the vanilla light query hook. */
    public static int sample(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return LightFieldNetwork.sample(level, pos);
        }
        Field field = field(level, false);
        return field == null ? 0 : field.sample(pos);
    }

    public static long revision(Level level) {
        Field field = field(level, false);
        return field == null ? 0 : field.revision;
    }

    public static void update(Level level, BlockPos source, Beam beam) {
        if (level.isClientSide) {
            return;
        }
        Field field = field(level, beam.active());
        if (field != null) {
            Change change = field.update(source, beam.active() ? beam : null);
            if (change != null) {
                changed.accept(level, change.bounds);
                if (level instanceof ServerLevel server) {
                    LightFieldNetwork.changed(server, change.bounds, change.weightsOnly);
                }
            }
        }
    }

    public static void remove(Level level, BlockPos source) {
        if (level.isClientSide) {
            return;
        }
        Field field = field(level, false);
        if (field != null) {
            Change change = field.update(source, null);
            if (change != null) {
                changed.accept(level, change.bounds);
                if (level instanceof ServerLevel server) {
                    LightFieldNetwork.changed(server, change.bounds);
                }
            }
        }
    }

    /** Only cached sections affected by this obstruction are forgotten and marked for redraw. */
    public static void invalidate(Level level, BlockPos obstruction) {
        Field field = field(level, false);
        if (field != null) {
            if (level instanceof ServerLevel server) {
                for (Entry entry : field.chunkEntries(ChunkPos.asLong(obstruction.getX() >> 4, obstruction.getZ() >> 4))) {
                    if (entry.beam.intersectsBlock(obstruction)) {
                        LightFieldNetwork.changed(server, entry.bounds);
                    }
                }
            }
            for (long section : field.invalidate(obstruction)) {
                changed.accept(level, sectionBounds(section));
            }
        }
    }

    public static void invalidateChunk(Level level, int x, int z) {
        Field field = field(level, false);
        if (field != null) {
            if (level instanceof ServerLevel server) {
                for (Entry entry : field.chunkEntries(ChunkPos.asLong(x, z))) {
                    LightFieldNetwork.changed(server, entry.bounds);
                }
            }
            for (long section : field.invalidateChunk(x, z)) {
                changed.accept(level, sectionBounds(section));
            }
        }
    }

    public static void clear(Level level) {
        Field field;
        synchronized (LEVELS) {
            field = LEVELS.remove(level);
        }
        if (field != null) {
            for (Entry entry : field.entries()) {
                changed.accept(level, entry.bounds);
            }
        }
    }

    public static void unloadChunk(Level level, ChunkPos chunk) {
        if (level.isClientSide) {
            LightFieldNetwork.discardChunk(level, chunk);
            return;
        }
        Field field = field(level, false);
        if (field != null) {
            invalidateChunk(level, chunk.x, chunk.z);
            for (Entry entry : field.entries()) {
                if (entry.source.getX() >> 4 == chunk.x && entry.source.getZ() >> 4 == chunk.z) {
                    remove(level, entry.source);
                }
            }
            field.forgetChunk(chunk);
        }
    }

    static int level(double sum) {
        return !(sum > 0) ? 0 : Mth.clamp((int) Math.round(sum), 1, 15);
    }

    record Entry(BlockPos source, Beam beam, AABB bounds) {
    }

    record Change(AABB bounds, boolean weightsOnly) {
    }

    record Snapshot(Field field, List<Entry> entries, long revision) {
        int sample(BlockPos pos) {
            return field.sample(pos, entries, revision);
        }
    }

    static Snapshot snapshot(Level level, long section) {
        Field field = field(level, false);
        return field == null ? null : field.snapshot(section);
    }

    static List<Entry> entries(Level level, long chunk) {
        Field field = field(level, false);
        return field == null ? List.of() : field.chunkEntries(chunk);
    }

    static void sectionChanged(Level level, long section) {
        changed.accept(level, sectionBounds(section));
    }

    @FunctionalInterface
    interface Visibility {
        boolean visible(BlockPos source, Beam beam, BlockPos target);
    }

    /** Immutable chunk lists make cache misses safe while fixtures change on the level thread. */
    static final class Field {
        final Long2ObjectOpenHashMap<Entry> fixtures = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<List<Entry>> chunks = new Long2ObjectOpenHashMap<>();
        final Long2ObjectLinkedOpenHashMap<CachedSection> sections = new Long2ObjectLinkedOpenHashMap<>();
        final Visibility visibility;
        volatile long revision;

        Field(Visibility visibility) {
            this.visibility = visibility;
        }

        synchronized List<Entry> entries() {
            return List.copyOf(fixtures.values());
        }

        synchronized List<Entry> chunkEntries(long chunk) {
            return chunks.getOrDefault(chunk, List.of());
        }

        synchronized Change update(BlockPos source, Beam beam) {
            long id = source.asLong();
            Entry before = fixtures.get(id);
            if (before == null && beam == null || before != null && before.beam.equals(beam)) {
                return null;
            }
            Entry next = beam == null ? null : new Entry(source.immutable(), beam, beam.bounds());
            AABB affected = before == null ? next.bounds : next == null ? before.bounds : before.bounds.minmax(next.bounds);
            boolean weightsOnly = before != null && next != null && before.beam.sameGeometry(next.beam);
            if (before != null && !weightsOnly) {
                index(before, false);
            }
            if (next == null) {
                fixtures.remove(id);
            } else {
                fixtures.put(id, next);
                index(next, true);
            }
            for (var cached : sections.long2ObjectEntrySet()) {
                if (sectionIntersects(cached.getLongKey(), affected)) {
                    Arrays.fill(cached.getValue().levels, (byte) -1);
                    if (!weightsOnly) {
                        cached.getValue().visibility.remove(id);
                    }
                }
            }
            revision++;
            return new Change(affected, weightsOnly);
        }

        private void index(Entry entry, boolean add) {
            AABB box = entry.bounds;
            for (int x = Mth.floor(box.minX) >> 4, endX = Mth.floor(box.maxX) >> 4; x <= endX; x++) {
                for (int z = Mth.floor(box.minZ) >> 4, endZ = Mth.floor(box.maxZ) >> 4; z <= endZ; z++) {
                    if (!entry.beam.intersects(x * 16.0, box.minY, z * 16.0, x * 16.0 + 16, box.maxY, z * 16.0 + 16)) {
                        continue;
                    }
                    long key = ChunkPos.asLong(x, z);
                    List<Entry> old = chunks.get(key);
                    List<Entry> list = old == null ? new ArrayList<>() : new ArrayList<>(old);
                    list.removeIf(e -> e.source.equals(entry.source));
                    if (add) {
                        list.add(entry);
                    }
                    if (list.isEmpty()) {
                        chunks.remove(key);
                    } else {
                        chunks.put(key, List.copyOf(list));
                    }
                }
            }
        }

        int sample(BlockPos pos) {
            return sample(pos, null, -1);
        }

        synchronized Snapshot snapshot(long section) {
            return new Snapshot(this, chunkEntries(ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section))), revision);
        }

        int sample(BlockPos pos, List<Entry> entries, long version) {
            long section = SectionPos.asLong(pos);
            int index = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
            CachedSection cached;
            synchronized (this) {
                if (entries == null) {
                    entries = chunkEntries(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                    version = revision;
                }
                if (entries.isEmpty()) {
                    return 0;
                }
                cached = sections.getAndMoveToLast(section);
                if (cached == null) {
                    if (sections.size() >= CACHE_SECTIONS) {
                        sections.removeFirst();
                    }
                    cached = new CachedSection();
                    sections.put(section, cached);
                } else if (version == revision && cached.levels[index] >= 0) {
                    return cached.levels[index];
                }
            }
            double sum = 0;
            for (Entry entry : entries) {
                double value = entry.beam.contribution(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
                if (value > 0 && visible(entry, pos, cached, section, index)) {
                    sum += value;
                    if (sum >= 15) {
                        break;
                    }
                }
            }
            int result = level(sum);
            synchronized (this) {
                if (version == revision && sections.get(section) == cached) {
                    cached.levels[index] = (byte) result;
                }
            }
            return result;
        }

        private boolean visible(Entry entry, BlockPos pos, CachedSection cached, long section, int index) {
            VisibilityMask mask = null;
            int word = index >> 6;
            long bit = 1L << index;
            synchronized (this) {
                Entry current = fixtures.get(entry.source.asLong());
                if (sections.get(section) == cached && current != null && current.beam.sameGeometry(entry.beam)) {
                    mask = cached.visibility.get(entry.source.asLong());
                    if (mask != null && !mask.beam.sameGeometry(entry.beam)) {
                        cached.visibility.remove(entry.source.asLong());
                        mask = null;
                    }
                    if (mask == null && cached.visibility.size() < VISIBILITY_SOURCES) {
                        mask = new VisibilityMask(entry.beam);
                        cached.visibility.put(entry.source.asLong(), mask);
                    }
                    if (mask != null && (mask.visited[word] & bit) != 0) {
                        return (mask.visible[word] & bit) != 0;
                    }
                }
            }
            boolean result = visibility.visible(entry.source, entry.beam, pos);
            if (mask != null) {
                synchronized (this) {
                    if (sections.get(section) == cached && cached.visibility.get(entry.source.asLong()) == mask) {
                        mask.visited[word] |= bit;
                        if (result) {
                            mask.visible[word] |= bit;
                        }
                    }
                }
            }
            return result;
        }

        synchronized List<Long> invalidate(BlockPos pos) {
            return forgetBeams(chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)), pos);
        }

        synchronized List<Long> invalidateChunk(int x, int z) {
            return forgetBeams(chunks.get(ChunkPos.asLong(x, z)), null);
        }

        private List<Long> forgetBeams(List<Entry> candidates, BlockPos obstruction) {
            List<Long> removed = new ArrayList<>();
            if (candidates != null) {
                for (Entry entry : candidates) {
                    if (obstruction == null || entry.beam.intersectsBlock(obstruction)) {
                        removed.addAll(forget(entry.bounds));
                        revision++;
                    }
                }
            }
            return removed;
        }

        private List<Long> forget(AABB bounds) {
            List<Long> removed = new ArrayList<>();
            sections.keySet().removeIf((java.util.function.LongPredicate) key -> {
                if (sectionIntersects(key, bounds)) {
                    removed.add(key);
                    return true;
                }
                return false;
            });
            return removed;
        }

        synchronized void forgetChunk(ChunkPos chunk) {
            sections.keySet().removeIf((java.util.function.LongPredicate) key -> SectionPos.x(key) == chunk.x && SectionPos.z(key) == chunk.z);
        }
    }

    static final class CachedSection {
        final byte[] levels = new byte[4096];
        final Long2ObjectOpenHashMap<VisibilityMask> visibility = new Long2ObjectOpenHashMap<>();

        CachedSection() {
            Arrays.fill(levels, (byte) -1);
        }
    }

    private static final class VisibilityMask {
        final Beam beam;
        final long[] visited = new long[64], visible = new long[64];

        VisibilityMask(Beam beam) {
            this.beam = beam;
        }
    }

    static boolean sectionIntersects(long section, AABB bounds) {
        int x = SectionPos.x(section) << 4, y = SectionPos.y(section) << 4, z = SectionPos.z(section) << 4;
        return x < bounds.maxX && x + 16 > bounds.minX && y < bounds.maxY && y + 16 > bounds.minY
                && z < bounds.maxZ && z + 16 > bounds.minZ;
    }

    private static AABB sectionBounds(long section) {
        int x = SectionPos.x(section) << 4, y = SectionPos.y(section) << 4, z = SectionPos.z(section) << 4;
        return new AABB(x, y, z, x + 16, y + 16, z + 16);
    }

    /** Vanilla transmission flags, not entity collision: bars/fences do not cast their tall collision proxy. */
    private static boolean visible(Level level, BlockPos source, Vec3 from, Vec3 to) {
        return BlockGetter.traverseBlocks(from, to, level, (world, pos) -> {
            if (pos.equals(source) || world.isOutsideBuildHeight(pos)) {
                return null;
            }
            var chunk = world.getChunkSource().getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                return Shapes.block().clip(from, to, pos);
            }
            BlockState state = chunk.getBlockState(pos);
            if (state.getLightBlock(world, pos) < 15 && !state.useShapeForLightOcclusion()) {
                return null;
            }
            return (state.useShapeForLightOcclusion() ? state.getOcclusionShape(world, pos) : Shapes.block())
                    .clip(from, to, pos);
        }, world -> null) == null;
    }
}
