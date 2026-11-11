package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.haruyq.createmediasystems.lighting.AnalysisFrame;
import org.haruyq.createmediasystems.lighting.LightingSettings;

import io.github.jinsh1228.largelights.FloodlightKind;
import net.minecraft.world.phys.Vec3;

class FloodlightRendererTest {
    @Test
    void typedAudioBandsKeepTheirResponseAndMissingSignalsKeepTheFader() {
        var group = new LightingSettings.Group();
        var audio = new AnalysisFrame(0, .4f, .2f, .3f, .5f, .1f, 120, 0, 0, true, false, 0);
        group.audio = true;
        float[] expected = {.5285f, .3185f, .4235f, .6335f};
        for (var band : LightingSettings.Band.values()) {
            group.audioBand = band;
            assertEquals(expected[band.ordinal()], FloodlightRenderer.audioStrength(.7f, group, audio), 1e-6);
        }
        assertEquals(.7f, FloodlightRenderer.audioStrength(.7f, group, null));
        group.audio = false;
        assertEquals(.7f, FloodlightRenderer.audioStrength(.7f, group, audio));
    }

    @Test
    void videoFramesOverrideIndividualColorsOnlyWhenVideoColorsAreEnabled() {
        var group = new LightingSettings.Group();
        group.mapping = LightingSettings.Mapping.PER_FIXTURE;
        group.colors[0] = 0x102030;
        group.colors[1] = 0xAABBCC;
        group.colorCount = 2;
        var video = new AnalysisFrame(0, 0, 0, 0, 0, 0, 120, 0xFF0000, 0x0000FF, false, true, 0);
        group.colorMode = LightingSettings.ColorMode.OWN;
        assertEquals(0xAABBCC, FloodlightRenderer.color(group, 0, 2, 0, video, 1));
        group.colorMode = LightingSettings.ColorMode.VIDEO;
        assertEquals(0x0000FF, FloodlightRenderer.color(group, 1, 2, 0, video, 0));
        assertEquals(0xAABBCC, FloodlightRenderer.color(group, 0, 2, 0, null, 1));
        group.mapping = LightingSettings.Mapping.GRADIENT;
        assertEquals(0x800080, FloodlightRenderer.color(group, 1, 3, 0, video, 0));
    }
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
