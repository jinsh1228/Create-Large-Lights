package io.github.jinsh1228.largelights.dev;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import com.mojang.logging.LogUtils;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.client.GpuLightGeometry;
import io.github.jinsh1228.largelights.client.GpuLightProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

/** Opt-in production renderer checks; no prototype renderer or shaders are used. */
final class GpuProductionDemo {
    private static final boolean ENABLED = Boolean.getBoolean("createLargeLights.productionDemo");
    private static final BlockPos LIGHT = new BlockPos(0, 158, -10);
    private static final BlockPos OBSTRUCTION = new BlockPos(0, 153, 2);
    private static final int TEST_COLOR = 0xFF66AA;
    private static int stage, stageAt, prepareAt = -1, stableAt, failures;
    private static long previousShadows = -1;
    private static boolean fadeReady;
    private static GpuLightProjection.Metrics fadeBefore, before;
    private static volatile BlockState oldBlock;
    private static volatile float oldYaw, oldPitch;
    private static volatile int oldColor;
    private static CompletableFuture<Void> reload;

    private GpuProductionDemo() {
    }

    static boolean enabled() {
        return ENABLED;
    }

    /** Let meshes and the smoothed head settle before measuring a colour/intensity-only fade. */
    static boolean readyForFade(Minecraft mc, int ticks) {
        if (!ENABLED) return true;
        if (prepareAt < 0) {
            prepareAt = ticks;
            baselineErrors();
        }
        var metrics = GpuLightProjection.metrics();
        if (metrics.shadowUpdates() != previousShadows) {
            previousShadows = metrics.shadowUpdates();
            stableAt = ticks;
        }
        fadeReady = metrics.drawnLights() > 0 && metrics.surfaceDraws() > 0 && ticks - stableAt >= 20;
        if (!fadeReady && ticks - prepareAt < 240) return false;
        check(fadeReady, "production GPU projection becomes ready and keeps its static shadow", metrics);
        check(metrics.cachedShadows() <= 16 && metrics.depthBytes() <= 64L * 1024 * 1024,
                "shadow cache remains within 16 maps / 64 MiB", metrics);
        check(GpuLightGeometry.bytes() <= 64L * 1024 * 1024, "shared geometry stays within 64 MiB", metrics);
        errors("ready");
        return true;
    }

    static void fadeStarted() {
        if (ENABLED) fadeBefore = GpuLightProjection.metrics();
    }

    static void fadeFinished(Minecraft mc, int ticks) {
        if (!ENABLED) return;
        var metrics = GpuLightProjection.metrics();
        check(fadeReady && metrics.drawnLights() > 0 && metrics.surfaceDraws() > fadeBefore.surfaceDraws(),
                "production GPU surfaces continue drawing during the actual console fade", metrics);
        check(metrics.shadowUpdates() == fadeBefore.shadowUpdates(),
                "intensity-only fade reuses its shadow map", metrics);
        errors("fade");
        advance(1, ticks);
        mc.getSingleplayerServer().execute(() -> {
            if (mc.getSingleplayerServer().overworld().getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light) {
                oldYaw = light.yaw;
                oldPitch = light.pitch;
                var group = light.beam().group();
                oldColor = group.colors[0];
                group.colors[0] = TEST_COLOR;
                light.beam().console().changed();
            }
        });
    }

    /** Runs before ShapeDemo's null-world guard, so unload cleanup can also be verified. */
    static void tick(Minecraft mc, int ticks, boolean fieldComplete, int sceneFailures) {
        if (!ENABLED || ticks < 0) return;
        if (stage == 10) {
            if (mc.level == null) {
                var metrics = GpuLightProjection.metrics();
                check(metrics.cachedShadows() == 0 && metrics.depthBytes() == 0 && GpuLightGeometry.bytes() == 0,
                        "world unload releases shadow maps and shared geometry", metrics);
                errors("world unload");
                LogUtils.getLogger().info("GPU_PRODUCTION demo done: {} checks failed (scene={}, gpu={})",
                        sceneFailures + failures, sceneFailures, failures);
                stage = 11;
                mc.stop();
            }
            return;
        }
        if (stage > 0 && stage < 10 && mc.level == null) {
            check(false, "world stays available during production checks", GpuLightProjection.metrics());
            LogUtils.getLogger().info("GPU_PRODUCTION demo done: {} checks failed (scene={}, gpu={})",
                    sceneFailures + failures, sceneFailures, failures);
            stage = 11;
            mc.stop();
            return;
        }
        if (stage == 11 || mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (ticks >= 1800) {
            check(false, "production checks completed before the timeout", GpuLightProjection.metrics());
            check(fieldComplete, "original field/model checks completed", GpuLightProjection.metrics());
            stage = 10;
            mc.level.disconnect();
            mc.disconnect();
            return;
        }
        var metrics = GpuLightProjection.metrics();
        switch (stage) {
            case 1 -> {
                if (!reached(clientColor(mc) == TEST_COLOR && ticks - stageAt >= 20, ticks, "colour update reaches the client")) return;
                check(metrics.shadowUpdates() == before.shadowUpdates() && metrics.surfaceDraws() > before.surfaceDraws(),
                        "colour-only update redraws surfaces without regenerating shadows", metrics);
                errors("colour");
                advance(2, ticks);
                mc.getSingleplayerServer().execute(() -> {
                    var light = (FloodlightBlock.Entity) mc.getSingleplayerServer().overworld().getBlockEntity(LIGHT);
                    light.beam().group().colors[0] = oldColor;
                    light.beam().console().changed();
                });
            }
            case 2 -> {
                if (!reached(clientColor(mc) == oldColor && ticks - stageAt >= 20, ticks, "original colour is restored")) return;
                check(metrics.shadowUpdates() == before.shadowUpdates(), "restoring colour keeps the shadow cached", metrics);
                advance(3, ticks);
                mc.getSingleplayerServer().execute(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    oldBlock = level.getBlockState(OBSTRUCTION);
                    level.setBlock(OBSTRUCTION, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                });
            }
            case 3 -> {
                if (!reached(mc.level.getBlockState(OBSTRUCTION).is(Blocks.STONE)
                        && metrics.drawnLights() > 0 && metrics.shadowUpdates() > before.shadowUpdates(), ticks,
                        "placing a beam obstruction regenerates the GPU shadow")) return;
                errors("block placed");
                advance(4, ticks);
                mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld()
                        .setBlock(OBSTRUCTION, oldBlock, Block.UPDATE_ALL));
            }
            case 4 -> {
                if (!reached(oldBlock != null && mc.level.getBlockState(OBSTRUCTION) == oldBlock
                        && metrics.drawnLights() > 0 && metrics.shadowUpdates() > before.shadowUpdates(), ticks,
                        "removing the obstruction regenerates the GPU shadow")) return;
                errors("block removed");
                advance(5, ticks);
                mc.getSingleplayerServer().execute(() -> ((FloodlightBlock.Entity) mc.getSingleplayerServer()
                        .overworld().getBlockEntity(LIGHT)).aim(oldYaw + 12, oldPitch));
            }
            case 5 -> {
                if (!reached(mc.level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light && light.yaw == oldYaw + 12
                        && metrics.drawnLights() > 0 && metrics.shadowUpdates() > before.shadowUpdates(), ticks,
                        "changing the fixture aim updates its GPU shadow")) return;
                errors("aim changed");
                advance(6, ticks);
                mc.getSingleplayerServer().execute(() -> ((FloodlightBlock.Entity) mc.getSingleplayerServer()
                        .overworld().getBlockEntity(LIGHT)).aim(oldYaw, oldPitch));
            }
            case 6 -> {
                if (metrics.shadowUpdates() != previousShadows) {
                    previousShadows = metrics.shadowUpdates();
                    stableAt = ticks;
                }
                if (!reached(mc.level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light && light.yaw == oldYaw
                        && metrics.drawnLights() > 0 && metrics.shadowUpdates() > before.shadowUpdates()
                        && ticks - stableAt >= 20, ticks, "restored aim and its shadow settle before resource reload")) return;
                advance(7, ticks);
                reload = mc.reloadResourcePacks();
                LogUtils.getLogger().info("GPU_PRODUCTION resource reload requested");
            }
            case 7 -> {
                if (!reached(reload.isDone() && !reload.isCompletedExceptionally() && mc.getOverlay() == null && metrics.drawnLights() > 0
                        && metrics.cachedShadows() > 0 && metrics.surfaceDraws() > 0, ticks,
                        "production GPU rendering recovers after resource reload")) return;
                errors("resource reload");
                mc.getSingleplayerServer().execute(() -> {
                    var light = (FloodlightBlock.Entity) mc.getSingleplayerServer().overworld().getBlockEntity(LIGHT);
                    light.beam().group().brightness = 1;
                    light.beam().console().changed();
                });
                advance(8, ticks);
                view(mc, new Vec3(-15.5, 154.5, -2.5), new Vec3(-9.5, 151.8, 5.5));
                LogUtils.getLogger().info("GPU_PRODUCTION VIEW bars/fences: next 80 ticks");
            }
            case 8 -> {
                if (ticks - stageAt < 80) return;
                advance(9, ticks);
                view(mc, new Vec3(8.5, 154.5, -2.5), new Vec3(.5, 151, 5.5));
                LogUtils.getLogger().info("GPU_PRODUCTION VIEW slabs/stairs: next 80 ticks");
            }
            case 9 -> {
                if (ticks - stageAt < 80 || !fieldComplete) return;
                check(metrics.drawnLights() > 0, "GPU projection remains active after camera changes", metrics);
                errors("camera views");
                LogUtils.getLogger().info("GPU_PRODUCTION world unload requested");
                stage = 10;
                mc.level.disconnect();
                mc.disconnect();
            }
            default -> { }
        }
    }

    private static int clientColor(Minecraft mc) {
        return mc.level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light && light.beam().group() != null
                ? light.beam().group().colors[0] : -1;
    }

    private static void advance(int next, int ticks) {
        stage = next;
        stageAt = ticks;
        before = GpuLightProjection.metrics();
        previousShadows = before.shadowUpdates();
        stableAt = ticks;
    }

    private static boolean reached(boolean passed, int ticks, String message) {
        if (!passed && ticks - stageAt < (stage == 7 ? 360 : 180)) return false;
        check(passed, message, GpuLightProjection.metrics());
        return true;
    }

    private static void view(Minecraft mc, Vec3 eye, Vec3 target) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            float[] aim = Aim.toward(target.subtract(eye), 0);
            player.teleportTo(server.overworld(), eye.x, eye.y - player.getEyeHeight(), eye.z, aim[0], aim[1]);
        });
    }

    private static void baselineErrors() {
        var errors = new ArrayList<Integer>();
        for (int count = 0, error; count < 8 && (error = GL11.glGetError()) != GL11.GL_NO_ERROR; count++) errors.add(error);
        LogUtils.getLogger().info("GPU_PRODUCTION pre-check GL error baseline: {}", errors);
    }

    private static void errors(String phase) {
        int error = GL11.glGetError();
        check(error == GL11.GL_NO_ERROR, "GL error after " + phase + " = " + error, GpuLightProjection.metrics());
    }

    private static void check(boolean passed, String message, GpuLightProjection.Metrics metrics) {
        if (!passed) failures++;
        LogUtils.getLogger().info("GPU_PRODUCTION CHECK {}: {}; metrics={}", passed ? "PASS" : "FAIL", message, metrics);
    }
}
