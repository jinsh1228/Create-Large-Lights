package io.github.jinsh1228.largelights.client;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.Rgb;
import org.haruyq.createmediasystems.api.client.Shaders;
import org.haruyq.createmediasystems.api.sublevel.SubLevels;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.api.lighting.AudioLevels;
import org.haruyq.createmediasystems.api.lighting.GroupView;
import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.haruyq.createmediasystems.api.client.WorldChanges;
import org.haruyq.createmediasystems.lighting.LightingRaycast;
import org.haruyq.createmediasystems.lighting.LightingRenderer;
import org.haruyq.createmediasystems.lighting.LightingSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.FloodlightConfig;
import io.github.jinsh1228.largelights.FloodlightKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A floodlight's body and aimed light. GPU projection lights its surfaces; CMS retains compatible fallbacks,
 * lenses and the air beam stopped where each edge meets a block.
 */
public final class FloodlightRenderer implements BlockEntityRenderer<FloodlightBlock.Entity> {
    private static final int EDGES = 16;
    private static final int STEPS = 8;
    private static final Vector3f VERTEX = new Vector3f();
    /** The lighting console's brushed frame and riveted casing, as CMS's stage lights wear them. */
    private static final RenderType FRAME = RenderType.entityCutoutNoCull(
            CreateMediaSystems.id("textures/block/lighting_console_frame.png"));
    private static final RenderType CASING = RenderType.entityCutoutNoCull(
            CreateMediaSystems.id("textures/block/lighting_console_casing.png"));
    /** Boxes, 7 floats each: x0, y0, z0, x1, y1, z1, colour. The mount's in its own space (+Y away from the wall). */
    private static final float[] FLOODLIGHT_MOUNT = {
            -0.3f, -0.5f, -0.3f, 0.3f, -0.43f, 0.3f, 0x8A8C98,
            -0.065f, -0.43f, -0.065f, 0.065f, -0.06f, 0.065f, 0x9A9CA8};
    private static final float[] STADIUM_MOUNT = {
            -0.46f, -0.5f, -0.46f, 0.46f, -0.43f, 0.46f, 0x8A8C98,
            -0.1f, -0.43f, -0.1f, 0.1f, -0.06f, 0.1f, 0x9A9CA8};
    /** The head's, in its own space (the beam along +Z): its frame, then its casing. */
    private static final float[] FLOODLIGHT_FRAME = boxes(
            new float[]{-0.11f, -0.11f, -0.11f, 0.11f, 0.11f, 0.11f, 0x8A8C98,
                    -0.43f, -0.43f, 0.13f, 0.43f, -0.36f, 0.17f, 0xB1B3BE,
                    -0.43f, 0.36f, 0.13f, 0.43f, 0.43f, 0.17f, 0xB1B3BE,
                    -0.43f, -0.36f, 0.13f, -0.36f, 0.36f, 0.17f, 0xB1B3BE,
                    0.36f, -0.36f, 0.13f, 0.43f, 0.36f, 0.17f, 0xB1B3BE},
            4, i -> new float[]{-0.34f, -0.325f + i * 0.2f, -0.29f, 0.34f, -0.275f + i * 0.2f, -0.2f, 0x9A9CA8});
    private static final float[] FLOODLIGHT_CASING = {-0.4f, -0.4f, -0.2f, 0.4f, 0.4f, 0.13f, 0xFFFFFF};
    private static final float[] STADIUM_FRAME = boxes(
            new float[]{-0.11f, -0.11f, -0.11f, 0.11f, 0.11f, 0.11f, 0x8A8C98,
                    -0.98f, -0.64f, 0.08f, -0.92f, 0.64f, 0.1f, 0xB1B3BE,
                    0.92f, -0.64f, 0.08f, 0.98f, 0.64f, 0.1f, 0xB1B3BE,
                    -0.98f, -0.64f, 0.08f, 0.98f, -0.6f, 0.1f, 0xB1B3BE,
                    -0.98f, 0.6f, 0.08f, 0.98f, 0.64f, 0.1f, 0xB1B3BE,
                    -0.98f, -0.02f, 0.08f, 0.98f, 0.02f, 0.1f, 0xB1B3BE,
                    -0.33f, -0.64f, 0.08f, -0.29f, 0.64f, 0.1f, 0xB1B3BE,
                    0.29f, -0.64f, 0.08f, 0.33f, 0.64f, 0.1f, 0xB1B3BE},
            5, i -> new float[]{-0.83f + i * 0.4f, -0.55f, -0.27f, -0.77f + i * 0.4f, 0.55f, -0.17f, 0x9A9CA8});
    private static final float[] STADIUM_CASING = {-0.98f, -0.64f, -0.17f, 0.98f, 0.64f, 0.08f, 0xFFFFFF};
    /** The lenses, 4 floats each: x0, y0, x1, y1 (at the lens's z). */
    private static final float[] FLOODLIGHT_LENSES = {-0.36f, -0.36f, 0.36f, 0.36f};
    private static final float[] STADIUM_LENSES = lenses();
    /** The beam's edges round its axis: cosines and sines. */
    private static final double[] COS = new double[EDGES];
    private static final double[] SIN = new double[EDGES];
    /** Closer than this (blocks), the beam in the air is drawn in full; further, in half the steps. */
    private static final float NEAR = 48;
    /** Unlinked, on redstone: a hall's warm white. */
    public static final int WARM_WHITE = 0xFFF1DC;
    private static final int OFF_LENS = 0x1C232D;
    /** Of the overlay, with real light under it. */
    private static final float REAL_LIGHT_OVERLAY = 0.4f;

    static {
        for (int i = 0; i < EDGES; i++) {
            COS[i] = Math.cos(Math.PI * 2 * i / EDGES);
            SIN[i] = Math.sin(Math.PI * 2 * i / EDGES);
        }
    }

    private static float[] boxes(float[] first, int count, java.util.function.IntFunction<float[]> more) {
        float[] all = java.util.Arrays.copyOf(first, first.length + count * 7);
        for (int i = 0; i < count; i++) {
            System.arraycopy(more.apply(i), 0, all, first.length + i * 7, 7);
        }
        return all;
    }

    private static float[] lenses() {
        float[] lenses = new float[6 * 4];
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                int i = (row * 3 + col) * 4;
                float x = -0.62f + col * 0.62f;
                float y = -0.31f + row * 0.62f;
                lenses[i] = x - 0.26f;
                lenses[i + 1] = y - 0.26f;
                lenses[i + 2] = x + 0.26f;
                lenses[i + 3] = y + 0.26f;
            }
        }
        return lenses;
    }

    FloodlightRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(FloodlightBlock.Entity lamp, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        Level level = lamp.getLevel();
        if (level == null) {
            return;
        }
        FloodlightKind kind = lamp.kind();
        boolean stadium = kind == FloodlightKind.STADIUM_LIGHT;
        Matrix4f model = new Matrix4f(pose.last().pose());
        Direction facing = lamp.getBlockState().getValue(FloodlightBlock.FACING);
        // Local +Y away from what it hangs on.
        Matrix4f mount = new Matrix4f(model).translate(0.5f, 0.5f, 0.5f).rotate(facing.getRotation());
        // Linked, the console's group sets all of it; unlinked, redstone sets how bright (FloodlightBlock.Entity.beam).
        FloodlightBlock.Entity.Beam beam = lamp.beam();
        LightingConsoleBlock.Entity console = beam.console();
        double seconds = (level.getGameTime() + (double) partialTick) / 20;
        if (console != null) {
            seconds = console.playhead.seconds(System.nanoTime(), seconds);
        }
        Matrix4f local = aim(lamp, beam, seconds, partialTick);
        Matrix4f head = new Matrix4f(model).mul(local);

        float lensZ = kind.lensZ;
        float[] normals = new float[18];
        VertexConsumer frame = buffers.getBuffer(FRAME);
        boxes(frame, mount, normals(mount, normals), stadium ? STADIUM_MOUNT : FLOODLIGHT_MOUNT, light);
        boxes(frame, head, normals(head, normals), stadium ? STADIUM_FRAME : FLOODLIGHT_FRAME, light);
        boxes(buffers.getBuffer(CASING), head, normals, stadium ? STADIUM_CASING : FLOODLIGHT_CASING, light);
        if (Shaders.shadowPass()) {
            return;
        }

        float strength = beam.strength();
        int color = WARM_WHITE;
        if (console != null) {
            LightingSettings.Group group = beam.group();
            strength = audioStrength(strength, group, console.audio(level.getGameTime()));
            var shown = console.video(level.getGameTime());
            color = color(group, lamp.order(), Math.max(1, console.groupSize(lamp.group())), seconds,
                    shown, lamp.individualColor);
        }

        VertexConsumer lens = buffers.getBuffer(LightingRenderer.lensType());
        int lensColor = strength > 0.001f ? Rgb.mix(color, 0xFFFFFF, 0.35f) : OFF_LENS;
        float[] lenses = stadium ? STADIUM_LENSES : FLOODLIGHT_LENSES;
        for (int i = 0; i < lenses.length; i += 4) {
            lensQuad(lens, head, lenses[i], lenses[i + 1], lenses[i + 2], lenses[i + 3], lensZ, lensColor);
        }
        if (strength <= 0.001f) {
            return;
        }

        Vec3 origin = point(local, new Vec3(0, 0, lensZ + 0.01));
        Vec3 direction = vector(local, new Vec3(0, 0, 1)).normalize();
        Vec3 right = vector(local, new Vec3(1, 0, 0)).normalize();
        Vec3 up = vector(local, new Vec3(0, 1, 0)).normalize();
        double halfAngle = Math.toRadians(beam.spread()) / 2;
        if (!(lamp.renderTrace instanceof Trace)) {
            lamp.renderTrace = new Trace();
        }
        Trace trace = (Trace) lamp.renderTrace;
        LightingRaycast rays = trace.update(lamp, origin, direction, right, up, Math.tan(halfAngle), beam.range());
        MultiBufferSource effects = LightingRenderer.beamBuffers(level, buffers);
        boolean realLight = FloodlightConfig.SPEC.isLoaded() && FloodlightConfig.REAL_LIGHT.get();
        float surfaceStrength = strength * overlay(kind, realLight, beam.lightLimit());
        if (!GpuLightProjection.queue(lamp, origin, direction, up, halfAngle, beam.range(),
                surfaceStrength, color, kind.radius)) {
            if (rays == null) rays = LightingRaycast.rendered(level, lamp.getBlockPos());
            LightingIllumination.render(lamp, origin, direction, halfAngle, beam.range(), surfaceStrength, color,
                    trace.center, rays, model, effects, kind.radius);
        }
        // The camera is at the frame's origin: the pose's translation is how far off the floodlight is.
        float distanceSqr = model.m30() * model.m30() + model.m31() * model.m31() + model.m32() * model.m32();
        cone(effects.getBuffer(LightingRenderer.beamType()), model, trace, origin, right, up,
                stadium ? 0.85f : 0.36f, stadium ? 0.55f : 0.36f, color, strength * kind.haze,
                distanceSqr < NEAR * NEAR ? STEPS : STEPS / 2);
    }

    /**
     * Where the head points this frame: its aim with the console's pan and tilt (Beam), and, linked, its group's
     * motion as a stage light's (a sway, a circle, or following the target player), eased as a stage light's head.
     */
    private static Matrix4f aim(FloodlightBlock.Entity lamp, FloodlightBlock.Entity.Beam beam, double seconds,
                                float partialTick) {
        boolean hold = false;
        LightingConsoleBlock.Entity console = beam.console();
        LightingSettings.Group group = beam.group();
        boolean ceiling = lamp.getBlockState().getValue(FloodlightBlock.FACING) == Direction.DOWN;
        boolean relative = ceiling && console != null && group != null;
        float yaw = relative ? group.pan : beam.yaw();
        float pitch = relative ? group.tilt : beam.pitch();
        if (console != null && group != null) {
            double phase = (group.audio && console.audioSource() != null
                    ? console.playhead.phase(System.nanoTime(), seconds) : seconds) * group.speed + lamp.order() * 0.35;
            if (group.motion == LightingSettings.Motion.SWEEP) {
                yaw += (float) Math.sin(phase) * 55;
            } else if (group.motion == LightingSettings.Motion.CIRCLE) {
                yaw += (float) Math.sin(phase) * 48;
                pitch += (float) Math.cos(phase) * 22;
            } else if (group.motion == LightingSettings.Motion.TRACK) {
                // Following: straight at the target player; with none, held where it is.
                hold = true;
                Player target = group.target == null ? null : lamp.getLevel().getPlayerByUUID(group.target);
                if (target != null) {
                    Vec3 body = target.getPosition(partialTick).add(0, target.getBbHeight() * 0.5, 0);
                    Vec3 toward = SubLevels.toRenderBlockSpace(lamp.getLevel(), lamp.getBlockPos(), body)
                            .subtract(Vec3.atCenterOf(lamp.getBlockPos()));
                    if (toward.lengthSqr() > 0.25) {
                        float[] at = ceiling ? Aim.relativeToward(toward, lamp.yaw, lamp.pitch, group.pan)
                                : Aim.toward(toward, yaw);
                        yaw = at[0];
                        pitch = at[1];
                        hold = false;
                    }
                }
            }
        }
        if (ceiling) {
            if (!(lamp.renderMotion instanceof AimRotation)) lamp.renderMotion = new AimRotation();
            Matrix4f desired = relative ? Aim.relativeHead(lamp.yaw, lamp.pitch, yaw, pitch)
                    : Aim.head(yaw, Mth.clamp(pitch, Aim.MIN_PITCH, Aim.MAX_PITCH));
            return ((AimRotation) lamp.renderMotion).update(seconds, desired, hold);
        }
        if (!(lamp.renderMotion instanceof AimMotion)) {
            lamp.renderMotion = new AimMotion();
        }
        float[] aim = ((AimMotion) lamp.renderMotion).update(seconds, yaw,
                Mth.clamp(pitch, Aim.MIN_PITCH, Aim.MAX_PITCH), hold);
        return Aim.head(aim[0], aim[1]);
    }

    /**
     * How strongly the beam is laid over what it falls on. With real light (RealLight; the server's config reaches
     * the client) the world is already lit there, so the overlay only shows the beam's colour and shape.
     */
    static float overlay(FloodlightKind kind, boolean real, int lightLimit) {
        return kind.intensity * (real && lightLimit > 0 ? REAL_LIGHT_OVERLAY : 1);
    }

    static float audioStrength(float strength, GroupView group, AudioLevels audio) {
        if (!group.audio() || audio == null) return strength;
        float energy = switch (group.audioBand()) {
            case BASS -> audio.bass();
            case MID -> audio.mid();
            case HIGH -> audio.high();
            default -> audio.level();
        };
        return strength * Mth.clamp(0.12f + energy * 1.5f + audio.pulse() * 0.35f, 0, 1);
    }

    static int color(LightingSettings.Group group, int order, int count, double seconds, AudioLevels shown, int individual) {
        boolean video = shown != null;
        return group.mapping == LightingSettings.Mapping.PER_FIXTURE && !group.videoColors(video)
                ? group.colors[Math.floorMod(individual, group.colorCount)]
                : LightingSettings.color(group, order, count, seconds,
                        shown == null ? 0 : shown.left(), shown == null ? 0 : shown.right(), video);
    }

    /** Where the beam's middle and edges meet blocks, traced again only when something that matters changed. */
    static final class Trace {
        LightingRaycast.Hit center;
        /** Each edge's unit direction, x, y, z. */
        final double[] edges = new double[EDGES * 3];
        final double[] lengths = new double[EDGES];
        long traced = -2;
        long blocks = -1;
        AABB bounds;
        /** Where the beam left from in the world: it moves with a Sable structure the floodlight is on. */
        Vec3 lastFrom;
        Vec3 lastOrigin;
        Vec3 lastDirection;
        double lastTangent;
        float lastRange;

        boolean sameBeam(Vec3 from, Vec3 origin, Vec3 direction, double tangent, float range) {
            return from.equals(lastFrom) && origin.equals(lastOrigin) && direction.equals(lastDirection)
                    && tangent == lastTangent && range == lastRange;
        }

        LightingRaycast update(FloodlightBlock.Entity lamp, Vec3 origin, Vec3 direction, Vec3 right,
                    Vec3 up, double tangent, float range) {
            long frame = WorldChanges.frame();
            Level level = lamp.getLevel();
            Vec3 anchor = Vec3.atLowerCornerOf(lamp.getBlockPos());
            Vec3 from = SubLevels.toRenderWorld(level, lamp.getBlockPos(), origin.add(anchor));
            // Off the world (a Ponder scene), every frame; in it, again when a block changes or the light moves.
            boolean still = center != null && frame - traced <= 1 && level == Minecraft.getInstance().level
                    && bounds != null && blocks == LightModelSnapshots.watch(this, bounds)
                    && GpuProjectionLifecycle.staticGeometry(level, bounds) && sameBeam(from, origin, direction, tangent, range);
            traced = frame;
            if (still) return null;
            lastFrom = from;
            lastOrigin = origin;
            lastDirection = direction;
            lastTangent = tangent;
            lastRange = range;
            LightingRaycast rays = LightingRaycast.rendered(level, lamp.getBlockPos());
            center = rays.clip(from, SubLevels.toRenderWorld(level, lamp.getBlockPos(),
                    origin.add(direction.scale(range)).add(anchor)));
            bounds = new AABB(from, center.point());
            for (int i = 0; i < EDGES; i++) {
                Vec3 aim = direction.add(right.scale(COS[i] * tangent)).add(up.scale(SIN[i] * tangent)).normalize();
                edges[i * 3] = aim.x;
                edges[i * 3 + 1] = aim.y;
                edges[i * 3 + 2] = aim.z;
                LightingRaycast.Hit hit = rays.clip(from, SubLevels.toRenderWorld(level, lamp.getBlockPos(),
                        origin.add(aim.scale(range)).add(anchor)));
                bounds = bounds.minmax(new AABB(from, hit.point()));
                lengths[i] = hit.block().getType() == HitResult.Type.BLOCK
                        ? SubLevels.toRenderBlockSpace(level, lamp.getBlockPos(), hit.point()).subtract(anchor)
                        .distanceTo(origin) : range;
            }
            bounds = bounds.inflate(2);
            blocks = LightModelSnapshots.watch(this, bounds);
            return rays;
        }
    }

    /**
     * The beam in the air: from the lens's outline ({@code width} by {@code height} across) out along each edge,
     * each strip as far as the nearer of its two edges reaches, fading as it goes, in {@code steps} along.
     */
    private static void cone(VertexConsumer out, Matrix4f matrix, Trace trace, Vec3 origin, Vec3 right, Vec3 up,
                             float width, float height, int color, float alpha, int steps) {
        if (alpha < 1) {
            return;
        }
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        double[] e = trace.edges;
        for (int i = 0; i < EDGES; i++) {
            int next = (i + 1) % EDGES;
            double length = Math.min(trace.lengths[i], trace.lengths[next]);
            double ax = origin.x + right.x * COS[i] * width + up.x * SIN[i] * height;
            double ay = origin.y + right.y * COS[i] * width + up.y * SIN[i] * height;
            double az = origin.z + right.z * COS[i] * width + up.z * SIN[i] * height;
            double bx = origin.x + right.x * COS[next] * width + up.x * SIN[next] * height;
            double by = origin.y + right.y * COS[next] * width + up.y * SIN[next] * height;
            double bz = origin.z + right.z * COS[next] * width + up.z * SIN[next] * height;
            int ai = i * 3, bi = next * 3;
            for (int step = 0; step < steps; step++) {
                double t0 = length * step / steps;
                double t1 = length * (step + 1) / steps;
                int alpha0 = Mth.clamp(Math.round(alpha * (1 - 0.85f * step / steps)), 0, 255);
                int alpha1 = Mth.clamp(Math.round(alpha * (1 - 0.85f * (step + 1) / steps)), 0, 255);
                vertex(out, matrix, (float) (ax + e[ai] * t0), (float) (ay + e[ai + 1] * t0), (float) (az + e[ai + 2] * t0))
                        .setColor(r, g, b, alpha0);
                vertex(out, matrix, (float) (bx + e[bi] * t0), (float) (by + e[bi + 1] * t0), (float) (bz + e[bi + 2] * t0))
                        .setColor(r, g, b, alpha0);
                vertex(out, matrix, (float) (bx + e[bi] * t1), (float) (by + e[bi + 1] * t1), (float) (bz + e[bi + 2] * t1))
                        .setColor(r, g, b, alpha1);
                vertex(out, matrix, (float) (ax + e[ai] * t1), (float) (ay + e[ai + 1] * t1), (float) (az + e[ai + 2] * t1))
                        .setColor(r, g, b, alpha1);
            }
        }
    }

    private static void lensQuad(VertexConsumer out, Matrix4f matrix, float x0, float y0, float x1, float y1, float z,
                                 int color) {
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        vertex(out, matrix, x0, y0, z).setColor(r, g, b, 255);
        vertex(out, matrix, x1, y0, z).setColor(r, g, b, 255);
        vertex(out, matrix, x1, y1, z).setColor(r, g, b, 255);
        vertex(out, matrix, x0, y1, z).setColor(r, g, b, 255);
    }

    /** Render-thread scratch: VertexConsumer's matrix overload allocates a vector for every vertex. */
    private static VertexConsumer vertex(VertexConsumer out, Matrix4f matrix, float x, float y, float z) {
        matrix.transformPosition(x, y, z, VERTEX);
        return out.addVertex(VERTEX.x, VERTEX.y, VERTEX.z);
    }

    /** The six face normals (north, south, west, east, up, down) through {@code matrix}, into {@code out}. */
    private static float[] normals(Matrix4f matrix, float[] out) {
        Vector3f n = new Vector3f();
        for (int axis = 0; axis < 3; axis++) {
            matrix.transformDirection(n.set(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0)).normalize();
            // +X east, +Y up, +Z south; each face's is that or its opposite.
            int plus = axis == 0 ? 3 : axis == 1 ? 4 : 1;
            int minus = axis == 0 ? 2 : axis == 1 ? 5 : 0;
            out[plus * 3] = n.x;
            out[plus * 3 + 1] = n.y;
            out[plus * 3 + 2] = n.z;
            out[minus * 3] = -n.x;
            out[minus * 3 + 1] = -n.y;
            out[minus * 3 + 2] = -n.z;
        }
        return out;
    }

    private static void boxes(VertexConsumer out, Matrix4f matrix, float[] normals, float[] boxes, int light) {
        for (int i = 0; i < boxes.length; i += 7) {
            box(out, matrix, normals, boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5],
                    (int) boxes[i + 6], light);
        }
    }

    private static void box(VertexConsumer out, Matrix4f m, float[] n, float x0, float y0, float z0, float x1, float y1,
                            float z1, int color, int light) {
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        // North, south, west, east, up, down: four corners each, as the console's own boxes are wound.
        quad(out, m, n, 0, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b, light);
        quad(out, m, n, 1, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, r, g, b, light);
        quad(out, m, n, 2, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, r, g, b, light);
        quad(out, m, n, 3, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, light);
        quad(out, m, n, 4, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, light);
        quad(out, m, n, 5, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, r, g, b, light);
    }

    private static void quad(VertexConsumer out, Matrix4f m, float[] n, int face, float ax, float ay, float az,
                             float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
                             int r, int g, int b, int light) {
        float nx = n[face * 3], ny = n[face * 3 + 1], nz = n[face * 3 + 2];
        vertex(out, m, ax, ay, az).setColor(r, g, b, 255).setUv(0, 1).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(nx, ny, nz);
        vertex(out, m, bx, by, bz).setColor(r, g, b, 255).setUv(1, 1).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(nx, ny, nz);
        vertex(out, m, cx, cy, cz).setColor(r, g, b, 255).setUv(1, 0).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(nx, ny, nz);
        vertex(out, m, dx, dy, dz).setColor(r, g, b, 255).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(nx, ny, nz);
    }

    private static Vec3 point(Matrix4f matrix, Vec3 point) {
        Vector3f result = matrix.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Vec3(result.x, result.y, result.z);
    }

    private static Vec3 vector(Matrix4f matrix, Vec3 vector) {
        Vector3f result = matrix.transformDirection(new Vector3f((float) vector.x, (float) vector.y, (float) vector.z));
        return new Vec3(result.x, result.y, result.z);
    }

    @Override
    public boolean shouldRenderOffScreen(FloodlightBlock.Entity lamp) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return LightingSettings.MAX_RANGE + 32;
    }

    @Override
    public AABB getRenderBoundingBox(FloodlightBlock.Entity lamp) {
        AABB bounds = new AABB(lamp.getBlockPos()).inflate(LightingSettings.MAX_RANGE + 1);
        Level level = lamp.getLevel();
        return level == null ? bounds : SubLevels.toWorld(level, bounds);
    }
}
