package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class ModelLightOcclusionTest {
    private static final ModelLightOcclusion.Alpha OPAQUE = (u, v) -> true;

    @Test
    void ironBarTextureGapsTransmitAndBarsUseTheirActualPlane() {
        var bars = planeZ(0.5, 0, 1, 0, 1, (u, v) -> u >= 0.45 && u <= 0.55);
        var hit = ModelLightOcclusion.intersect(List.of(bars), new Vec3(0.5, 0.5, -1), new Vec3(0.5, 0.5, 1));
        assertNotNull(hit);
        assertEquals(0.75, hit.fraction(), 1e-9);
        assertNull(ModelLightOcclusion.intersect(List.of(bars), new Vec3(0.2, 0.5, -1), new Vec3(0.2, 0.5, 1)));
        // A point just before the model plane is lit even though it lies inside the wider collision shape.
        assertNull(ModelLightOcclusion.intersect(List.of(bars), new Vec3(0.5, 0.5, -1), new Vec3(0.5, 0.5, 0.499)));
    }

    @Test
    void fenceRailsDoNotOccludeTheirGapsOrTheAirAboveThem() {
        var bottom = planeZ(0.5, 0, 1, 0.375, 0.5625, OPAQUE);
        var top = planeZ(0.5, 0, 1, 0.75, 0.9375, OPAQUE);
        var post = planeZ(0.5, 0.375, 0.625, 0, 1, OPAQUE);
        var fence = List.of(bottom, top, post);
        assertNull(ModelLightOcclusion.intersect(fence, new Vec3(0.2, 0.65, -1), new Vec3(0.2, 0.65, 1)));
        assertNull(ModelLightOcclusion.intersect(fence, new Vec3(0.5, 1.25, -1), new Vec3(0.5, 1.25, 1)));
        assertNotNull(ModelLightOcclusion.intersect(fence, new Vec3(0.2, 0.8, -1), new Vec3(0.2, 0.8, 1)));
        assertNotNull(ModelLightOcclusion.intersect(fence, new Vec3(0.5, 0.65, -1), new Vec3(0.5, 0.65, 1)));
    }

    @Test
    void slabTopHeightsAreNotExpandedToAFullBlock() {
        for (double height : new double[]{0.5, 1}) {
            var slab = new ModelLightOcclusion.Quad(new Vec3[]{new Vec3(0, height, 0), new Vec3(1, height, 0),
                    new Vec3(1, height, 1), new Vec3(0, height, 1)}, uv(), OPAQUE);
            var hit = ModelLightOcclusion.intersect(List.of(slab), new Vec3(0.5, 2, 0.5), new Vec3(0.5, -1, 0.5));
            assertNotNull(hit);
            assertEquals(height, 2 - 3 * hit.fraction(), 1e-9);
            assertNull(ModelLightOcclusion.intersect(List.of(slab), new Vec3(0.5, 2, 0.5),
                    new Vec3(0.5, height + 0.001, 0.5)));
        }
    }

    @Test
    void nearestOpaqueQuadWinsRegardlessOfListOrderAndTransparentFrontFaces() {
        var front = planeZ(0.25, 0, 1, 0, 1, (u, v) -> false);
        var near = planeZ(0.5, 0, 1, 0, 1, OPAQUE);
        var far = planeZ(0.9, 0, 1, 0, 1, OPAQUE);
        Vec3 from = new Vec3(0.5, 0.5, 0);
        Vec3 to = new Vec3(0.5, 0.5, 1);
        var hit = ModelLightOcclusion.intersect(List.of(far, front, near), from, to);
        assertNotNull(hit);
        assertEquals(0.5, hit.fraction(), 1e-9);
        assertTrue(hit.normal().dot(to.subtract(from)) < 0);
        var back = ModelLightOcclusion.intersect(List.of(near), to, from);
        assertNotNull(back);
        assertEquals(0.5, back.fraction(), 1e-9);
        assertTrue(back.normal().dot(from.subtract(to)) < 0);
    }

    @Test
    void aStairUpperStepStillShadowsItsOwnLowerTread() {
        var lower = planeY(0.5, 0, 0.5);
        var upper = planeY(1, 0.5, 1);
        var stairs = List.of(lower, upper);
        Vec3 target = new Vec3(0.5, 0.501, 0.25);
        Vec3 behind = new Vec3(0.5, 1.5, 0.8);
        var shadow = ModelLightOcclusion.intersect(stairs, behind, target);
        assertNotNull(shadow);
        assertEquals(1, behind.lerp(target, shadow.fraction()).y, 1e-9);
        // The same tread is exposed from the front. Ignoring the whole target block would also incorrectly
        // expose it from behind, rather than retaining this genuine shadow from its own upper step.
        assertNull(ModelLightOcclusion.intersect(stairs, new Vec3(0.5, 1.5, -0.5), target));
    }

    @Test
    void parallelAndBeyondSegmentFacesDoNotOcclude() {
        var face = planeZ(0.5, 0, 1, 0, 1, OPAQUE);
        assertNull(ModelLightOcclusion.intersect(List.of(face), new Vec3(0, 0.5, 0), new Vec3(1, 0.5, 0)));
        assertNull(ModelLightOcclusion.intersect(List.of(face), new Vec3(0.5, 0.5, -1), new Vec3(0.5, 0.5, 0)));
    }

    @Test
    void skewedModFacesRetainTheirIntersectionAndTextureCoordinatesFromBothSides() {
        Random random = new Random(167);
        for (int i = 0; i < 512; i++) {
            Vec3 at = new Vec3(random.nextDouble() * 20 - 10, random.nextDouble() * 20 - 10, random.nextDouble() * 20 - 10);
            Vec3 across = new Vec3(1, random.nextDouble(), random.nextDouble());
            Vec3 down = new Vec3(random.nextDouble(), 1, -random.nextDouble());
            Vec3 normal = across.cross(down).normalize();
            double u = i % 2 == 0 ? .2 : .8, v = .6;
            Vec3 point = at.add(across.scale(u)).add(down.scale(v));
            var face = new ModelLightOcclusion.Quad(new Vec3[]{at, at.add(across), at.add(across).add(down), at.add(down)},
                    uv(), (s, t) -> Math.abs(s - u) < 1e-9 && Math.abs(t - v) < 1e-9);
            Vec3 from = point.subtract(normal.scale(3)), to = point.add(normal.scale(7));
            var forward = ModelLightOcclusion.intersect(List.of(face), from, to);
            var backward = ModelLightOcclusion.intersect(List.of(face), to, from);
            assertNotNull(forward);
            assertNotNull(backward);
            assertEquals(.3, forward.fraction(), 1e-9);
            assertEquals(.7, backward.fraction(), 1e-9);
            assertTrue(forward.normal().dot(normal) < 0);
            assertTrue(backward.normal().dot(normal) > 0);
        }
    }

    private static ModelLightOcclusion.Quad planeZ(double z, double x0, double x1, double y0, double y1,
                                                   ModelLightOcclusion.Alpha alpha) {
        return new ModelLightOcclusion.Quad(new Vec3[]{new Vec3(x0, y0, z), new Vec3(x1, y0, z),
                new Vec3(x1, y1, z), new Vec3(x0, y1, z)}, uv(), alpha);
    }

    private static ModelLightOcclusion.Quad planeY(double y, double z0, double z1) {
        return new ModelLightOcclusion.Quad(new Vec3[]{new Vec3(0, y, z0), new Vec3(1, y, z0),
                new Vec3(1, y, z1), new Vec3(0, y, z1)}, uv(), OPAQUE);
    }

    private static float[] uv() {
        return new float[]{0, 0, 1, 0, 1, 1, 0, 1};
    }
}
