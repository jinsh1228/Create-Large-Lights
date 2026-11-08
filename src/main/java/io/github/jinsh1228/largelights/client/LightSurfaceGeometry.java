package io.github.jinsh1228.largelights.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.neoforged.neoforge.client.model.IQuadTransformer;

/** Smaller visibility patches on a model's own faces, without enlarging thin blocks or moving slab surfaces. */
public final class LightSurfaceGeometry {
    private static final float PATCH_SIZE = 0.5f;
    private static final int MAX_STEPS = 4;
    private static final int CACHE_LIMIT = 4096;
    private static final Map<BakedQuad, List<BakedQuad>> CACHE = new IdentityHashMap<>();

    private LightSurfaceGeometry() {
    }

    public static List<BakedQuad> subdivide(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return quads;
        }
        List<BakedQuad> result = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            result.addAll(cached(quad));
        }
        return result;
    }

    public static synchronized void clear() {
        CACHE.clear();
    }

    private static synchronized List<BakedQuad> cached(BakedQuad quad) {
        List<BakedQuad> known = CACHE.get(quad);
        if (known != null) {
            return known;
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        List<BakedQuad> patches = split(quad);
        CACHE.put(quad, patches);
        return patches;
    }

    private static List<BakedQuad> split(BakedQuad quad) {
        int[] source = quad.getVertices();
        int across = steps(Math.max(length(source, 0, 1), length(source, 3, 2)));
        int down = steps(Math.max(length(source, 0, 3), length(source, 1, 2)));
        if (across == 1 && down == 1) {
            return List.of(quad);
        }
        List<BakedQuad> patches = new ArrayList<>(across * down);
        for (int y = 0; y < down; y++) {
            for (int x = 0; x < across; x++) {
                int[] vertices = source.clone();
                for (int corner = 0; corner < 4; corner++) {
                    float u = (float) (x + (corner == 1 || corner == 2 ? 1 : 0)) / across;
                    float v = (float) (y + (corner >= 2 ? 1 : 0)) / down;
                    int target = corner * IQuadTransformer.STRIDE;
                    // Keep packed normals and other model data from the corresponding original corner.
                    int nearest = v < 0.5f ? (u < 0.5f ? 0 : 1) : (u < 0.5f ? 3 : 2);
                    System.arraycopy(source, nearest * IQuadTransformer.STRIDE, vertices, target, IQuadTransformer.STRIDE);
                    for (int axis = 0; axis < 3; axis++) {
                        vertices[target + IQuadTransformer.POSITION + axis] = Float.floatToRawIntBits(
                                sample(source, IQuadTransformer.POSITION + axis, u, v));
                    }
                    for (int axis = 0; axis < 2; axis++) {
                        vertices[target + IQuadTransformer.UV0 + axis] = Float.floatToRawIntBits(
                                sample(source, IQuadTransformer.UV0 + axis, u, v));
                    }
                    if (IQuadTransformer.COLOR >= 0) {
                        vertices[target + IQuadTransformer.COLOR] = color(source, u, v);
                    }
                }
                patches.add(new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(),
                        quad.isShade(), quad.hasAmbientOcclusion()));
            }
        }
        return List.copyOf(patches);
    }

    private static int steps(double length) {
        // Vanilla faces need at most 2x2; also bound unusually large faces supplied by other models.
        return Math.max(1, Math.min(MAX_STEPS, (int) Math.ceil(length / PATCH_SIZE - 1e-6)));
    }

    private static double length(int[] data, int a, int b) {
        double squared = 0;
        for (int axis = 0; axis < 3; axis++) {
            int attribute = IQuadTransformer.POSITION + axis;
            double delta = Float.intBitsToFloat(data[a * IQuadTransformer.STRIDE + attribute])
                    - Float.intBitsToFloat(data[b * IQuadTransformer.STRIDE + attribute]);
            squared += delta * delta;
        }
        return Math.sqrt(squared);
    }

    private static float sample(int[] data, int attribute, float u, float v) {
        float a = Float.intBitsToFloat(data[attribute]);
        float b = Float.intBitsToFloat(data[IQuadTransformer.STRIDE + attribute]);
        float c = Float.intBitsToFloat(data[2 * IQuadTransformer.STRIDE + attribute]);
        float d = Float.intBitsToFloat(data[3 * IQuadTransformer.STRIDE + attribute]);
        return (a + (b - a) * u) * (1 - v) + (d + (c - d) * u) * v;
    }

    private static int color(int[] data, float u, float v) {
        int color = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = data[IQuadTransformer.COLOR] >>> shift & 255;
            int b = data[IQuadTransformer.STRIDE + IQuadTransformer.COLOR] >>> shift & 255;
            int c = data[2 * IQuadTransformer.STRIDE + IQuadTransformer.COLOR] >>> shift & 255;
            int d = data[3 * IQuadTransformer.STRIDE + IQuadTransformer.COLOR] >>> shift & 255;
            color |= Math.round((a + (b - a) * u) * (1 - v) + (d + (c - d) * u) * v) << shift;
        }
        return color;
    }
}
