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
    /**
     * A lighting console group's tilt that leaves a floodlight where it was aimed: the console's default (a stage
     * light's 35° down), so that linking one to a group changes nothing till the group's aim is moved.
     */
    public static final float NEUTRAL_TILT = 35;

    private Aim() {
    }

    /** The head's own space into its block's: the beam along +Z, the lens's width along +X. */
    public static Matrix4f head(float yaw, float pitch) {
        return new Matrix4f().translate(0.5f, 0.5f, 0.5f)
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
    }

    /** Ceiling controls tilt about the fixture's own axes, including across its straight-down aim. */
    public static Matrix4f relativeHead(float yaw, float pitch, float pan, float tilt) {
        return head(yaw, pitch).rotateY((float) Math.toRadians(-pan))
                .rotateX((float) Math.toRadians(tilt - NEUTRAL_TILT));
    }

    public static Vec3 direction(float yaw, float pitch) {
        Vector3f forward = head(yaw, pitch).transformDirection(new Vector3f(0, 0, 1));
        return new Vec3(forward.x, forward.y, forward.z).normalize();
    }

    /**
     * {@code yaw} and {@code pitch} turned by a console group's {@code pan} (positive turns right, seen from above)
     * and {@code tilt} (positive turns down), both from the group's neutral aim (pan 0, NEUTRAL_TILT).
     */
    public static float[] offset(float yaw, float pitch, float pan, float tilt) {
        return new float[]{Mth.wrapDegrees(yaw + pan), Mth.clamp(pitch + tilt - NEUTRAL_TILT, MIN_PITCH, MAX_PITCH)};
    }

    public static float[] offset(float yaw, float pitch, float pan, float tilt, boolean ceiling) {
        if (!ceiling || pan == 0 && tilt == NEUTRAL_TILT) return offset(yaw, pitch, pan, tilt);
        // Match relativeHead without allocating a matrix for gameplay light queries.
        Vector3f forward = new Vector3f(0, 0, 1)
                .rotateX((float) Math.toRadians(tilt - NEUTRAL_TILT)).rotateY((float) Math.toRadians(-pan))
                .rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(-yaw));
        return toward(forward.x, forward.y, forward.z, yaw);
    }

    /** A tracked direction in the same local controls as relativeHead. */
    public static float[] relativeToward(Vec3 direction, float yaw, float pitch, float fallbackPan) {
        Vector3f local = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z)
                .rotateY((float) Math.toRadians(yaw)).rotateX((float) Math.toRadians(-pitch));
        float[] aim = toward(local.x, local.y, local.z, fallbackPan);
        aim[1] += NEUTRAL_TILT;
        return aim;
    }

    /** The yaw and pitch that point along {@code direction}; straight up or down keeps {@code fallbackYaw}. */
    public static float[] toward(Vec3 direction, float fallbackYaw) {
        return toward(direction.x, direction.y, direction.z, fallbackYaw);
    }

    private static float[] toward(double x, double y, double z, float fallbackYaw) {
        double horizontal = Math.sqrt(x * x + z * z);
        float yaw = horizontal < 1e-6 ? fallbackYaw
                : Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-x, z)));
        float pitch = (float) Math.toDegrees(-Math.atan2(y, horizontal));
        return new float[]{yaw, Mth.clamp(pitch, MIN_PITCH, MAX_PITCH)};
    }
}
