package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.IQuadTransformer;

class LightSurfaceGeometryTest {
    @Test
    void slabTopPatchesStayAtHalfBlockHeightAndRetainTheWholeTexture() {
        BakedQuad original = quad(new float[][]{{0, 0.5f, 0}, {1, 0.5f, 0}, {1, 0.5f, 1}, {0, 0.5f, 1}}, Direction.UP);
        int[] before = original.getVertices().clone();
        List<BakedQuad> patches = LightSurfaceGeometry.subdivide(List.of(original));
        assertEquals(4, patches.size());
        double area = 0;
        for (BakedQuad patch : patches) {
            for (int vertex = 0; vertex < 4; vertex++) {
                float x = value(patch, vertex, IQuadTransformer.POSITION);
                float y = value(patch, vertex, IQuadTransformer.POSITION + 1);
                float z = value(patch, vertex, IQuadTransformer.POSITION + 2);
                assertEquals(0.5f, y);
                assertTrue(x >= 0 && x <= 1 && z >= 0 && z <= 1);
                assertEquals(0.2f + x * 0.4f, value(patch, vertex, IQuadTransformer.UV0), 1e-6);
                assertEquals(0.3f + z * 0.5f, value(patch, vertex, IQuadTransformer.UV0 + 1), 1e-6);
            }
            area += edge(patch, 0, 1) * edge(patch, 0, 3);
            assertTrue(edge(patch, 0, 1) <= 0.5 + 1e-6);
            assertTrue(edge(patch, 0, 3) <= 0.5 + 1e-6);
            assertEquals(original.getTintIndex(), patch.getTintIndex());
            assertSame(original.getDirection(), patch.getDirection());
            assertSame(original.getSprite(), patch.getSprite());
            assertFalse(patch.isShade());
            assertFalse(patch.hasAmbientOcclusion());
        }
        assertEquals(1, area, 1e-6);
        assertArrayEquals(before, original.getVertices());
    }

    @Test
    void ironBarPlaneKeepsItsThicknessAndFenceRailsAvoidUnnecessarySplits() {
        BakedQuad bar = quad(new float[][]{{0.5f, 0, 0}, {0.5f, 0, 1}, {0.5f, 1, 1}, {0.5f, 1, 0}}, Direction.EAST);
        List<BakedQuad> bars = LightSurfaceGeometry.subdivide(List.of(bar));
        assertEquals(4, bars.size());
        for (BakedQuad patch : bars) {
            for (int vertex = 0; vertex < 4; vertex++) {
                assertEquals(0.5f, value(patch, vertex, IQuadTransformer.POSITION));
            }
        }
        BakedQuad rail = quad(new float[][]{{0.4375f, 0.75f, 0}, {0.5625f, 0.75f, 0},
                {0.5625f, 0.75f, 0.5f}, {0.4375f, 0.75f, 0.5f}}, Direction.UP);
        assertSame(rail, LightSurfaceGeometry.subdivide(List.of(rail)).getFirst());
    }

    @Test
    void subdivisionsAreCachedAndOversizedModelsHaveBoundedWork() {
        BakedQuad original = quad(new float[][]{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, Direction.UP);
        List<BakedQuad> first = LightSurfaceGeometry.subdivide(List.of(original));
        assertSame(first.getFirst(), LightSurfaceGeometry.subdivide(List.of(original)).getFirst());
        LightSurfaceGeometry.clear();
        assertFalse(first.getFirst() == LightSurfaceGeometry.subdivide(List.of(original)).getFirst());

        BakedQuad oversized = quad(new float[][]{{0, 0, 0}, {100, 0, 0}, {100, 0, 100}, {0, 0, 100}}, Direction.UP);
        assertEquals(16, LightSurfaceGeometry.subdivide(List.of(oversized)).size());
    }

    private static BakedQuad quad(float[][] points, Direction direction) {
        int[] vertices = new int[IQuadTransformer.STRIDE * 4];
        float[][] uv = {{0.2f, 0.3f}, {0.6f, 0.3f}, {0.6f, 0.8f}, {0.2f, 0.8f}};
        for (int vertex = 0; vertex < 4; vertex++) {
            int start = vertex * IQuadTransformer.STRIDE;
            for (int axis = 0; axis < 3; axis++) {
                vertices[start + IQuadTransformer.POSITION + axis] = Float.floatToRawIntBits(points[vertex][axis]);
            }
            for (int axis = 0; axis < 2; axis++) {
                vertices[start + IQuadTransformer.UV0 + axis] = Float.floatToRawIntBits(uv[vertex][axis]);
            }
            vertices[start + IQuadTransformer.COLOR] = 0xFF336699;
        }
        return new BakedQuad(vertices, 3, direction, null, false, false);
    }

    private static float value(BakedQuad quad, int vertex, int attribute) {
        return Float.intBitsToFloat(quad.getVertices()[vertex * IQuadTransformer.STRIDE + attribute]);
    }

    private static double edge(BakedQuad quad, int first, int second) {
        double squared = 0;
        for (int axis = 0; axis < 3; axis++) {
            double difference = value(quad, first, IQuadTransformer.POSITION + axis)
                    - value(quad, second, IQuadTransformer.POSITION + axis);
            squared += difference * difference;
        }
        return Math.sqrt(squared);
    }
}
