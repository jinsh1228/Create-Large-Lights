package io.github.jinsh1228.largelights;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where a floodlight's head points, as Minecraft's own rotations: {@code yaw} as an entity's (0 is south, 90 west)
 * and {@code pitch} as an entity's (positive is down). The head turns about its block's centre; its beam leaves
 * along its local +Z.
 */
public final class Aim {
    public static final float MIN_PITCH = -90;
    public static final float MAX_PITCH = 90;

    private Aim() {
    }

    /** The head's own space into its block's: the beam along +Z, the lens's width along +X. */
    public static Matrix4f head(float yaw, float pitch) {
        return new Matrix4f().translate(0.5f, 0.5f, 0.5f)
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
    }

    public static Vec3 direction(float yaw, float pitch) {
        Vector3f forward = head(yaw, pitch).transformDirection(new Vector3f(0, 0, 1));
        return new Vec3(forward.x, forward.y, forward.z).normalize();
    }

    /** The yaw and pitch that point along {@code direction}; straight up or down keeps {@code fallbackYaw}. */
    public static float[] toward(Vec3 direction, float fallbackYaw) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = horizontal < 1e-6 ? fallbackYaw
                : Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        float pitch = (float) Math.toDegrees(-Math.atan2(direction.y, horizontal));
        return new float[]{yaw, Mth.clamp(pitch, MIN_PITCH, MAX_PITCH)};
    }
}
