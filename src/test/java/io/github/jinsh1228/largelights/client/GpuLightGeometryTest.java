package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

class GpuLightGeometryTest {
    @Test
    void indexedVerticesReproduceEveryOriginalAttributeBitForMixedModFaces() {
        Random random = new Random(421);
        FloatArrayList input = new FloatArrayList();
        int expected = 0;
        for (int quad = 0; quad < 128; quad++) {
            float[][] corners = new float[6][14];
            for (float[] corner : corners) for (int axis = 0; axis < 14; axis++) corner[axis] = random.nextFloat() * 40 - 20;
            if (quad % 3 != 0) {
                corners[3] = corners[0].clone();
                corners[4] = corners[2].clone();
                expected += 4;
            } else {
                expected += 6;
            }
            for (float[] corner : corners) input.addElements(input.size(), corner);
        }
        float[] original = input.toFloatArray();
        IntArrayList indices = new IntArrayList();
        int packed = GpuLightGeometry.compact(input, indices);
        assertEquals(expected * 14, packed);
        assertEquals(original.length / 14, indices.size());
        for (int vertex = 0; vertex < indices.size(); vertex++) {
            for (int attribute = 0; attribute < 14; attribute++) {
                assertEquals(Float.floatToRawIntBits(original[vertex * 14 + attribute]),
                        Float.floatToRawIntBits(input.getFloat(indices.getInt(vertex) * 14 + attribute)));
            }
        }
    }

    @Test
    void distinctPlaneNormalsAndSignedZeroDoNotGetMerged() {
        FloatArrayList input = new FloatArrayList(new float[6 * 14]);
        input.set(3 * 14 + 11, -0.0f);
        input.set(4 * 14 + 13, 1);
        IntArrayList indices = new IntArrayList();
        assertEquals(6 * 14, GpuLightGeometry.compact(input, indices));
        for (int vertex = 0; vertex < 6; vertex++) assertEquals(vertex, indices.getInt(vertex));
    }

    @Test
    void bufferBudgetCountsIndicesAndNeverExceedsTheOriginalStorage() {
        FloatArrayList shared = new FloatArrayList(new float[6 * 14]);
        assertEquals(4L * 14 * Float.BYTES + 6L * Integer.BYTES, GpuLightGeometry.packedBytes(shared));
        shared.set(3 * 14 + 11, 1);
        assertEquals(6L * 14 * Float.BYTES, GpuLightGeometry.packedBytes(shared));
    }
}
