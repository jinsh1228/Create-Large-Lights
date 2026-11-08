package io.github.jinsh1228.largelights.client;

import net.minecraft.util.Mth;

/**
 * A floodlight head easing toward where it should point, as a Create: Media Systems stage light's does
 * (LightingMotion): the shorter way round in yaw, and held still while it has nowhere to go.
 */
final class AimMotion {
    private double time = Double.NaN;
    private float yaw;
    private float pitch;

    float[] update(double seconds, float desiredYaw, float desiredPitch, boolean hold) {
        if (!Double.isFinite(time) || seconds < time) {
            yaw = desiredYaw;
            pitch = desiredPitch;
        } else if (!hold) {
            double weight = 1 - Math.exp(-Math.min(seconds - time, 0.5) * 9);
            float yawError = Mth.wrapDegrees(desiredYaw - yaw);
            float pitchError = desiredPitch - pitch;
            // Subpixel motion near zero otherwise keeps invalidating shadow maps until float underflow.
            yaw = Math.abs(yawError) <= 1e-4f ? desiredYaw : yaw + yawError * (float) weight;
            pitch = Math.abs(pitchError) <= 1e-4f ? desiredPitch : pitch + pitchError * (float) weight;
        }
        time = seconds;
        return new float[]{Mth.wrapDegrees(yaw), pitch};
    }
}
