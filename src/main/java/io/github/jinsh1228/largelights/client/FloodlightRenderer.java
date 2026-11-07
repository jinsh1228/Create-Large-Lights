package io.github.jinsh1228.largelights.client;

import java.util.Map;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.haruyq.createmediasystems.lighting.LightingRaycast;
import org.haruyq.createmediasystems.lighting.LightingRenderer;
import org.haruyq.createmediasystems.lighting.LightingSettings;
import org.haruyq.createmediasystems.playback.AnalysisPlayback;
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
 * A floodlight's body, aimed as it is, and its light: Create: Media Systems lays it on the faces the beam falls on
 * (LightingIllumination), and a faint cone shows it in the air, stopped where each edge meets a block.
 */
public final class FloodlightRenderer implements BlockEntityRenderer<FloodlightBlock.Entity> {
    private static final RenderType BODY = RenderType.entityCutoutNoCull(
            CreateMediaSystems.id("textures/block/blu_ray_player_side.png"));
    /** Unlinked, on redstone: a hall's warm white. */
    public static final int WARM_WHITE = 0xFFF1DC;
    private static final int OFF_LENS = 0x1C232D;
    /** Of the overlay, with real light under it. */
    private static final float REAL_LIGHT_OVERLAY = 0.4f;
    private static final int EDGES = 16;
    private static final int STEPS = 8;
    private static final Map<FloodlightBlock.Entity, Trace> TRACES = new WeakHashMap<>();
    private static final Map<FloodlightBlock.Entity, AimMotion> MOTION = new WeakHashMap<>();

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
            seconds = AnalysisPlayback.apply(console, seconds);
        }
        float[] aim = aim(lamp, beam, seconds, partialTick);
        Matrix4f local = Aim.head(aim[0], aim[1]);
        Matrix4f head = new Matrix4f(model).mul(local);

        VertexConsumer body = buffers.getBuffer(BODY);
        float plate = stadium ? 0.46f : 0.3f;
        float stem = stadium ? 0.1f : 0.065f;
        box(body, mount, -plate, -0.5f, -plate, plate, -0.43f, plate, 0x5E626C, light);
        box(body, mount, -stem, -0.43f, -stem, stem, -0.06f, stem, 0x6E727E, light);
        box(body, head, -0.11f, -0.11f, -0.11f, 0.11f, 0.11f, 0.11f, 0x4C505A, light);
        float lensZ;
        if (stadium) {
            lensZ = 0.1f;
            box(body, head, -0.98f, -0.64f, -0.17f, 0.98f, 0.64f, 0.08f, 0x8D929E, light);
            box(body, head, -0.98f, -0.64f, 0.08f, -0.92f, 0.64f, 0.1f, 0xA9AEBA, light);
            box(body, head, 0.92f, -0.64f, 0.08f, 0.98f, 0.64f, 0.1f, 0xA9AEBA, light);
            box(body, head, -0.98f, -0.64f, 0.08f, 0.98f, -0.6f, 0.1f, 0xA9AEBA, light);
            box(body, head, -0.98f, 0.6f, 0.08f, 0.98f, 0.64f, 0.1f, 0xA9AEBA, light);
            box(body, head, -0.98f, -0.02f, 0.08f, 0.98f, 0.02f, 0.1f, 0xA9AEBA, light);
            box(body, head, -0.33f, -0.64f, 0.08f, -0.29f, 0.64f, 0.1f, 0xA9AEBA, light);
            box(body, head, 0.29f, -0.64f, 0.08f, 0.33f, 0.64f, 0.1f, 0xA9AEBA, light);
            for (int fin = 0; fin < 5; fin++) {
                float x = -0.8f + fin * 0.4f;
                box(body, head, x - 0.03f, -0.55f, -0.27f, x + 0.03f, 0.55f, -0.17f, 0x6E727E, light);
            }
        } else {
            lensZ = 0.15f;
            box(body, head, -0.4f, -0.4f, -0.2f, 0.4f, 0.4f, 0.13f, 0x8D929E, light);
            box(body, head, -0.43f, -0.43f, 0.13f, 0.43f, -0.36f, 0.17f, 0xA9AEBA, light);
            box(body, head, -0.43f, 0.36f, 0.13f, 0.43f, 0.43f, 0.17f, 0xA9AEBA, light);
            box(body, head, -0.43f, -0.36f, 0.13f, -0.36f, 0.36f, 0.17f, 0xA9AEBA, light);
            box(body, head, 0.36f, -0.36f, 0.13f, 0.43f, 0.36f, 0.17f, 0xA9AEBA, light);
            for (int fin = 0; fin < 4; fin++) {
                float y = -0.3f + fin * 0.2f;
                box(body, head, -0.34f, y - 0.025f, -0.29f, 0.34f, y + 0.025f, -0.2f, 0x6E727E, light);
            }
        }
        if (LightingRenderer.shadowPass()) {
            return;
        }

        float strength = beam.strength();
        int color = WARM_WHITE;
        if (console != null) {
            LightingSettings.Group group = beam.group();
            boolean signal = level.getGameTime() - console.signalAt < 30;
            if (group.audio && console.hasAudio && signal) {
                float energy = switch (group.audioBand) {
                    case 1 -> console.bass;
                    case 2 -> console.mid;
                    case 3 -> console.high;
                    default -> console.audioLevel;
                };
                strength *= Mth.clamp(0.12f + energy * 1.5f + console.pulse * 0.35f, 0, 1);
            }
            boolean video = console.hasVideo && signal;
            color = LightingSettings.color(group, lamp.order, Math.max(1, console.groupSize(lamp.group)), seconds,
                    console.videoLeft, console.videoRight, video);
            if (group.mapping == 3 && !(group.colorMode == 1 && video)) {
                color = group.colors[Math.floorMod(lamp.individualColor, group.colorCount)];
            }
        }

        VertexConsumer lens = buffers.getBuffer(LightingRenderer.lensType());
        int lensColor = strength > 0.001f ? LightingSettings.mix(color, 0xFFFFFF, 0.35f) : OFF_LENS;
        if (stadium) {
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 3; col++) {
                    float x = -0.62f + col * 0.62f;
                    float y = -0.31f + row * 0.62f;
                    lensQuad(lens, head, x - 0.26f, y - 0.26f, x + 0.26f, y + 0.26f, lensZ, lensColor);
                }
            }
        } else {
            lensQuad(lens, head, -0.36f, -0.36f, 0.36f, 0.36f, lensZ, lensColor);
        }
        if (strength <= 0.001f) {
            return;
        }

        Vec3 origin = point(local, new Vec3(0, 0, lensZ + 0.01));
        Vec3 direction = vector(local, new Vec3(0, 0, 1)).normalize();
        Vec3 right = vector(local, new Vec3(1, 0, 0)).normalize();
        Vec3 up = vector(local, new Vec3(0, 1, 0)).normalize();
        double halfAngle = Math.toRadians(beam.spread()) / 2;
        LightingRaycast rays = new LightingRaycast(level, lamp.getBlockPos(), true);
        Trace trace = TRACES.computeIfAbsent(lamp, ignored -> new Trace());
        trace.update(lamp, rays, origin, direction, right, up, Math.tan(halfAngle), beam.range());
        MultiBufferSource effects = LightingRenderer.beamBuffers(level, buffers);
        LightingIllumination.render(lamp, origin, direction, halfAngle, beam.range(), strength * overlay(kind), color,
                trace.center,
                rays, model, effects, kind.radius);
        cone(effects.getBuffer(LightingRenderer.beamType()), model, trace, origin, right, up,
                stadium ? 0.85f : 0.36f, stadium ? 0.55f : 0.36f, color, strength * kind.haze);
    }

    /**
     * Where the head points this frame: its aim with the console's pan and tilt (Beam), and, linked, its group's
     * motion as a stage light's (a sway, a circle, or following the target player), eased as a stage light's head.
     */
    private static float[] aim(FloodlightBlock.Entity lamp, FloodlightBlock.Entity.Beam beam, double seconds,
                               float partialTick) {
        float yaw = beam.yaw();
        float pitch = beam.pitch();
        boolean hold = false;
        LightingConsoleBlock.Entity console = beam.console();
        LightingSettings.Group group = beam.group();
        if (console != null && group != null) {
            double phase = (group.audio && console.audioSource != null ? console.motionPhase : seconds) * group.speed
                    + lamp.order * 0.35;
            if (group.motion == 1) {
                yaw += (float) Math.sin(phase) * 55;
            } else if (group.motion == 2) {
                yaw += (float) Math.sin(phase) * 48;
                pitch += (float) Math.cos(phase) * 22;
            } else if (group.motion == 3) {
                // Following: straight at the target player; with none, held where it is.
                hold = true;
                Player target = group.target == null ? null : lamp.getLevel().getPlayerByUUID(group.target);
                if (target != null) {
                    Vec3 body = target.getPosition(partialTick).add(0, target.getBbHeight() * 0.5, 0);
                    Vec3 toward = SableCompat.toRenderBlockSpace(lamp.getLevel(), lamp.getBlockPos(), body)
                            .subtract(Vec3.atCenterOf(lamp.getBlockPos()));
                    if (toward.lengthSqr() > 0.25) {
                        float[] at = Aim.toward(toward, yaw);
                        yaw = at[0];
                        pitch = at[1];
                        hold = false;
                    }
                }
            }
        }
        return MOTION.computeIfAbsent(lamp, ignored -> new AimMotion())
                .update(seconds, yaw, Mth.clamp(pitch, Aim.MIN_PITCH, Aim.MAX_PITCH), hold);
    }

    /**
     * How strongly the beam is laid over what it falls on. With real light (RealLight; the server's config reaches
     * the client) the world is already lit there, so the overlay only shows the beam's colour and shape.
     */
    private static float overlay(FloodlightKind kind) {
        boolean real = FloodlightConfig.SPEC.isLoaded() && FloodlightConfig.REAL_LIGHT.get();
        return kind.intensity * (real ? REAL_LIGHT_OVERLAY : 1);
    }

    /** Where the beam's middle and edges meet blocks, traced again only when something that matters changed. */
    private static final class Trace {
        LightingRaycast.Hit center;
        final Vec3[] edges = new Vec3[EDGES];
        final double[] lengths = new double[EDGES];
        long traced = -2;
        /** Where the beam left from in the world: it moves with a Sable structure the floodlight is on. */
        Vec3 lastFrom;
        Vec3 lastOrigin;
        Vec3 lastDirection;
        double lastTangent;

        void update(FloodlightBlock.Entity lamp, LightingRaycast rays, Vec3 origin, Vec3 direction, Vec3 right,
                    Vec3 up, double tangent, float range) {
            long frame = LightingIllumination.frame();
            Level level = lamp.getLevel();
            Vec3 anchor = Vec3.atLowerCornerOf(lamp.getBlockPos());
            Vec3 from = SableCompat.toRenderWorld(level, lamp.getBlockPos(), origin.add(anchor));
            // Off the world (a Ponder scene), every frame; in it, again when a block changes or the light moves.
            boolean still = center != null && frame - traced <= 1 && level == Minecraft.getInstance().level
                    && !LightingIllumination.blocksChanged() && from.equals(lastFrom)
                    && origin.equals(lastOrigin) && direction.equals(lastDirection) && tangent == lastTangent;
            traced = frame;
            if (still) {
                return;
            }
            lastFrom = from;
            lastOrigin = origin;
            lastDirection = direction;
            lastTangent = tangent;
            center = rays.clip(from, SableCompat.toRenderWorld(level, lamp.getBlockPos(),
                    origin.add(direction.scale(range)).add(anchor)));
            for (int i = 0; i < EDGES; i++) {
                double angle = Math.PI * 2 * i / EDGES;
                Vec3 aim = direction.add(right.scale(Math.cos(angle) * tangent)).add(up.scale(Math.sin(angle) * tangent))
                        .normalize();
                edges[i] = aim;
                LightingRaycast.Hit hit = rays.clip(from, SableCompat.toRenderWorld(level, lamp.getBlockPos(),
                        origin.add(aim.scale(range)).add(anchor)));
                lengths[i] = hit.block().getType() == HitResult.Type.BLOCK
                        ? SableCompat.toRenderBlockSpace(level, lamp.getBlockPos(), hit.point()).subtract(anchor)
                        .distanceTo(origin) : range;
            }
        }
    }

    /**
     * The beam in the air: from the lens's outline ({@code width} by {@code height} across) out along each edge,
     * each strip as far as the nearer of its two edges reaches, fading as it goes.
     */
    private static void cone(VertexConsumer out, Matrix4f matrix, Trace trace, Vec3 origin, Vec3 right, Vec3 up,
                             float width, float height, int color, float alpha) {
        if (alpha < 1) {
            return;
        }
        for (int i = 0; i < EDGES; i++) {
            int next = (i + 1) % EDGES;
            double length = Math.min(trace.lengths[i], trace.lengths[next]);
            Vec3 startA = rim(origin, right, up, width, height, i);
            Vec3 startB = rim(origin, right, up, width, height, next);
            for (int step = 0; step < STEPS; step++) {
                double t0 = length * step / STEPS;
                double t1 = length * (step + 1) / STEPS;
                int alpha0 = Math.round(alpha * (1 - 0.85f * step / STEPS));
                int alpha1 = Math.round(alpha * (1 - 0.85f * (step + 1) / STEPS));
                vertex(out, matrix, startA.add(trace.edges[i].scale(t0)), color, alpha0);
                vertex(out, matrix, startB.add(trace.edges[next].scale(t0)), color, alpha0);
                vertex(out, matrix, startB.add(trace.edges[next].scale(t1)), color, alpha1);
                vertex(out, matrix, startA.add(trace.edges[i].scale(t1)), color, alpha1);
            }
        }
    }

    private static Vec3 rim(Vec3 origin, Vec3 right, Vec3 up, float width, float height, int edge) {
        double angle = Math.PI * 2 * edge / EDGES;
        return origin.add(right.scale(Math.cos(angle) * width)).add(up.scale(Math.sin(angle) * height));
    }

    private static void lensQuad(VertexConsumer out, Matrix4f matrix, float x0, float y0, float x1, float y1, float z,
                                 int color) {
        vertex(out, matrix, new Vec3(x0, y0, z), color, 255);
        vertex(out, matrix, new Vec3(x1, y0, z), color, 255);
        vertex(out, matrix, new Vec3(x1, y1, z), color, 255);
        vertex(out, matrix, new Vec3(x0, y1, z), color, 255);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 at, int color, int alpha) {
        out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z)
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, Mth.clamp(alpha, 0, 255));
    }

    private static void box(VertexConsumer out, Matrix4f matrix, float x0, float y0, float z0, float x1, float y1,
                            float z1, int color, int light) {
        Vec3 a = new Vec3(x0, y0, z0), b = new Vec3(x1, y0, z0), c = new Vec3(x1, y1, z0), d = new Vec3(x0, y1, z0);
        Vec3 e = new Vec3(x0, y0, z1), f = new Vec3(x1, y0, z1), g = new Vec3(x1, y1, z1), h = new Vec3(x0, y1, z1);
        face(out, matrix, a, b, c, d, color, light, Direction.NORTH);
        face(out, matrix, f, e, h, g, color, light, Direction.SOUTH);
        face(out, matrix, e, a, d, h, color, light, Direction.WEST);
        face(out, matrix, b, f, g, c, color, light, Direction.EAST);
        face(out, matrix, d, c, g, h, color, light, Direction.UP);
        face(out, matrix, e, f, b, a, color, light, Direction.DOWN);
    }

    private static void face(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, int light,
                             Direction side) {
        Vector3f normal = matrix.transformDirection(new Vector3f(side.getStepX(), side.getStepY(), side.getStepZ()))
                .normalize();
        Vec3[] corners = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            Vec3 at = corners[i];
            out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z)
                    .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255)
                    .setUv(i == 0 || i == 3 ? 0 : 1, i < 2 ? 1 : 0).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light).setNormal(normal.x, normal.y, normal.z);
        }
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
        return lamp.getRenderBoundingBox();
    }
}
