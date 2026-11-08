package io.github.jinsh1228.largelights.dev;

import java.util.Arrays;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingMonitorBlock;
import org.lwjgl.opengl.GL11;

import com.mojang.logging.LogUtils;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.ConsoleSettings;
import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.FloodlightConfig;
import io.github.jinsh1228.largelights.LightField;
import io.github.jinsh1228.largelights.client.GpuLightGeometry;
import io.github.jinsh1228.largelights.client.GpuLightProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Opt-in long-distance quality scene. The production renderer and its ordinary 1024 shadow maps are unchanged. */
@EventBusSubscriber(modid = CreateLargeLights.MOD_ID, value = Dist.CLIENT)
public final class GpuFarDemo {
    private static final boolean ENABLED = Boolean.getBoolean("createLargeLights.farDemo");
    private static final int DISTANCE = Integer.getInteger("createLargeLights.farDistance", 128) == 256 ? 256 : 128;
    private static final int FLOOR = 310 - DISTANCE;
    private static final BlockPos LIGHT = new BlockPos(0, 310, 0);
    private static final BlockPos CONSOLE = new BlockPos(8, 309, -6);
    private static final double[] frames = new double[20000];
    private static volatile boolean built, linked;
    private static volatile int failures;
    private static int ticks, stage, stageAt, stableAt, frameCount;
    private static long previousShadow = -1, previousFrame;

    private GpuFarDemo() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void tick(Minecraft mc) {
        if (stage == 4) {
            if (mc.level == null) {
                check(GpuLightProjection.metrics().depthBytes() == 0 && GpuLightGeometry.bytes() == 0,
                        "world unload releases GPU resources");
                LogUtils.getLogger().info("GPU_FAR demo done: {} checks failed; distance={}", failures, DISTANCE);
                stage = 5;
                mc.stop();
            }
            return;
        }
        if (stage == 5 || mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        ticks++;
        var server = mc.getSingleplayerServer();
        if (ticks == 1) {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.save();
        }
        if (ticks == 20) {
            for (int count = 0; count < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; count++) { }
            server.execute(() -> build(server.overworld()));
        }
        if (built && !linked && ticks % 20 == 0) server.execute(() -> link(server.overworld()));
        if (ticks >= 1000) {
            check(false, "far scene finishes before its timeout");
            stage = 4;
            disconnect(mc);
            return;
        }
        var metrics = GpuLightProjection.metrics();
        if (metrics.shadowUpdates() != previousShadow) {
            previousShadow = metrics.shadowUpdates();
            stableAt = ticks;
        }
        if (stage == 0) {
            if (!linked || !(mc.level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light)) return;
            var beam = light.beam();
            if (beam.console() == null || beam.group() == null || beam.range() != 300 || beam.spread() != 80
                    || beam.strength() != 1 || beam.group().colors[0] != 0x55CCFF || metrics.drawnLights() == 0
                    || ticks - stableAt < 20) return;
            check(!FloodlightConfig.REAL_LIGHT.get() && LightField.sample(mc.level, new BlockPos(7, FLOOR + 1, -7)) == 0,
                    "real light is disabled; only the production surface projection is measured");
            check(mc.level.getFluidState(BlockPos.containing(mc.player.getEyePosition())).isEmpty(),
                    "receiver camera is in dry air");
            check(metrics.cachedShadows() <= 16 && metrics.depthBytes() <= 64L << 20,
                    "production shadow cache stays within 16 maps / 64 MiB");
            check(GpuLightGeometry.bytes() <= 64L << 20, "shared geometry stays within 64 MiB");
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "production rendering has no GL error");
            double coneWidth = 2 * DISTANCE * Math.tan(Math.toRadians(40));
            LogUtils.getLogger().info("GPU_FAR CONFIG distance={} source_y=310 floor_y={} range=300 spread=80 color=55CCFF strength=1 realLight=false render_distance=6 vsync=false fps_setting=260 random_ticks=0 radius_cap=48 nominal_world_units_per_shadow_texel={} nominal_capped_footprint_map_fraction={} geometry_bytes={} metrics={}",
                    DISTANCE, FLOOR, 2 * DISTANCE * Math.tan(Math.toRadians(40)) / 1024,
                    Math.PI * 48 * 48 / (coneWidth * coneWidth),
                    GpuLightGeometry.bytes(), metrics);
            advance(mc, 1, "overview", new Vec3(11.5, FLOOR + 9, -11.5), new Vec3(0, FLOOR + 1, 0));
        } else if (ticks - stageAt >= 90) {
            report();
            if (stage == 1) {
                advance(mc, 2, "bars", new Vec3(-9.5, FLOOR + 5, -9.5), new Vec3(-4, FLOOR + 1.6, 0));
            } else if (stage == 2) {
                advance(mc, 3, "slabs", new Vec3(9.5, FLOOR + 4.5, -8.5), new Vec3(4, FLOOR + 1, 0));
            } else {
                check(metrics.drawnLights() > 0, "production projection remains active for every view");
                check(GL11.glGetError() == GL11.GL_NO_ERROR, "no GL error after the far camera views");
                stage = 4;
                disconnect(mc);
            }
        }
    }

    private static void build(ServerLevel level) {
        level.setDayTime(18000);
        level.setWeatherParameters(6000, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, level.getServer());
        FloodlightConfig.REAL_LIGHT.set(false);
        if (DISTANCE == 256) {
            // Seal before draining: the normal-world sea can otherwise refill the test column and churn meshes.
            for (int x = -13; x <= 13; x++) {
                for (int z = -13; z <= 13; z++) {
                    level.setBlock(new BlockPos(x, FLOOR - 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    if (Math.abs(x) == 13 || Math.abs(z) == 13) {
                        for (int y = FLOOR; y <= 120; y++) {
                            level.setBlock(new BlockPos(x, y, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    }
                }
            }
            for (BlockPos pos : BlockPos.betweenClosed(-12, FLOOR, -12, 12, 120, 12)) {
                if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int x = -8; x < 8; x++) {
            for (int z = -8; z < 8; z++) {
                level.setBlock(new BlockPos(x, FLOOR, z), Blocks.WHITE_CONCRETE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int z = -4; z <= 4; z++) {
            for (int y = 1; y <= 3; y++) set(level, -6, y, z, Blocks.IRON_BARS.defaultBlockState());
            set(level, -3, 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
            set(level, 0, 1, z, Blocks.OAK_FENCE.defaultBlockState());
            set(level, 3, 1, z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, z <= 0 ? SlabType.BOTTOM : SlabType.TOP));
            set(level, 6, 1, z, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        }
        set(level, 3, 1, 6, Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        set(level, 6, 1, 6, Blocks.WHITE_CONCRETE.defaultBlockState());
        level.setBlock(LIGHT, CreateLargeLights.STADIUM_LIGHT.get().defaultBlockState()
                .setValue(FloodlightBlock.FACING, Direction.DOWN).setValue(FloodlightBlock.POWER, 15), Block.UPDATE_ALL);
        level.setBlock(LIGHT.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light) {
            light.spread = 3;
            light.aim(0, 90);
        }
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                level.setBlock(LightingConsoleBlock.at(CONSOLE, Direction.NORTH, x, 0, z),
                        CreateMediaSystems.LIGHTING_CONSOLE.get().defaultBlockState()
                                .setValue(LightingConsoleBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
            }
            level.setBlock(LightingConsoleBlock.at(CONSOLE, Direction.NORTH, x, 1, 1),
                    CreateMediaSystems.LIGHTING_MONITOR.get().defaultBlockState()
                            .setValue(LightingMonitorBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
        }
        // Begin at the receiver: production registration must handle a source outside native vertical compilation.
        view(level, new Vec3(11.5, FLOOR + 9, -11.5), new Vec3(0, FLOOR + 1, 0));
        built = true;
        LogUtils.getLogger().info("GPU_FAR scene built distance={} floor_y={} source_y=310", DISTANCE, FLOOR);
    }

    private static void link(ServerLevel level) {
        var console = LightingConsoleBlock.find(level, CONSOLE);
        if (console == null || !console.formed || !(level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light)) return;
        console = console.controller();
        if (!console.bind(light, 0)) return;
        console.settings.master = 1;
        console.settings.enabled = true;
        var group = console.settings.groups[0];
        group.brightness = 1;
        group.range = 300;
        group.motion = 0;
        group.pan = 0;
        group.tilt = Aim.NEUTRAL_TILT;
        group.audio = false;
        group.colorCount = 1;
        group.colors[0] = 0x55CCFF;
        group.colorCycle = false;
        ConsoleSettings.setSpread(console, 0, 80);
        ConsoleSettings.setRealLight(console, 0, false);
        console.changed();
        linked = true;
        LogUtils.getLogger().info("GPU_FAR console linked distance={} range=300 spread=80 realLight=false", DISTANCE);
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, FLOOR + y, z), state, Block.UPDATE_ALL);
    }

    private static void disconnect(Minecraft mc) {
        mc.level.disconnect();
        mc.disconnect();
    }

    private static void advance(Minecraft mc, int next, String name, Vec3 eye, Vec3 target) {
        stage = next;
        stageAt = ticks;
        frameCount = 0;
        previousFrame = 0;
        mc.getSingleplayerServer().execute(() -> view(mc.getSingleplayerServer().overworld(), eye, target));
        LogUtils.getLogger().info("GPU_FAR VIEW {} distance={} capture_next_90_ticks=true", name, DISTANCE);
    }

    private static void view(ServerLevel level, Vec3 eye, Vec3 target) {
        var player = level.getServer().getPlayerList().getPlayers().getFirst();
        float[] aim = Aim.toward(target.subtract(eye), 0);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(level, eye.x, eye.y - player.getEyeHeight(), eye.z, aim[0], aim[1]);
    }

    @SubscribeEvent
    public static void frame(RenderLevelStageEvent event) {
        if (!ENABLED || stage < 1 || stage > 3 || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        long now = System.nanoTime();
        if (previousFrame != 0 && frameCount < frames.length) frames[frameCount++] = (now - previousFrame) / 1e6;
        previousFrame = now;
    }

    private static void report() {
        if (frameCount == 0) return;
        double[] sorted = Arrays.copyOf(frames, frameCount);
        Arrays.sort(sorted);
        LogUtils.getLogger().info("GPU_FAR FRAME distance={} view={} samples={} p50_ms={} p95_ms={} metrics={}", DISTANCE, stage,
                frameCount, sorted[sorted.length / 2], sorted[Math.min(sorted.length - 1, (int) (sorted.length * .95))],
                GpuLightProjection.metrics());
    }

    private static synchronized void check(boolean passed, String message) {
        if (!passed) failures++;
        LogUtils.getLogger().info("GPU_FAR CHECK {}: {}", passed ? "PASS" : "FAIL", message);
    }
}
