package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import io.github.jinsh1228.largelights.Aim;

class AimRotationTest {
    @Test
    void crossingStraightDownTiltsInTheSamePlaneAndThenStaysExactlyStill() {
        for (int fps : new int[]{30, 60, 144}) {
            AimRotation motion = new AimRotation();
            Matrix4f head = motion.update(0, Aim.relativeHead(0, 90, -30, Aim.NEUTRAL_TILT), false);
            float previousX = head.transformDirection(new Vector3f(0, 0, 1)).x;
            Matrix4f settled = null;
            for (int frame = 1; frame <= fps * 4; frame++) {
                head = motion.update(frame / (double) fps, Aim.relativeHead(0, 90, 30, Aim.NEUTRAL_TILT), false);
                Vector3f forward = head.transformDirection(new Vector3f(0, 0, 1));
                assertEquals(0, forward.z, 1e-5);
                assertTrue(forward.y < -.85f);
                assertTrue(forward.x <= previousX + 1e-5f);
                assertTrue(head.transformDirection(new Vector3f(1, 0, 0)).x > .85f);
                previousX = forward.x;
                if (frame == fps * 3) settled = new Matrix4f(head);
                if (settled != null) assertTrue(head.equals(settled, 0));
            }
            assertEquals(-.5f, previousX, 1e-5);
        }
    }

    @Test
    void aMissingTrackingTargetHoldsTheWholeOrientation() {
        AimRotation motion = new AimRotation();
        Matrix4f initial = new Matrix4f(motion.update(0, Aim.relativeHead(40, 90, 20, 50), false));
        for (int frame = 1; frame < 60; frame++) {
            Matrix4f head = motion.update(frame / 60.0, Aim.relativeHead(40, 90, -50, -10), true);
            assertTrue(initial.equals(head, 0));
        }
    }

    @Test
    void rewindingTheClockUsesTheCurrentAimImmediately() {
        AimRotation motion = new AimRotation();
        motion.update(10, Aim.relativeHead(0, 90, -30, 35), false);
        Matrix4f desired = Aim.relativeHead(0, 90, 30, 35);
        Vector3f expected = desired.transformDirection(new Vector3f(0, 0, 1));
        Vector3f actual = motion.update(1, desired, false).transformDirection(new Vector3f(0, 0, 1));
        assertEquals(expected.x, actual.x, 1e-5);
        assertEquals(expected.y, actual.y, 1e-5);
        assertEquals(expected.z, actual.z, 1e-5);
    }
}
