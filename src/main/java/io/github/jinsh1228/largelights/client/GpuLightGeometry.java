package io.github.jinsh1228.largelights.client;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.systems.RenderSystem;

import io.github.jinsh1228.largelights.mixin.MultipartModelAccessor;
import io.github.jinsh1228.largelights.mixin.WeightedModelAccessor;
import io.github.jinsh1228.largelights.LightField;
import io.github.jinsh1228.largelights.LightReach;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.model.IQuadTransformer;

/** Section geometry shared by every projector. All mutation and GL work happens on the render thread. */
public final class GpuLightGeometry {
    private static final int STRIDE = 14;
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};
    private static final int[] SHARED_CORNERS = {0, 1, 2, 5};
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int[][] BOX_FACES = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 4, 7, 3},
            {1, 2, 6, 5}, {0, 1, 5, 4}, {3, 7, 6, 2}};
    private static final int[][] BOX_NORMALS = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0},
            {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
    private static final int MAX_METADATA = 4096;
    private static final long MAX_BYTES = 64L << 20;
    private static final int MAX_SECTION_FLOATS = (8 << 20) / Float.BYTES;
    private static final long BUILD_NANOS = 2_000_000;
    private static final Scene NOT_READY = new Scene(List.of(), false, 0);
    private static final Scene PENDING = new Scene(List.of(), false, 0, true);
    private static Cache cache;

    private GpuLightGeometry() {
    }

    public record Scene(List<Mesh> meshes, boolean ready, long revision, boolean pending) {
        public Scene(List<Mesh> meshes, boolean ready, long revision) {
            this(meshes, ready, revision, false);
        }
    }

    public static final class Mesh {
        private final BlockPos origin;
        private final boolean receives;
        private final Mesh shared;
        private int vao, vbo, ibo, vertices;
        private long bytes;
        private Map<Long, Span> blocks = Map.of();
        private AABB bounds;

        private Mesh(BlockPos origin, boolean receives) {
            this.origin = origin;
            this.receives = receives;
            shared = null;
        }

        private Mesh(BlockPos origin, Mesh shared) {
            this.origin = origin;
            receives = false;
            this.shared = shared;
        }

        public BlockPos origin() {
            return origin;
        }

        public boolean receives() {
            return receives;
        }

        /** World-space bounds of the uploaded vertices, including model offsets and overhang. */
        public AABB bounds() {
            return shared == null ? bounds : shared.bounds.move(origin);
        }

        public void draw() {
            Mesh data = shared == null ? this : shared;
            if (data.vao != 0) {
                GL30.glBindVertexArray(data.vao);
                data.drawRange(0, data.vertices);
            }
        }

        /** A fixture's own collision model must not stop the beam that starts inside it. */
        public void draw(BlockPos ignored) {
            Span span = blocks.get(ignored.asLong());
            if (span == null) {
                draw();
            } else if (vao != 0) {
                GL30.glBindVertexArray(vao);
                if (span.start > 0) drawRange(0, span.start);
                int end = span.start + span.count;
                if (end < vertices) drawRange(end, vertices - end);
            }
        }

        private void drawRange(int first, int count) {
            if (ibo == 0) GL11.glDrawArrays(GL11.GL_TRIANGLES, first, count);
            else GL11.glDrawElements(GL11.GL_TRIANGLES, count, GL11.GL_UNSIGNED_INT, first * (long) Integer.BYTES);
        }

        private void upload(FloatArrayList values) {
            if (values.isEmpty()) return;
            float[] vertices = values.elements();
            float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
            float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int vertex = 0; vertex < values.size(); vertex += STRIDE) {
                minX = Math.min(minX, vertices[vertex]); maxX = Math.max(maxX, vertices[vertex]);
                minY = Math.min(minY, vertices[vertex + 1]); maxY = Math.max(maxY, vertices[vertex + 1]);
                minZ = Math.min(minZ, vertices[vertex + 2]); maxZ = Math.max(maxZ, vertices[vertex + 2]);
            }
            bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).move(origin).inflate(.001);
            boolean indexed = packedBytes(values) < (long) values.size() * Float.BYTES;
            IntArrayList indices = indexed ? new IntArrayList(values.size() / STRIDE) : null;
            int packed = indexed ? compact(values, indices) : values.size();
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            this.vertices = values.size() / STRIDE;
            bytes = (long) packed * Float.BYTES + (indexed ? (long) indices.size() * Integer.BYTES : 0);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            FloatBuffer data = MemoryUtil.memAllocFloat(packed);
            try {
                data.put(values.elements(), 0, packed).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(data);
            }
            if (indexed) {
                ibo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
                IntBuffer elements = MemoryUtil.memAllocInt(indices.size());
                try {
                    elements.put(indices.elements(), 0, indices.size()).flip();
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, elements, GL15.GL_STATIC_DRAW);
                } finally {
                    MemoryUtil.memFree(elements);
                }
            }
            int[] widths = {3, 2, 3, 3, 3};
            int offset = 0;
            for (int attribute = 0; attribute < widths.length; attribute++) {
                GL20.glEnableVertexAttribArray(attribute);
                GL20.glVertexAttribPointer(attribute, widths[attribute], GL11.GL_FLOAT, false,
                        STRIDE * Float.BYTES, offset * (long) Float.BYTES);
                offset += widths[attribute];
            }
        }

        private void close() {
            if (shared != null) return;
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (vbo != 0) GL15.glDeleteBuffers(vbo);
            if (ibo != 0) GL15.glDeleteBuffers(ibo);
            vao = vbo = ibo = vertices = 0;
            bytes = 0;
            blocks = Map.of();
        }
    }

    public static void clear() {
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(GpuLightGeometry::clear);
            return;
        }
        if (cache != null) cache.close();
        cache = null;
    }

    public static void sectionChanged(long key) {
        if (cache == null) return;
        Entry entry = cache.sections.get(key);
        if (entry != null) {
            entry.generation++;
            entry.full = null;
            entry.failed = false;
        }
        int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
        cache.invalidateOpaque(x, y, z);
        AABB area = new AABB(x * 16.0 - 2, y * 16.0 - 2, z * 16.0 - 2,
                x * 16.0 + 18, y * 16.0 + 18, z * 16.0 + 18);
        cache.reach.invalidate(area);
        cache.memberships.entrySet().removeIf(member -> (entry == null || !member.getValue().entries.contains(entry))
                && Cache.intersects(member.getKey(), x, y, z) && !cache.hidden(member.getKey(), area));
        cache.epoch++;
    }

    public static void chunkChanged(int x, int z) {
        if (cache == null) return;
        cache.opaqueKey = Long.MIN_VALUE;
        cache.opaqueMask = null;
        cache.opaqueSections.keySet().removeIf((java.util.function.LongPredicate) key -> Math.abs(SectionPos.x(key) - x) <= 1
                && Math.abs(SectionPos.z(key) - z) <= 1);
        cache.reach.invalidate(new AABB(x * 16.0 - 1, -1e9, z * 16.0 - 1, x * 16.0 + 17, 1e9, z * 16.0 + 17));
        for (Entry entry : cache.sections.values()) {
            if (Math.abs(SectionPos.x(entry.key) - x) <= 1 && Math.abs(SectionPos.z(entry.key) - z) <= 1) {
                entry.generation++;
                entry.full = null;
                entry.failed = false;
            }
        }
        cache.memberships.entrySet().removeIf(member -> member.getKey().bounds.maxX >= (x - 1) * 16.0
                && member.getKey().bounds.minX <= (x + 2) * 16.0
                && member.getKey().bounds.maxZ >= (z - 1) * 16.0
                && member.getKey().bounds.minZ <= (z + 2) * 16.0);
        cache.epoch++;
    }

    /** Enqueue demand; this method never deletes or uploads a selected mesh. */
    public static Scene select(ClientLevel level, AABB bounds, Vec3 origin, Vec3 direction,
                               double tangent, double range, double radius) {
        if (Minecraft.getInstance().level != level) return NOT_READY;
        if (cache == null || cache.level != level) {
            // The engine calls rebuild before selecting, so a world switch is handled there.
            return PENDING;
        }
        LightField.Beam beam = new LightField.Beam(origin, direction, tangent, range, 1, 15, radius);
        bounds = cache.reach.bounds(BlockPos.containing(origin), beam, 2);
        range = cache.reach.limit(BlockPos.containing(origin), beam);
        Query query = new Query(bounds, origin, direction, tangent, range, radius);
        Membership membership = cache.memberships.get(query);
        if (membership == null) {
            membership = cache.find(query);
            if (membership == null) return NOT_READY;
            if (cache.memberships.size() >= 256) cache.memberships.remove(cache.memberships.keySet().iterator().next());
            cache.memberships.put(query, membership);
        }
        for (Entry entry : membership.entries) entry.used = cache.frame;
        if (membership.scene != null && membership.scene.ready && membership.epoch == cache.epoch) return membership.scene;
        List<Mesh> meshes = new ArrayList<>(membership.entries.size() + membership.unknown.size());
        boolean ready = membership.unknown.isEmpty() || cache.unknown.vao != 0;
        boolean supported = true;
        long revision = 0xcbf29ce484222325L;
        for (Entry entry : membership.entries) {
            if (entry.built != entry.generation) {
                ready = false;
                if (entry.failed || cache.frame < entry.retryAt) supported = false;
                if (!entry.queued && !entry.failed && cache.frame >= entry.retryAt && cache.pending.size() < MAX_METADATA) {
                    entry.queued = true;
                    cache.pending.addLast(entry);
                }
            }
            revision = (revision ^ entry.key) * 0x100000001b3L;
            revision = (revision ^ entry.generation) * 0x100000001b3L;
            if (entry.surface != null) meshes.add(entry.surface);
            if (entry.caster != null) meshes.add(entry.caster);
        }
        for (Mesh unknown : membership.unknown) {
            meshes.add(unknown);
            revision = (revision ^ unknown.origin.asLong()) * 0x100000001b3L;
        }
        membership.epoch = cache.epoch;
        return membership.scene = new Scene(List.copyOf(meshes), ready, revision, !ready && supported);
    }

    /** One incremental builder and one budget for the whole frame, rather than one scan per light. */
    public static void rebuild(long frame) {
        ClientLevel level = Minecraft.getInstance().level;
        if (cache == null || cache.level != level) {
            clear();
            if (level == null) return;
            cache = new Cache(level);
        }
        if (cache.frame == frame) return;
        cache.frame = frame;
        int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int savedBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            cache.rebuild();
        } finally {
            GL30.glBindVertexArray(savedVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedBuffer);
        }
    }

    public static long bytes() {
        return cache == null ? 0 : cache.bytes;
    }

    private record Query(AABB bounds, Vec3 origin, Vec3 direction, double tangent, double range, double radius) {
    }

    private record Span(int start, int count) {
    }

    private static final class Membership {
        final List<Entry> entries;
        final List<Mesh> unknown;
        Scene scene;
        long epoch;

        Membership(List<Entry> entries, List<Mesh> unknown) {
            this.entries = entries;
            this.unknown = unknown;
        }
    }

    private static final class Entry {
        final long key;
        long generation = 1, built, used = Long.MIN_VALUE, retryAt;
        long bytes;
        Mesh surface, caster;
        Boolean full;
        boolean queued, failed;

        Entry(long key) {
            this.key = key;
        }
    }

    private static final class Cache {
        final ClientLevel level;
        final Map<Long, Entry> sections = new LinkedHashMap<>();
        final Map<Query, Membership> memberships = new LinkedHashMap<>();
        final Map<BakedModel, Boolean> culledModels = new IdentityHashMap<>();
        final Map<BlockState, Boolean> fullStates = new HashMap<>();
        final Map<BlockState, Boolean> opaqueStates = new HashMap<>();
        final LightReach reach = new LightReach(this::opaque);
        final Long2ObjectLinkedOpenHashMap<long[]> opaqueSections = new Long2ObjectLinkedOpenHashMap<>();
        long opaqueKey = Long.MIN_VALUE;
        long[] opaqueMask;
        final ArrayDeque<Entry> pending = new ArrayDeque<>();
        final Mesh unknown = new Mesh(BlockPos.ZERO, false);
        long frame = Long.MIN_VALUE, bytes, epoch;
        boolean needsUnknown;
        Build building;

        Cache(ClientLevel level) {
            this.level = level;
        }

        private LevelChunk chunk(int x, int z) {
            return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        }

        private LevelChunkSection section(long key) {
            int y = SectionPos.y(key) - level.getMinSection();
            LevelChunk chunk = y >= 0 && y < level.getSectionsCount() ? chunk(SectionPos.x(key), SectionPos.z(key)) : null;
            return chunk == null ? null : chunk.getSection(y);
        }

        private Membership find(Query query) {
            AABB bounds = query.bounds;
            LightField.Beam beam = new LightField.Beam(query.origin, query.direction, query.tangent, query.range, 1, 15, query.radius);
            BlockPos source = BlockPos.containing(query.origin);
            int minX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minX));
            int maxX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxX));
            int minZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minZ));
            int maxZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxZ));
            int minY = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord((int) Math.floor(bounds.minY)));
            int maxY = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxY)));
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) * (maxY - minY + 1) > 32768) return null;
            List<Entry> entries = new ArrayList<>();
            List<Mesh> missing = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    LevelChunk chunk = chunk(x, z);
                    boolean intersects = false;
                    for (int y = minY; y <= maxY; y++) {
                        if (!intersects(query, x, y, z)) continue;
                        if (reach.hidden(source, beam, new AABB(x * 16.0 - 2, y * 16.0 - 2, z * 16.0 - 2,
                                x * 16.0 + 18, y * 16.0 + 18, z * 16.0 + 18))) continue;
                        intersects = true;
                        if (chunk == null || chunk.getSection(y - level.getMinSection()).hasOnlyAir()) continue;
                        long key = SectionPos.asLong(x, y, z);
                        Entry entry = sections.get(key);
                        if (entry == null) {
                            if (sections.size() >= MAX_METADATA) {
                                // Eviction is deferred to the next frame, before any selected VAOs are used.
                                return null;
                            }
                            entry = new Entry(key);
                            sections.put(key, entry);
                        }
                        entries.add(entry);
                    }
                    if (chunk == null && intersects) {
                        needsUnknown = true;
                        missing.add(new Mesh(new BlockPos(x << 4, level.getMinBuildHeight(), z << 4), unknown));
                    }
                }
            }
            return new Membership(List.copyOf(entries), List.copyOf(missing));
        }

        private static boolean intersects(Query query, int x, int y, int z) {
            double dx = x * 16.0 + 8 - query.origin.x;
            double dy = y * 16.0 + 8 - query.origin.y;
            double dz = z * 16.0 + 8 - query.origin.z;
            double along = dx * query.direction.x + dy * query.direction.y + dz * query.direction.z;
            double radius = 17.321; // Section plus two blocks of model overhang, conservatively bounded by a sphere.
            if (along + radius <= 0 || along - radius >= query.range) return false;
            double radial = Math.sqrt(Math.max(0, dx * dx + dy * dy + dz * dz - along * along));
            return radial <= query.radius + radius
                    && radial <= Math.max(0, along) * query.tangent + radius * Math.sqrt(1 + query.tangent * query.tangent);
        }

        private void rebuild() {
            long until = System.nanoTime() + BUILD_NANOS;
            if (needsUnknown && unknown.vao == 0) {
                FloatArrayList values = new FloatArrayList();
                box(values, 0, 0, 0, 16, level.getHeight(), 16);
                unknown.upload(values);
                bytes += unknown.bytes;
                epoch++;
            }
            // Retire old metadata and buffers only when no projector requested them in the previous frame.
            if (sections.size() >= MAX_METADATA) {
                var iterator = sections.values().iterator();
                while (iterator.hasNext() && sections.size() >= MAX_METADATA - 64) {
                    Entry entry = iterator.next();
                    if (entry.used < frame - 1 && (building == null || building.entry != entry)) {
                        release(entry);
                        iterator.remove();
                        memberships.clear();
                    }
                }
            }
            while (System.nanoTime() < until) {
                if (building == null) {
                    Entry entry = pending.pollFirst();
                    if (entry == null) break;
                    if (sections.get(entry.key) != entry || entry.used < frame - 1) {
                        entry.queued = false;
                        continue;
                    }
                    if (entry.built == entry.generation) {
                        entry.queued = false;
                        continue;
                    }
                    building = new Build(this, entry);
                }
                if (building.generation != building.entry.generation) {
                    building.entry.queued = false;
                    building = null;
                    continue;
                }
                building.advance(64);
                if (building.done()) {
                    finish(building);
                    building = null;
                }
            }
        }

        private void finish(Build build) {
            Entry entry = build.entry;
            entry.queued = false;
            if (build.tooLarge) {
                entry.failed = true;
                return;
            }
            long size = packedBytes(build.surface) + packedBytes(build.caster);
            for (Entry old : sections.values()) {
                if (bytes - entry.bytes + size <= MAX_BYTES) break;
                if (old != entry && old.used < frame - 1) release(old);
            }
            if (bytes - entry.bytes + size > MAX_BYTES) {
                entry.retryAt = frame + 60;
                return;
            }
            release(entry);
            if (!build.surface.isEmpty()) {
                entry.surface = new Mesh(build.origin, true);
                entry.surface.upload(build.surface);
            }
            if (!build.caster.isEmpty()) {
                entry.caster = new Mesh(build.origin, false);
                entry.caster.upload(build.caster);
                entry.caster.blocks = Map.copyOf(build.casterBlocks);
            }
            entry.bytes = size;
            bytes += size;
            entry.built = build.generation;
        }

        private void release(Entry entry) {
            if (entry.surface != null) entry.surface.close();
            if (entry.caster != null) entry.caster.close();
            entry.surface = entry.caster = null;
            bytes -= entry.bytes;
            entry.bytes = 0;
            entry.built = 0;
            epoch++;
        }

        private boolean full(long key) {
            LevelChunkSection section = section(key);
            if (section == null || section.hasOnlyAir()) return false;
            Entry entry = sections.get(key);
            if (entry != null && entry.full != null) return entry.full;
            boolean full = !section.maybeHas(state -> !fullStateCached(state));
            if (entry != null) entry.full = full;
            return full;
        }

        private boolean fullState(BlockState state) {
            if (state.getBlock().hasDynamicShape() || !state.isSolidRender(level, BlockPos.ZERO)) return false;
            return culled(Minecraft.getInstance().getBlockRenderer().getBlockModel(state));
        }

        private boolean fullStateCached(BlockState state) {
            Boolean known = fullStates.get(state);
            if (known != null) return known;
            boolean full = fullState(state);
            if (fullStates.size() >= 8192) fullStates.clear();
            fullStates.put(state, full);
            return full;
        }

        private boolean hidden(Query query, AABB area) {
            return reach.hidden(BlockPos.containing(query.origin),
                    new LightField.Beam(query.origin, query.direction, query.tangent, query.range, 1, 15, query.radius), area);
        }

        private void invalidateOpaque(int x, int y, int z) {
            opaqueKey = Long.MIN_VALUE;
            opaqueMask = null;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                opaqueSections.remove(SectionPos.asLong(x + dx, y + dy, z + dz));
        }

        private boolean opaque(BlockPos pos) {
            if (level.isOutsideBuildHeight(pos)) return false;
            long key = SectionPos.asLong(pos);
            if (key != opaqueKey) {
                opaqueMask = opaqueSections.getAndMoveToLast(key);
                if (opaqueMask == null) {
                    if (opaqueSections.size() >= 512) opaqueSections.removeFirst();
                    opaqueMask = new long[128];
                    opaqueSections.put(key, opaqueMask);
                }
                opaqueKey = key;
            }
            int cell = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15, word = cell >> 6;
            long bit = 1L << cell;
            if ((opaqueMask[word] & bit) == 0) {
                boolean full = opaqueModel(pos);
                opaqueMask[word] |= bit;
                if (full) opaqueMask[word + 64] |= bit;
            }
            return (opaqueMask[word + 64] & bit) != 0;
        }

        /** Only proven unit-cube models with no transparent texels may close the work volume. */
        private boolean opaqueModel(BlockPos pos) {
            if (level.isOutsideBuildHeight(pos)) return false;
            LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) return false;
            BlockState state = chunk.getBlockState(pos);
            if (state.hasBlockEntity() || state.getBlock().hasDynamicShape()
                    || !state.isSolidRender(level, pos) || !state.isCollisionShapeFullBlock(level, pos)
                    || state.getOffset(level, pos).lengthSqr() != 0) return false;
            Boolean known = opaqueStates.get(state);
            if (known != null) return known;
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            boolean opaque = model.getClass() == SimpleBakedModel.class;
            RandomSource random = RandomSource.create(42);
            for (Direction side : DIRECTIONS) {
                if (!opaque) break;
                opaque = model.getQuads(state, side, random).stream().anyMatch(quad -> fullFace(quad, side));
            }
            if (opaqueStates.size() >= 8192) opaqueStates.clear();
            opaqueStates.put(state, opaque);
            return opaque;
        }

        private static boolean fullFace(BakedQuad quad, Direction side) {
            int axis = side.getAxis().ordinal(), a = (axis + 1) % 3, b = (axis + 2) % 3, corners = 0, order = 0;
            int[] data = quad.getVertices();
            float plane = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
                if (Float.intBitsToFloat(data[base + axis]) != plane) return false;
                float u = Float.intBitsToFloat(data[base + a]), v = Float.intBitsToFloat(data[base + b]);
                if (u != 0 && u != 1 || v != 0 && v != 1) return false;
                int corner = (int) u | (int) v << 1;
                corners |= 1 << corner;
                order |= corner << (vertex * 2);
            }
            if (corners != 15 || ((order ^ order >> 4) & 3) != 3 || ((order >> 2 ^ order >> 6) & 3) != 3) return false;
            var sprite = quad.getSprite();
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * IQuadTransformer.STRIDE + IQuadTransformer.UV0;
                float u = Float.intBitsToFloat(data[base]), v = Float.intBitsToFloat(data[base + 1]);
                if (u < sprite.getU0() || u > sprite.getU1() || v < sprite.getV0() || v > sprite.getV1()) return false;
            }
            for (int frame : sprite.contents().getUniqueFrames().toArray())
                for (int y = 0; y < sprite.contents().height(); y++)
                    for (int x = 0; x < sprite.contents().width(); x++)
                        if (sprite.getPixelRGBA(frame, x, y) >>> 24 < 26) return false;
            return true;
        }

        private boolean culled(BakedModel model) {
            Boolean known = culledModels.get(model);
            if (known != null) return known;
            boolean result = false;
            if (model.getClass() == SimpleBakedModel.class) {
                result = model.getQuads(null, null, RandomSource.create(42)).isEmpty();
            } else if (model.getClass() == MultiPartBakedModel.class) {
                result = ((MultipartModelAccessor) model).createLargeLights$selectors().stream()
                        .allMatch(part -> culled(part.getRight()));
            } else if (model.getClass() == WeightedBakedModel.class) {
                result = ((WeightedModelAccessor) model).createLargeLights$models().stream()
                        .allMatch(part -> culled(part.data()));
            }
            if (culledModels.size() >= 8192) culledModels.clear();
            culledModels.put(model, result);
            return result;
        }

        void close() {
            sections.values().forEach(this::release);
            unknown.close();
            building = null;
        }
    }

    private static final class Build {
        final Cache cache;
        final Entry entry;
        final long generation;
        final BlockPos origin;
        final FloatArrayList surface = new FloatArrayList(), caster = new FloatArrayList();
        final Map<Long, Span> casterBlocks = new HashMap<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        final LevelChunkSection blocks;
        int cursor;
        boolean tooLarge;

        Build(Cache cache, Entry entry) {
            this.cache = cache;
            this.entry = entry;
            generation = entry.generation;
            origin = new BlockPos(SectionPos.x(entry.key) << 4, SectionPos.y(entry.key) << 4, SectionPos.z(entry.key) << 4);
            blocks = cache.section(entry.key);
            if (blocks == null) cursor = 4096;
            if (cache.full(entry.key)) {
                boolean enclosed = true;
                for (Direction side : DIRECTIONS) {
                    long adjacent = SectionPos.asLong(SectionPos.x(entry.key) + side.getStepX(),
                            SectionPos.y(entry.key) + side.getStepY(), SectionPos.z(entry.key) + side.getStepZ());
                    if (!cache.full(adjacent)) {
                        enclosed = false;
                        break;
                    }
                }
                if (enclosed) cursor = 4096;
            }
        }

        boolean done() {
            return cursor >= 4096 || tooLarge;
        }

        void advance(int budget) {
            for (int end = Math.min(4096, cursor + budget); cursor < end && !tooLarge; cursor++) {
                int x = cursor & 15, z = cursor >> 4 & 15, y = cursor >> 8;
                pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                BlockState state = blocks.getBlockState(x, y, z);
                if (state.isAir()) continue;
                if (state.getRenderShape() != RenderShape.MODEL) {
                    int start = caster.size() / STRIDE;
                    for (AABB box : state.getCollisionShape(cache.level, pos, CollisionContext.empty()).toAabbs()) {
                        if (surface.size() + caster.size() + 36 * STRIDE > MAX_SECTION_FLOATS) {
                            tooLarge = true;
                            break;
                        }
                        box(caster, x + box.minX, y + box.minY, z + box.minZ,
                                x + box.maxX, y + box.maxY, z + box.maxZ);
                    }
                    if (caster.size() / STRIDE > start) {
                        casterBlocks.put(pos.asLong(), new Span(start, caster.size() / STRIDE - start));
                    }
                } else {
                    var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
                    boolean general = !cache.culled(model);
                    int visible = 0;
                    for (Direction side : DIRECTIONS) {
                        neighbor.setWithOffset(pos, side);
                        if (Block.shouldRenderFace(state, cache.level, pos, side, neighbor)) visible |= 1 << side.get3DDataValue();
                    }
                    if (visible == 0 && !general) continue;
                    Vec3 offset = state.getOffset(cache.level, pos).add(x, y, z);
                    for (int side = 0; side <= 6; side++) {
                        if (side == 6 ? !general : (visible & 1 << side) == 0) continue;
                        for (BakedQuad quad : LightModelSnapshots.quads(cache.level, pos, state,
                                side == 6 ? null : Direction.from3DDataValue(side))) {
                            if (surface.size() + caster.size() + TRIANGLES.length * STRIDE > MAX_SECTION_FLOATS) {
                                tooLarge = true;
                                break;
                            }
                            int tint = quad.isTinted() ? Minecraft.getInstance().getBlockColors()
                                    .getColor(state, cache.level, pos, quad.getTintIndex()) : 0xFFFFFF;
                            append(surface, quad, offset, tint);
                        }
                        if (tooLarge) break;
                    }
                }
                if (surface.size() + caster.size() > MAX_SECTION_FLOATS) tooLarge = true;
            }
        }
    }

    /** Exact sharing within a quad only: non-planar faces retain their distinct per-triangle attributes. */
    static int compact(FloatArrayList values, IntArrayList indices) {
        float[] data = values.elements();
        int packed = 0;
        for (int base = 0; base < values.size(); base += 6 * STRIDE) {
            boolean shared = shared(data, base);
            int first = packed / STRIDE;
            for (int corner = 0; corner < (shared ? 4 : 6); corner++) {
                int source = base + (shared ? SHARED_CORNERS[corner] : corner) * STRIDE;
                System.arraycopy(data, source, data, packed, STRIDE);
                packed += STRIDE;
            }
            for (int corner = 0; corner < 6; corner++) indices.add(first + (shared ? TRIANGLES[corner] : corner));
        }
        return packed;
    }

    static long packedBytes(FloatArrayList values) {
        long vertices = 0;
        for (int base = 0; base < values.size(); base += 6 * STRIDE) vertices += shared(values.elements(), base) ? 4 : 6;
        return Math.min((long) values.size() * Float.BYTES,
                vertices * STRIDE * Float.BYTES + (long) values.size() / STRIDE * Integer.BYTES);
    }

    private static boolean shared(float[] data, int base) {
        for (int attribute = 0; attribute < STRIDE; attribute++) {
            if (Float.floatToRawIntBits(data[base + attribute]) != Float.floatToRawIntBits(data[base + 3 * STRIDE + attribute])
                    || Float.floatToRawIntBits(data[base + 2 * STRIDE + attribute]) != Float.floatToRawIntBits(data[base + 4 * STRIDE + attribute])) return false;
        }
        return true;
    }

    private static void append(FloatArrayList out, BakedQuad quad, Vec3 offset, int tint) {
        int[] data = quad.getVertices();
        int first = IQuadTransformer.POSITION;
        double ax = Float.intBitsToFloat(data[first + IQuadTransformer.STRIDE]) - Float.intBitsToFloat(data[first]);
        double ay = Float.intBitsToFloat(data[first + IQuadTransformer.STRIDE + 1]) - Float.intBitsToFloat(data[first + 1]);
        double az = Float.intBitsToFloat(data[first + IQuadTransformer.STRIDE + 2]) - Float.intBitsToFloat(data[first + 2]);
        double bx = Float.intBitsToFloat(data[first + IQuadTransformer.STRIDE * 3]) - Float.intBitsToFloat(data[first]);
        double by = Float.intBitsToFloat(data[first + IQuadTransformer.STRIDE * 3 + 1]) - Float.intBitsToFloat(data[first + 1]);
        double bz = Float.intBitsToFloat(data[first + IQuadTransformer.STRIDE * 3 + 2]) - Float.intBitsToFloat(data[first + 2]);
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        Direction facing = quad.getDirection();
        double sign = nx * facing.getStepX() + ny * facing.getStepY() + nz * facing.getStepZ() < 0 ? -1 : 1;
        float normalX = length < 1e-12 ? facing.getStepX() : (float) (nx / length * sign);
        float normalY = length < 1e-12 ? facing.getStepY() : (float) (ny / length * sign);
        float normalZ = length < 1e-12 ? facing.getStepZ() : (float) (nz / length * sign);
        for (int triangle = 0; triangle < TRIANGLES.length; triangle += 3) {
            Vec3 plane = triangleNormal(data, TRIANGLES[triangle], TRIANGLES[triangle + 1], TRIANGLES[triangle + 2]);
            for (int corner = 0; corner < 3; corner++) {
                int base = TRIANGLES[triangle + corner] * IQuadTransformer.STRIDE;
                for (int axis = 0; axis < 3; axis++) out.add((float) (Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + axis])
                        + (axis == 0 ? offset.x : axis == 1 ? offset.y : offset.z)));
                out.add(Float.intBitsToFloat(data[base + IQuadTransformer.UV0]));
                out.add(Float.intBitsToFloat(data[base + IQuadTransformer.UV0 + 1]));
                int normal = data[base + IQuadTransformer.NORMAL];
                if (normal == 0) {
                    out.add(normalX); out.add(normalY); out.add(normalZ);
                } else {
                    out.add((byte) normal / 127f); out.add((byte) (normal >> 8) / 127f); out.add((byte) (normal >> 16) / 127f);
                }
                int rgba = data[base + IQuadTransformer.COLOR];
                out.add((rgba & 255) / 255f * (tint >> 16 & 255) / 255f);
                out.add((rgba >> 8 & 255) / 255f * (tint >> 8 & 255) / 255f);
                out.add((rgba >> 16 & 255) / 255f * (tint & 255) / 255f);
                out.add((float) plane.x); out.add((float) plane.y); out.add((float) plane.z);
            }
        }
    }

    private static Vec3 triangleNormal(int[] vertices, int a, int b, int c) {
        int ia = a * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
        int ib = b * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
        int ic = c * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
        double ax = Float.intBitsToFloat(vertices[ib]) - Float.intBitsToFloat(vertices[ia]);
        double ay = Float.intBitsToFloat(vertices[ib + 1]) - Float.intBitsToFloat(vertices[ia + 1]);
        double az = Float.intBitsToFloat(vertices[ib + 2]) - Float.intBitsToFloat(vertices[ia + 2]);
        double bx = Float.intBitsToFloat(vertices[ic]) - Float.intBitsToFloat(vertices[ia]);
        double by = Float.intBitsToFloat(vertices[ic + 1]) - Float.intBitsToFloat(vertices[ia + 1]);
        double bz = Float.intBitsToFloat(vertices[ic + 2]) - Float.intBitsToFloat(vertices[ia + 2]);
        double x = ay * bz - az * by, y = az * bx - ax * bz, z = ax * by - ay * bx;
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1e-12 ? Vec3.ZERO : new Vec3(x / length, y / length, z / length);
    }

    private static void box(FloatArrayList out, double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] points = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
                {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        for (int side = 0; side < BOX_FACES.length; side++) {
            int[] face = BOX_FACES[side];
            int[] normal = BOX_NORMALS[side];
            for (int index : TRIANGLES) {
                double[] point = points[face[index]];
                for (double coordinate : point) out.add((float) coordinate);
                out.add(0); out.add(0);
                out.add(0); out.add(1); out.add(0);
                out.add(1); out.add(1); out.add(1);
                out.add(normal[0]); out.add(normal[1]); out.add(normal[2]);
            }
        }
    }
}
