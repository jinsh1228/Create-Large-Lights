package io.github.cmsfloodlights;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class AimTest {
    /** Vec3.directionFromRotation uses Mth's sine table, good to about 1e-4. */
    private static void assertClose(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-3, "x");
        assertEquals(expected.y, actual.y, 1e-3, "y");
        assertEquals(expected.z, actual.z, 1e-3, "z");
    }

    @Test
    void pointsAsAnEntityLooks() {
        for (float yaw = -180; yaw < 180; yaw += 22.5f) {
            for (float pitch = -90; pitch <= 90; pitch += 15) {
                assertClose(Vec3.directionFromRotation(pitch, yaw), Aim.direction(yaw, pitch));
            }
        }
    }

    @Test
    void towardUndoesDirection() {
        for (float yaw = -165; yaw < 180; yaw += 15) {
            for (float pitch = -75; pitch <= 75; pitch += 15) {
                float[] aim = Aim.toward(Aim.direction(yaw, pitch), 0);
                assertEquals(yaw, aim[0], 1e-3);
                assertEquals(pitch, aim[1], 1e-3);
            }
        }
    }

    @Test
    void straightDownKeepsItsYaw() {
        float[] aim = Aim.toward(new Vec3(0, -1, 0), 40);
        assertEquals(40, aim[0]);
        assertEquals(90, aim[1], 1e-4);
    }

    @Test
    void spreadsWrap() {
        FloodlightKind kind = FloodlightKind.FLOODLIGHT;
        assertEquals(kind.spread(0), kind.spread(kind.spreads()));
        assertEquals(kind.spread(kind.spreads() - 1), kind.spread(-1));
    }
}
