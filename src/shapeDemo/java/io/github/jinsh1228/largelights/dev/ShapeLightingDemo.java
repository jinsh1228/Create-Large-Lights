package io.github.jinsh1228.largelights.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.ConsoleSettings;
import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.FloodlightConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import org.haruyq.createmediasystems.CreateMediaSystems;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingMonitorBlock;
import org.haruyq.createmediasystems.lighting.LightingRaycast;

/**
 * Dev only (-PshapeDemo): repeatable partial-block projection scene. Screenshots are taken externally through
 * Computer Use. Each camera remains still for several hundred ticks, then the game saves and exits.
 */
@EventBusSubscriber(modid = CreateLargeLights.MOD_ID, value = Dist.CLIENT)
public final class ShapeLightingDemo {
    private static final String WORLD = "ShapeLightingDemo";
    private static final int FLOOR = 150;
    private static final BlockPos LIGHT = new BlockPos(0, FLOOR + 8, -10);
    private static final BlockPos CONSOLE = new BlockPos(16, FLOOR + 1, -9);
    private static boolean started;
    private static int ticks = -1;
    private static int failures;

    private ShapeLightingDemo() {
    }

    private static boolean enabled() {
        return Boolean.getBoolean("createLargeLights.shapeDemo");
    }

    @SubscribeEvent
    public static void screen(ScreenEvent.Opening event) {
        if (!enabled() || started || !(event.getNewScreen() instanceof TitleScreen
                || event.getNewScreen() instanceof AccessibilityOnboardingScreen)) {
            return;
        }
        started = true;
        Minecraft mc = Minecraft.getInstance();
        mc.options.renderDistance().set(6);
        mc.options.gamma().set(0.8);
        mc.options.hideGui = true;
        mc.options.onboardAccessibility = false;
        mc.options.save();
        mc.execute(() -> {
            Path saves = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
            if (Files.exists(saves)) {
                try (Stream<Path> files = Files.walk(saves)) {
                    files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel(WORLD,
                    new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
                            WorldDataConfiguration.DEFAULT),
                    new WorldOptions(1L, false, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
            return;
        }
        ticks++;
        var server = mc.getSingleplayerServer();
        if (ticks == 20) {
            server.execute(() -> build(server.overworld()));
        } else if (ticks == 80) {
            server.execute(() -> {
                view(server, new Vec3(17.5, FLOOR + 7, -9.5), new Vec3(0, FLOOR + 1.5, 5));
                LogUtils.getLogger().info("SHAPE VIEW overview: ticks 80-799; lanes left-to-right bars, fence, bottom slab, top slab, stairs, cube, double slab");
            });
        } else if (ticks == 120) {
            server.execute(() -> link(server.overworld()));
        } else if (ticks == 240) {
            server.execute(() -> checkScene(server.overworld()));
        } else if (ticks == 360) {
            checkRenderingRays(mc);
        } else if (ticks == 800) {
            server.execute(() -> {
                view(server, new Vec3(-15.5, FLOOR + 4.5, -2.5), new Vec3(-9.5, FLOOR + 1.8, 5.5));
                LogUtils.getLogger().info("SHAPE VIEW bars and fence: ticks 800-1399");
            });
        } else if (ticks == 1400) {
            server.execute(() -> {
                view(server, new Vec3(8.5, FLOOR + 4.5, -2.5), new Vec3(0.5, FLOOR + 1, 5.5));
                LogUtils.getLogger().info("SHAPE VIEW slabs and stairs: ticks 1400-1999");
            });
        } else if (ticks == 2000) {
            LogUtils.getLogger().info("SHAPE demo done: {} checks failed", failures);
            mc.stop();
        }
    }

    private static void build(ServerLevel level) {
        level.setDayTime(18000);
        level.setWeatherParameters(6000, 0, false, false);
        FloodlightConfig.REAL_LIGHT.set(true);
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        for (int x = -20; x <= 20; x++) {
            for (int z = -14; z <= 18; z++) {
                level.setBlock(new BlockPos(x, FLOOR, z), Blocks.WHITE_CONCRETE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = FLOOR + 1; y < FLOOR + 14; y++) {
                    boolean edge = x == -20 || x == 20 || z == -14 || z == 18;
                    level.setBlock(new BlockPos(x, y, z), edge ? wall : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
                level.setBlock(new BlockPos(x, FLOOR + 14, z), wall, Block.UPDATE_CLIENTS);
            }
        }

        BlockState bars = connected(Blocks.IRON_BARS.defaultBlockState());
        BlockState fence = connected(Blocks.OAK_FENCE.defaultBlockState());
        for (int x = -13; x <= -11; x++) {
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
                set(level, x, y, 5, bars);
            }
        }
        for (int x = -9; x <= -7; x++) {
            for (int y = FLOOR + 1; y <= FLOOR + 2; y++) {
                set(level, x, y, 5, fence);
            }
        }
        for (int x = -5; x <= -3; x++) {
            set(level, x, FLOOR + 1, 5, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        }
        for (int x = -1; x <= 1; x++) {
            set(level, x, FLOOR + 1, 5,
                    Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        }
        for (int x = 3; x <= 5; x++) {
            set(level, x, FLOOR + 1, 5, Blocks.QUARTZ_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.SOUTH));
        }
        for (int x = 7; x <= 9; x++) {
            set(level, x, FLOOR + 1, 5, Blocks.WHITE_CONCRETE.defaultBlockState());
        }
        for (int x = 11; x <= 13; x++) {
            set(level, x, FLOOR + 1, 5,
                    Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        }
        // A second row distinguishes partial top faces and thin glass from their full-block neighbours.
        for (int x = -5; x <= -3; x++) {
            set(level, x, FLOOR + 2, 9, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        }
        for (int x = -1; x <= 1; x++) {
            set(level, x, FLOOR + 2, 9,
                    Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH)
                            .setValue(StairBlock.HALF, Half.TOP));
        }
        for (int x = 3; x <= 5; x++) {
            set(level, x, FLOOR + 2, 9, connected(Blocks.GLASS_PANE.defaultBlockState()));
        }

        level.setBlock(LIGHT, CreateLargeLights.STADIUM_LIGHT.get().defaultBlockState()
                .setValue(FloodlightBlock.FACING, Direction.DOWN).setValue(FloodlightBlock.POWER, 15), Block.UPDATE_ALL);
        level.setBlock(LIGHT.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light) {
            float[] aim = Aim.toward(new Vec3(0.5, FLOOR + 1, 6).subtract(Vec3.atCenterOf(LIGHT)), 0);
            light.spread = 3;
            light.aim(aim[0], aim[1]);
        }
        buildConsole(level);
        LogUtils.getLogger().info("SHAPE scene built at Y{}; only fixture light, bars x-12, fence x-8, bottom slabs x-4, top slabs x0, stairs x4, cubes x8, double slabs x12", FLOOR);
    }

    private static BlockState connected(BlockState state) {
        return state.setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true)
                .setValue(BlockStateProperties.NORTH, false).setValue(BlockStateProperties.SOUTH, false);
    }

    private static void buildConsole(ServerLevel level) {
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
    }

    private static void link(ServerLevel level) {
        var console = LightingConsoleBlock.find(level, CONSOLE);
        check(console != null && console.formed, "lighting console formed");
        if (console == null || !console.formed || !(level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light)) {
            return;
        }
        console = console.controller();
        check(console.bind(light, 0), "stadium light linked");
        console.settings.master = 1;
        console.settings.enabled = true;
        var group = console.settings.groups[0];
        group.brightness = 1;
        group.range = 64;
        group.pan = 0;
        group.tilt = Aim.NEUTRAL_TILT;
        group.motion = 0;
        group.colorCount = 1;
        group.colors[0] = 0x55CCFF;
        group.colorCycle = false;
        group.audio = false;
        ConsoleSettings.setSpread(console, 0, 80);
        ConsoleSettings.setRealLight(console, 0, true);
        console.changed();
    }

    private static void checkScene(ServerLevel level) {
        check(level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity, "stadium light exists");
        if (level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light) {
            var beam = light.beam();
            check(beam.strength() == 1 && beam.console() != null, "cyan beam enabled");
            long[] sources = light.lightBlocks();
            check(sources.length > 0 && FloodlightConfig.REAL_LIGHT.get(), "fixture provides the real light sources");
            Vec3 forward = Aim.direction(beam.yaw(), beam.pitch());
            Vec3 origin = Vec3.atCenterOf(LIGHT).add(forward.scale(light.kind().lensZ + 0.01));
            double tangent = Math.tan(Math.toRadians(beam.spread()) / 2);
            boolean inside = true;
            for (long packed : sources) {
                BlockPos at = BlockPos.of(packed);
                double x = at.getX() + 0.5 - origin.x;
                double y = at.getY() + 0.5 - origin.y;
                double z = at.getZ() + 0.5 - origin.z;
                double along = x * forward.x + y * forward.y + z * forward.z;
                double radius = along * tangent;
                inside &= along > 0 && along < beam.range()
                        && Math.max(0, x * x + y * y + z * z - along * along) < radius * radius;
            }
            check(inside, "every real light source centre is inside the rendered beam");
            int allSources = 0;
            for (BlockPos at : BlockPos.betweenClosed(-20, FLOOR + 1, -14, 20, FLOOR + 13, 18)) {
                if (level.getBlockState(at).is(Blocks.LIGHT)) {
                    allSources++;
                }
            }
            check(allSources == sources.length, "the room has no light sources other than the fixture's " + sources.length);
        }
        check(level.getBlockState(new BlockPos(-12, FLOOR + 2, 5)).is(Blocks.IRON_BARS), "iron bars panel exists");
        check(level.getBlockState(new BlockPos(-8, FLOOR + 1, 5)).is(Blocks.OAK_FENCE), "connected fence panel exists");
        check(level.getBlockState(new BlockPos(-4, FLOOR + 1, 5)).getValue(SlabBlock.TYPE) == SlabType.BOTTOM,
                "bottom slab surface is half-height");
        check(level.getBlockState(new BlockPos(0, FLOOR + 1, 5)).getValue(SlabBlock.TYPE) == SlabType.TOP,
                "top slab has an open lower half");
        check(level.getBlockState(new BlockPos(4, FLOOR + 1, 5)).is(Blocks.QUARTZ_STAIRS), "stairs exist");
    }

    /** Exercise the installed CMS mixin against the actual baked models and their texture alpha. */
    private static void checkRenderingRays(Minecraft mc) {
        LightingRaycast rays = new LightingRaycast(mc.level, LIGHT, true);
        check(rays.visible(new Vec3(-11.5, FLOOR + 2.5, 3), new Vec3(-11.5, FLOOR + 2.5, 5.499)),
                "iron bar receiver is visible in front of its model, inside the larger collision proxy");

        int openings = 0;
        // Pixel centres in the vanilla texture's openings. Quarter-block coordinates lie on the edge of
        // a vertical bar's texel, where the model's UV shrink correctly makes the sample opaque.
        for (double x : new double[]{4.5 / 16, 11.5 / 16}) {
            for (double y : new double[]{4.5 / 16, 10.5 / 16}) {
                var hit = rays.clip(new Vec3(-12 + x, FLOOR + 2 + y, 3),
                        new Vec3(-12 + x, FLOOR + 2 + y, 7));
                if (hit.block().getType() == HitResult.Type.MISS) {
                    openings++;
                }
            }
        }
        check(openings == 4, "iron bar texture gaps transmit light (" + openings + " of 4 sampled rays)");
        check(rays.clip(new Vec3(-12 + 3.5 / 16, FLOOR + 2 + 4.5 / 16, 3),
                new Vec3(-12 + 3.5 / 16, FLOOR + 2 + 4.5 / 16, 7)).block().getType() == HitResult.Type.BLOCK,
                "iron bar opaque texels still block light");

        check(rays.clip(new Vec3(-8.1, FLOOR + 1.65, 3), new Vec3(-8.1, FLOOR + 1.65, 7))
                .block().getType() == HitResult.Type.MISS, "light passes through the fence rail gap");
        check(rays.clip(new Vec3(-8.1, FLOOR + 1.8, 3), new Vec3(-8.1, FLOOR + 1.8, 7))
                .block().getType() == HitResult.Type.BLOCK, "the fence rail still blocks light");

        Vec3 bottomAbove = new Vec3(-3.5, FLOOR + 4, 5.5);
        check(rays.visible(bottomAbove, new Vec3(-3.5, FLOOR + 1.501, 5.5)),
                "bottom slab receiver remains visible above its half-height surface");
        var bottom = rays.clip(bottomAbove, new Vec3(-3.5, FLOOR + 1.49, 5.5));
        check(bottom.block().getType() == HitResult.Type.BLOCK && Math.abs(bottom.point().y - (FLOOR + 1.5)) < 1e-6,
                "bottom slab stops a ray at the actual half-height surface");

        Vec3 topAbove = new Vec3(0.5, FLOOR + 4, 5.5);
        check(rays.visible(topAbove, new Vec3(0.5, FLOOR + 2.001, 5.5)),
                "top slab receiver remains visible above its upper surface");
        var top = rays.clip(topAbove, new Vec3(0.5, FLOOR + 1.99, 5.5));
        check(top.block().getType() == HitResult.Type.BLOCK && Math.abs(top.point().y - (FLOOR + 2)) < 1e-6,
                "top slab stops a ray at its actual upper surface");

        Vec3 stairAbove = new Vec3(4.5, FLOOR + 4, 5.25);
        check(rays.visible(stairAbove, new Vec3(4.5, FLOOR + 1.501, 5.25)),
                "the lower stair tread remains visible beside the raised step");
        var stair = rays.clip(stairAbove, new Vec3(4.5, FLOOR + 1.49, 5.25));
        check(stair.block().getType() == HitResult.Type.BLOCK && Math.abs(stair.point().y - (FLOOR + 1.5)) < 1e-6,
                "stairs stop a ray at the lower tread rather than the enclosing cube");
    }

    private static void check(boolean passed, String message) {
        if (!passed) {
            failures++;
        }
        LogUtils.getLogger().info("SHAPE CHECK {}: {}", passed ? "PASS" : "FAIL", message);
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
    }

    private static void view(net.minecraft.server.MinecraftServer server, Vec3 eye, Vec3 at) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        float[] aim = Aim.toward(at.subtract(eye), 0);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(server.overworld(), eye.x, eye.y - player.getEyeHeight(), eye.z, aim[0], aim[1]);
    }
}
