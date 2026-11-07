package io.github.jinsh1228.largelights;

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

    @Test
    void theConsolesDefaultAimLeavesALightWhereItWasAimed() {
        float[] aim = Aim.offset(30, 50, 0, Aim.NEUTRAL_TILT);
        assertEquals(30, aim[0]);
        assertEquals(50, aim[1]);
    }

    @Test
    void panTurnsRightAndTiltDown() {
        float[] aim = Aim.offset(170, 80, 20, Aim.NEUTRAL_TILT + 20);
        assertEquals(-170, aim[0], 1e-4);
        // Down no further than straight down.
        assertEquals(90, aim[1]);
        Vec3 before = Aim.direction(0, 0);
        Vec3 after = Aim.direction(Aim.offset(0, 0, 90, Aim.NEUTRAL_TILT)[0], 0);
        // Facing south, turning right (seen from above) faces west.
        assertClose(new Vec3(0, 0, 1), before);
        assertClose(new Vec3(-1, 0, 0), after);
    }
}
