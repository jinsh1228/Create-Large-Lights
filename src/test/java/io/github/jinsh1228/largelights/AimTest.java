package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
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

    @Test
    void ceilingPanTiltsLeftAndRightInsteadOfSpinningAboutTheBeam() {
        for (int sign : new int[]{-1, 1}) {
            float[] aim = Aim.offset(0, 90, sign * 30, Aim.NEUTRAL_TILT, true);
            assertClose(new Vec3(-sign * .5, -Math.sqrt(3) / 2, 0), Aim.direction(aim[0], aim[1]));
        }
        float[] eastFacing = Aim.offset(90, 90, 30, Aim.NEUTRAL_TILT, true);
        assertClose(new Vec3(0, -Math.sqrt(3) / 2, -.5), Aim.direction(eastFacing[0], eastFacing[1]));
    }

    @Test
    void ceilingTiltCanCrossStraightDownInBothDirections() {
        for (int sign : new int[]{-1, 1}) {
            float[] aim = Aim.offset(0, 90, 0, Aim.NEUTRAL_TILT + sign * 30, true);
            assertClose(new Vec3(0, -Math.sqrt(3) / 2, -sign * .5), Aim.direction(aim[0], aim[1]));
        }
    }

    @Test
    void ceilingGameplayLightAndTheHeadShareTheirDirectionAcrossTheControlRange() {
        for (float yaw : new float[]{-170, 0, 90}) {
            for (float pitch : new float[]{15, 60, 90}) {
                float[] neutral = Aim.offset(yaw, pitch, 0, Aim.NEUTRAL_TILT, true);
                assertEquals(yaw, neutral[0]);
                assertEquals(pitch, neutral[1]);
                for (float pan : new float[]{-180, -30, 0, 30, 180}) {
                    for (float tilt : new float[]{-85, Aim.NEUTRAL_TILT, 85}) {
                        float[] aim = Aim.offset(yaw, pitch, pan, tilt, true);
                        assertClose(forward(Aim.relativeHead(yaw, pitch, pan, tilt)), Aim.direction(aim[0], aim[1]));
                    }
                }
            }
        }
    }

    @Test
    void wallControlsKeepTheirCompassAndTilt() {
        float[] north = Aim.offset(180, 35, 20, 55, false);
        assertEquals(-160, north[0]);
        assertEquals(55, north[1]);
        float[] east = Aim.offset(-90, 35, 30, 5, false);
        assertEquals(-60, east[0]);
        assertEquals(5, east[1]);
    }

    @Test
    void trackingAcrossTheFloorBelowACeilingLightKeepsItsHeadUpright() {
        Vector3f previousRight = null;
        for (int step = -4; step <= 4; step++) {
            Vec3 target = new Vec3(step * .25, -12, 0);
            float[] controls = Aim.relativeToward(target, 0, 90, 0);
            Matrix4f head = Aim.relativeHead(0, 90, controls[0], controls[1]);
            assertClose(target.normalize(), forward(head));
            Vector3f right = head.transformDirection(new Vector3f(1, 0, 0));
            if (previousRight != null) assertTrue(right.dot(previousRight) > .99f);
            previousRight = right;
        }
    }

    private static Vec3 forward(Matrix4f head) {
        Vector3f direction = head.transformDirection(new Vector3f(0, 0, 1));
        return new Vec3(direction.x, direction.y, direction.z).normalize();
    }
}
