package io.github.jinsh1228.largelights.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.systems.RenderSystem;

import io.github.jinsh1228.largelights.mixin.MultipartModelAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;

/** The same position-aware model geometry for receiving light and casting shadows. */
public final class LightModelSnapshots {
    private static final int MAX_STATES = 4096;
    private static final int MAX_POSITIONS = 8192;
    private static final int MAX_SECTIONS = 128;
    private static final Snapshot EMPTY = new Snapshot(java.util.Collections.nCopies(7, List.of()), List.of());
    private static volatile Cache cache = new Cache(null);
    private static final Map<Object, Watch> watches = new WeakHashMap<>();
    private static long changes;

    private static final class Watch {
        final AABB bounds;
        long revision;
        Watch(AABB bounds) { this.bounds = bounds; revision = ++changes; }
    }

    static long watch(Object owner, AABB bounds) {
        Watch known = watches.get(owner);
        if (known == null || !known.bounds.equals(bounds)) {
            known = new Watch(bounds);
            watches.put(owner, known);
        }
        return known.revision;
    }

    private static void changed(AABB area) {
        for (Watch watch : watches.values()) if (watch.bounds.intersects(area)) watch.revision = ++changes;
    }

    private LightModelSnapshots() {
    }

    public static void clear() {
        cache = new Cache(null);
        watches.clear();
    }

    public static void sectionChanged(long key) {
        int x = SectionPos.x(key) * 16, y = SectionPos.y(key) * 16, z = SectionPos.z(key) * 16;
        changed(new AABB(x - 2, y - 2, z - 2, x + 18, y + 18, z + 18));
        cache.remove(key);
        GpuLightGeometry.sectionChanged(key);
    }

    public static void chunkChanged(int x, int z) {
        changed(new AABB(x * 16.0 - 2, -1e9, z * 16.0 - 2, x * 16.0 + 18, 1e9, z * 16.0 + 18));
        GpuLightGeometry.chunkChanged(x, z);
        Cache current = cache;
        for (long key : current.sections.keySet()) {
            if (Math.abs(SectionPos.x(key) - x) <= 1 && Math.abs(SectionPos.z(key) - z) <= 1) {
                current.remove(key);
            }
        }
    }

    public static List<BakedQuad> quads(Level level, BlockPos pos, BlockState state, Direction side) {
        return snapshot(level, pos, state).sides.get(side == null ? 6 : side.get3DDataValue());
    }

    static List<ModelLightOcclusion.Quad> occluders(Level level, BlockPos pos, BlockState state) {
        return snapshot(level, pos, state).occluders;
    }

    private static Snapshot snapshot(Level level, BlockPos pos, BlockState state) {
        Cache current = cache;
        if (current.level == level) {
            Snapshot shared = current.models.get(state);
            if (shared != null) return shared;
            Section section = current.sections.get(SectionPos.asLong(pos));
            Entry entry = section == null ? null : section.entries.get(pos.asLong());
            if (entry != null && entry.state == state) return entry.snapshot;
        }
        // ModelDataManager and native sprite pixels belong to the client thread.
        // Submit one entire model miss, never seven side misses or a task while holding a map lock.
        if (!RenderSystem.isOnRenderThread()) {
            BlockPos at = pos.immutable();
            return Minecraft.getInstance().submit(() -> snapshot(level, at, state)).join();
        }
        if (Minecraft.getInstance().level != level) return EMPTY;
        if (cache.level != level) cache = new Cache(level);
        return cache.load(pos, state);
    }

    private record Snapshot(List<List<BakedQuad>> sides, List<ModelLightOcclusion.Quad> occluders) {
    }

    private record Entry(BlockState state, Snapshot snapshot) {
    }

    /** Removing a section also retires its generation; workers retain only immutable snapshots. */
    private static final class Section {
        final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    }

    private static final class Cache {
        final Level level;
        final Map<BlockState, Snapshot> models = new ConcurrentHashMap<>();
        final Map<Long, Section> sections = new ConcurrentHashMap<>();
        final ArrayDeque<Long> order = new ArrayDeque<>();
        final Map<BakedModel, Boolean> staticModels = new IdentityHashMap<>();
        final Map<TextureAtlasSprite, ModelLightOcclusion.Alpha> sprites = new IdentityHashMap<>();
        int positions;

        Cache(Level level) {
            this.level = level;
        }

        void remove(long key) {
            Section removed = sections.remove(key);
            if (removed != null) {
                positions -= removed.entries.size();
                order.remove(key);
            }
        }

        Snapshot load(BlockPos pos, BlockState state) {
            var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            if (isStatic(model)) {
                Snapshot known = models.get(state);
                if (known != null) return known;
                if (models.size() >= MAX_STATES) models.clear();
                Snapshot baked = bake(model, pos, state);
                models.put(state, baked);
                return baked;
            }
            long key = SectionPos.asLong(pos);
            if (positions >= MAX_POSITIONS || sections.size() >= MAX_SECTIONS && !sections.containsKey(key)) {
                remove(order.getFirst());
            }
            Section section = sections.get(key);
            if (section == null) {
                section = new Section();
                sections.put(key, section);
                order.addLast(key);
            }
            Snapshot baked = bake(model, pos, state);
            if (section.entries.put(pos.asLong(), new Entry(state, baked)) == null) positions++;
            return baked;
        }

        private boolean isStatic(BakedModel model) {
            Boolean known = staticModels.get(model);
            if (known != null) return known;
            boolean fixed = model.getClass() == SimpleBakedModel.class;
            if (model.getClass() == MultiPartBakedModel.class) {
                fixed = ((MultipartModelAccessor) model).createLargeLights$selectors().stream()
                        .allMatch(part -> isStatic(part.getRight()));
            }
            if (staticModels.size() >= MAX_STATES) staticModels.clear();
            staticModels.put(model, fixed);
            return fixed;
        }

        private Snapshot bake(BakedModel model, BlockPos pos, BlockState state) {
            ModelData raw = level.getModelDataManager().getAt(pos);
            ModelData data = model.getModelData(level, pos, state, raw);
            long seed = state.getSeed(pos);
            RandomSource random = RandomSource.create(seed);
            var layers = model.getRenderTypes(state, random, data);
            List<List<BakedQuad>> sides = new ArrayList<>(7);
            List<ModelLightOcclusion.Quad> occluders = new ArrayList<>();
            for (int side = 0; side <= 6; side++) {
                Direction direction = side == 6 ? null : Direction.from3DDataValue(side);
                List<BakedQuad> quads = new ArrayList<>();
                for (var layer : layers) {
                    random.setSeed(seed);
                    quads.addAll(model.getQuads(state, direction, random, data, layer));
                }
                sides.add(List.copyOf(quads));
                for (BakedQuad quad : quads) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    ModelLightOcclusion.Alpha alpha = sprites.computeIfAbsent(sprite, Cache::pixels);
                    int[] vertices = quad.getVertices();
                    Vec3[] points = new Vec3[4];
                    float[] uv = new float[8];
                    for (int i = 0; i < 4; i++) {
                        int base = i * IQuadTransformer.STRIDE;
                        points[i] = new Vec3(Float.intBitsToFloat(vertices[base + IQuadTransformer.POSITION]),
                                Float.intBitsToFloat(vertices[base + IQuadTransformer.POSITION + 1]),
                                Float.intBitsToFloat(vertices[base + IQuadTransformer.POSITION + 2]));
                        uv[i * 2] = sprite.getUOffset(Float.intBitsToFloat(vertices[base + IQuadTransformer.UV0]));
                        uv[i * 2 + 1] = sprite.getVOffset(Float.intBitsToFloat(vertices[base + IQuadTransformer.UV0 + 1]));
                    }
                    occluders.add(new ModelLightOcclusion.Quad(points, uv, alpha));
                }
            }
            return new Snapshot(List.copyOf(sides), List.copyOf(occluders));
        }

        private static ModelLightOcclusion.Alpha pixels(TextureAtlasSprite sprite) {
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            boolean[] opaque = new boolean[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) opaque[y * width + x] = (sprite.getPixelRGBA(0, x, y) >>> 24) >= 26;
            }
            return (u, v) -> opaque[Mth.clamp((int) Math.floor(v * height), 0, height - 1) * width
                    + Mth.clamp((int) Math.floor(u * width), 0, width - 1)];
        }
    }
}
