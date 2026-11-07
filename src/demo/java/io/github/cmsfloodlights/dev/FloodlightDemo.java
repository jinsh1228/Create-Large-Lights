package io.github.cmsfloodlights.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;

import io.github.cmsfloodlights.Aim;
import io.github.cmsfloodlights.CmsFloodlights;
import io.github.cmsfloodlights.FloodlightBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Dev only (-Pdemo): a gym 28 blocks tall, far past the 15 blocks a vanilla light reaches, lit by ceiling floodlights
 * and wall stadium lights on redstone; photographed lit, unlit, and from the floor, then the game closes.
 */
@EventBusSubscriber(modid = CmsFloodlights.MOD_ID, value = Dist.CLIENT)
public final class FloodlightDemo {
    private static final String WORLD = "FloodlightDemo";
    private static final int FLOOR = 150;
    private static final int CEILING = 178;
    private static final int HALF = 30;
    private static final List<BlockPos> POWER = new ArrayList<>();
    private static boolean started;
    private static int ticks = -1;

    private FloodlightDemo() {
    }

    private static boolean enabled() {
        return Boolean.getBoolean("cmsFloodlights.demo");
    }

    @SubscribeEvent
    public static void screen(ScreenEvent.Opening event) {
        // A first launch opens accessibility onboarding instead of the title screen.
        if (!enabled() || started || !(event.getNewScreen() instanceof TitleScreen
                || event.getNewScreen() instanceof AccessibilityOnboardingScreen)) {
            return;
        }
        started = true;
        Minecraft mc = Minecraft.getInstance();
        mc.options.renderDistance().set(8);
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
        } else if (ticks == 60) {
            server.execute(() -> view(server, new Vec3(-27.5, FLOOR + 12, -27.5), new Vec3(0, FLOOR, 0)));
        } else if (ticks == 400) {
            server.execute(() -> checkUnlinked(server.overworld()));
        } else if (ticks == 600) {
            shot(mc, "1_floodlights_on.png");
            server.execute(() -> power(server.overworld(), false));
        } else if (ticks == 700) {
            shot(mc, "2_floodlights_off.png");
            server.execute(() -> {
                checkOff(server.overworld());
                power(server.overworld(), true);
                view(server, new Vec3(0.5, FLOOR + 2.6, -26.5), new Vec3(0.5, FLOOR + 1, 10));
            });
        } else if (ticks == 1100) {
            shot(mc, "3_floor_view.png");
            server.execute(() -> console(server.overworld()));
        } else if (ticks == 1180) {
            server.execute(() -> link(server.overworld()));
        } else if (ticks == 1300) {
            server.execute(() -> checkLinked(server));
        } else if (ticks == 1500) {
            shot(mc, "4_console_linked.png");
        } else if (ticks == 1540) {
            LogUtils.getLogger().info("Floodlight demo done: {} checks failed", failures);
            mc.stop();
        }
    }

    private static int failures;
    private static final BlockPos CENTER_LIGHT = new BlockPos(0, CEILING - 1, 0);
    private static final BlockPos CORNER_LIGHT = new BlockPos(16, CEILING - 1, 16);
    /** The console's first column, front row: it faces north, its columns run west, its back row south. */
    private static final BlockPos CONSOLE = new BlockPos(6, FLOOR + 1, 24);

    private static void check(boolean passed, String what, Object... args) {
        if (!passed) {
            failures++;
        }
        Object[] all = new Object[args.length + 1];
        all[0] = passed ? "PASS" : "FAIL";
        System.arraycopy(args, 0, all, 1, args.length);
        LogUtils.getLogger().info("Floodlight demo CHECK {}: " + what, all);
    }

    private static int blockLight(ServerLevel level, int x, int z) {
        return level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, new BlockPos(x, FLOOR + 1, z));
    }

    private static int lightBlocksInHall(ServerLevel level) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(-HALF, FLOOR + 1, -HALF, HALF, CEILING - 1, HALF)) {
            if (level.getBlockState(pos).is(Blocks.LIGHT)) {
                count++;
            }
        }
        return count;
    }

    private static FloodlightBlock.Entity fixture(ServerLevel level, BlockPos pos) {
        return (FloodlightBlock.Entity) level.getBlockEntity(pos);
    }

    private static void wrench(ServerLevel level, BlockPos pos) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayers().getFirst();
        var state = level.getBlockState(pos);
        ((FloodlightBlock) state.getBlock()).onWrenched(state, new net.minecraft.world.item.context.UseOnContext(player,
                net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos), Direction.DOWN, pos, false)));
    }

    private static void checkUnlinked(ServerLevel level) {
        int[][] spots = {{0, 0}, {8, 8}, {16, 16}, {-20, -20}, {-27, 27}};
        for (int[] spot : spots) {
            int light = blockLight(level, spot[0], spot[1]);
            check(light >= 10, "floor block light at ({}, {}) is {}, vanilla's lamps reach none of it", spot[0], spot[1],
                    light);
        }
        int placed = lightBlocksInHall(level);
        check(placed > 20, "{} light blocks placed in the hall", placed);
        FloodlightBlock.Entity corner = fixture(level, CORNER_LIGHT);
        check(corner.beam().strength() == 1 && corner.beam().console() == null,
                "unlinked, on a redstone block: strength {}", corner.beam().strength());
        float before = corner.beam().spread();
        float[] seen = new float[4];
        for (int i = 0; i < 4; i++) {
            wrench(level, CORNER_LIGHT);
            seen[i] = corner.beam().spread();
        }
        check(seen[0] != before && seen[3] == before, "the wrench steps the beam width: {} -> {}",
                before, java.util.Arrays.toString(seen));
    }

    private static void checkOff(ServerLevel level) {
        // Light blocks follow the next look (RealLight.CHECK ticks), well within the 100 ticks since.
        int placed = lightBlocksInHall(level);
        check(placed == 0, "with the redstone gone, {} light blocks are left", placed);
        int light = blockLight(level, 0, 0);
        check(light == 0, "with the redstone gone, the floor's block light is {}", light);
    }

    private static void console(ServerLevel level) {
        var consoleBlock = org.haruyq.createmediasystems.CreateMediaSystems.LIGHTING_CONSOLE.get();
        var monitorBlock = org.haruyq.createmediasystems.CreateMediaSystems.LIGHTING_MONITOR.get();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                level.setBlock(org.haruyq.createmediasystems.lighting.LightingConsoleBlock.at(CONSOLE, Direction.NORTH, x, 0, z),
                        consoleBlock.defaultBlockState().setValue(
                                org.haruyq.createmediasystems.lighting.LightingConsoleBlock.FACING, Direction.NORTH),
                        Block.UPDATE_ALL);
            }
            level.setBlock(org.haruyq.createmediasystems.lighting.LightingConsoleBlock.at(CONSOLE, Direction.NORTH, x, 1, 1),
                    monitorBlock.defaultBlockState().setValue(
                            org.haruyq.createmediasystems.lighting.LightingMonitorBlock.FACING, Direction.NORTH),
                    Block.UPDATE_ALL);
        }
    }

    private static void link(ServerLevel level) {
        var console = org.haruyq.createmediasystems.lighting.LightingConsoleBlock.find(level, CONSOLE);
        check(console != null && console.formed, "the lighting console formed");
        if (console == null) {
            return;
        }
        console = console.controller();
        FloodlightBlock.Entity center = fixture(level, CENTER_LIGHT);
        check(console.bind(center, 0), "the centre floodlight linked to the console's group 1");
        var group = console.settings.groups[0];
        console.settings.master = 1;
        group.brightness = 0.4f;
        group.spread = 18;
        group.range = 120;
        group.colorCount = 1;
        group.colors[0] = 0xFF3040;
        group.colorCycle = false;
        group.audio = false;
        console.changed();
    }

    private static void checkLinked(net.minecraft.server.MinecraftServer server) {
        ServerLevel level = server.overworld();
        FloodlightBlock.Entity center = fixture(level, CENTER_LIGHT);
        var beam = center.beam();
        check(beam.console() != null, "the centre floodlight is run by the console");
        check(Math.abs(beam.strength() - 0.4f) < 1e-4, "brightness from the console: {}", beam.strength());
        check(beam.spread() == 18, "beam width from the console: {}", beam.spread());
        check(beam.range() == 120, "reach from the console: {}", beam.range());
        int expected = io.github.cmsfloodlights.RealLight.lightLevel(0.4f);
        long[] lights = center.lightBlocks();
        // Neighbouring floodlights may already hold the cells under it (each cell has one owner): the count may be 0.
        boolean levels = true;
        for (long packed : lights) {
            var state = level.getBlockState(BlockPos.of(packed));
            levels &= state.is(Blocks.LIGHT) && state.getValue(net.minecraft.world.level.block.LightBlock.LEVEL) == expected;
        }
        check(levels, "its {} light blocks are all at level {}, as the console's brightness", lights.length, expected);
        float spread = center.beam().spread();
        wrench(level, CENTER_LIGHT);
        check(center.beam().spread() == spread, "the wrench leaves a linked floodlight's beam width to the console");
        // Left off for the last shot: the unlinked floodlights go dark, the linked one shines on in the console's red.
        power(level, false);
        check(center.beam().strength() > 0, "a linked floodlight ignores redstone");
        view(server, new Vec3(-14.5, FLOOR + 14, -14.5), new Vec3(0.5, FLOOR, 0.5));
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                message -> LogUtils.getLogger().info("Floodlight demo: {}", message.getString()));
    }

    private static void view(net.minecraft.server.MinecraftServer server, Vec3 eye, Vec3 at) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        float[] aim = Aim.toward(at.subtract(eye), 0);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(server.overworld(), eye.x, eye.y - player.getEyeHeight(), eye.z, aim[0], aim[1]);
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
    }

    private static void build(ServerLevel level) {
        level.setDayTime(18000);
        level.setWeatherParameters(6000, 0, false, false);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState roof = Blocks.GRAY_CONCRETE.defaultBlockState();
        for (int x = -HALF - 1; x <= HALF + 1; x++) {
            for (int z = -HALF - 1; z <= HALF + 1; z++) {
                boolean edge = Math.abs(x) == HALF + 1 || Math.abs(z) == HALF + 1;
                // A gym floor: planks, with a court's white lines.
                boolean line = Math.abs(x) == 20 && Math.abs(z) <= 12 || Math.abs(z) == 12 && Math.abs(x) <= 20
                        || x == 0 && Math.abs(z) <= 12 || x * x + z * z >= 16 && x * x + z * z <= 25;
                set(level, x, FLOOR, z, edge ? wall : line ? Blocks.WHITE_CONCRETE.defaultBlockState()
                        : Blocks.OAK_PLANKS.defaultBlockState());
                for (int y = FLOOR + 1; y < CEILING; y++) {
                    set(level, x, y, z, edge ? wall : air);
                }
                set(level, x, CEILING, z, roof);
                set(level, x, CEILING + 1, z, roof);
            }
        }
        // Benches along one side, to have something standing on the floor.
        for (int x = -14; x <= 14; x++) {
            set(level, x, FLOOR + 1, 18, Blocks.SPRUCE_SLAB.defaultBlockState());
        }
        POWER.clear();
        BlockState floodlight = CmsFloodlights.FLOODLIGHT.get().defaultBlockState()
                .setValue(FloodlightBlock.FACING, Direction.DOWN);
        for (int x = -16; x <= 16; x += 16) {
            for (int z = -16; z <= 16; z += 16) {
                BlockPos pos = new BlockPos(x, CEILING - 1, z);
                level.setBlock(pos, floodlight, Block.UPDATE_ALL);
                aim(level, pos, 3, Vec3.atCenterOf(new BlockPos(x, FLOOR, z)));
                POWER.add(pos.above());
            }
        }
        BlockState stadium = CmsFloodlights.STADIUM_LIGHT.get().defaultBlockState()
                .setValue(FloodlightBlock.FACING, Direction.EAST);
        for (int z = -12; z <= 12; z += 24) {
            BlockPos pos = new BlockPos(-HALF, CEILING - 6, z);
            level.setBlock(pos, stadium, Block.UPDATE_ALL);
            aim(level, pos, 1, Vec3.atCenterOf(new BlockPos(6, FLOOR, z / 2)));
            POWER.add(pos.west());
        }
        power(level, true);
    }

    private static void aim(ServerLevel level, BlockPos pos, int spread, Vec3 target) {
        if (level.getBlockEntity(pos) instanceof FloodlightBlock.Entity light) {
            float[] aim = Aim.toward(target.subtract(Vec3.atCenterOf(pos)), 0);
            light.spread = spread;
            light.aim(aim[0], aim[1]);
        }
    }

    private static void power(ServerLevel level, boolean on) {
        BlockState state = on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState();
        for (BlockPos pos : POWER) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }
}
