package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.jinsh1228.largelights.FloodlightKind;
import net.minecraft.world.phys.Vec3;

class FloodlightRendererTest {
    @Test
    void changingOnlyRangeInvalidatesTheBeamTrace() {
        FloodlightRenderer.Trace trace = new FloodlightRenderer.Trace();
        Vec3 from = new Vec3(10, 20, 30);
        Vec3 origin = new Vec3(0.5, 0.5, 0.66);
        Vec3 direction = new Vec3(0, 0, 1);
        trace.lastFrom = from;
        trace.lastOrigin = origin;
        trace.lastDirection = direction;
        trace.lastTangent = 0.5;
        trace.lastRange = 128;

        assertTrue(trace.sameBeam(from, origin, direction, 0.5, 128));
        assertFalse(trace.sameBeam(from, origin, direction, 0.5, 32));
        assertFalse(trace.sameBeam(from, origin, direction, 0.5, 256));
    }

    @Test
    void overlayHasFullStrengthWhenRealLightIsDisabled() {
        for (FloodlightKind kind : FloodlightKind.values()) {
            assertEquals(kind.intensity, FloodlightRenderer.overlay(kind, false, 15));
            assertEquals(kind.intensity, FloodlightRenderer.overlay(kind, true, 0));
            assertEquals(kind.intensity * 0.4f, FloodlightRenderer.overlay(kind, true, 15));
        }
    }

    @Test
    void anEasedAimConvergesToZeroAndThenStaysExactlyStillAtDifferentFrameRates() {
        for (int fps : new int[]{30, 60, 144}) {
            AimMotion motion = new AimMotion();
            motion.update(0, 12, 4, false);
            float[] first = motion.update(1.0 / fps, 0, 0, false);
            assertTrue(first[0] > 0 && first[0] < 12);
            assertTrue(first[1] > 0 && first[1] < 4);
            for (int frame = 2; frame <= fps * 4; frame++) {
                float[] aim = motion.update(frame / (double) fps, 0, 0, false);
                if (frame >= fps * 3) {
                    assertEquals(0f, aim[0]);
                    assertEquals(0f, aim[1]);
                }
            }
        }
    }
}
