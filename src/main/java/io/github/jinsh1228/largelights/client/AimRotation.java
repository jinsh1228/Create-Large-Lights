package io.github.jinsh1228.largelights.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Eases a ceiling head in orientation space, without an Euler yaw flip at straight down. */
final class AimRotation {
    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf target = new Quaternionf();
    private double time = Double.NaN;

    Matrix4f update(double seconds, Matrix4f desired, boolean hold) {
        desired.getNormalizedRotation(target).normalize();
        if (!Double.isFinite(time) || seconds < time) {
            rotation.set(target);
        } else if (!hold) {
            rotation.slerp(target, (float) (1 - Math.exp(-Math.min(seconds - time, .5) * 9))).normalize();
            float sign = rotation.dot(target) < 0 ? -1 : 1;
            if (Math.abs(rotation.x - sign * target.x) <= 1e-6f
                    && Math.abs(rotation.y - sign * target.y) <= 1e-6f
                    && Math.abs(rotation.z - sign * target.z) <= 1e-6f
                    && Math.abs(rotation.w - sign * target.w) <= 1e-6f) {
                rotation.set(target);
            }
        }
        time = seconds;
        return desired.identity().translate(.5f, .5f, .5f).rotate(rotation);
    }
}
