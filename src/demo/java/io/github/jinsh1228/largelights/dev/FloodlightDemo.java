package io.github.jinsh1228.largelights.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.LightField;

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
@EventBusSubscriber(modid = CreateLargeLights.MOD_ID, value = Dist.CLIENT)
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
        return Boolean.getBoolean("createLargeLights.demo");
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
        } else if (ticks == 1520) {
            server.execute(() -> pageSettings(server.overworld()));
        } else if (ticks == 1580) {
            server.execute(() -> checkPageSettings(server.overworld()));
        } else if (ticks == 1620) {
            server.execute(() -> checkRealLightOff(server.overworld()));
        } else if (ticks == 1630) {
            server.execute(() -> checkFadeMidway(server.overworld()));
        } else if (ticks == 1680) {
            server.execute(() -> checkFadeDone(server.overworld()));
        } else if (ticks == 1700) {
            checkPageShows(mc);
            server.execute(() -> view(server, new Vec3(4.5, FLOOR + 2.55, 22.45), new Vec3(4.5, FLOOR + 1.55, 25)));
        } else if (ticks == 1800) {
            shot(mc, "5_console_page.png");
        } else if (ticks == 1850) {
            server.execute(() -> {
                buildStreet(server.overworld());
                view(server, new Vec3(37.5, FLOOR + 7, 7.5), new Vec3(56, FLOOR, -2));
            });
        } else if (ticks == 1950) {
            server.execute(() -> {
                checkStreetNight(server.overworld());
                server.getPlayerList().getPlayers().getFirst().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.item.ItemStack(CreateLargeLights.LIGHT_METER.get()));
            });
        } else if (ticks == 1985) {
            checkMeter();
        } else if (ticks == 1990) {
            shot(mc, "6_street_lights_meter.png");
        } else if (ticks == 2000) {
            server.execute(() -> {
                server.getPlayerList().getPlayers().getFirst().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        net.minecraft.world.item.ItemStack.EMPTY);
                server.overworld().setDayTime(6000);
            });
        } else if (ticks == 2040) {
            server.execute(() -> checkStreetDay(server.overworld()));
        } else if (ticks == 2070) {
            server.execute(() -> checkStreetWrench(server.overworld()));
        } else if (ticks == 2100) {
            server.execute(() -> {
                checkStreetBack(server.overworld());
                timersSetup(server.overworld());
            });
        } else if (ticks == 2130) {
            server.execute(() -> server.overworld().setDayTime(13100));
        } else if (ticks == 2190) {
            server.execute(() -> checkTimer(server.overworld(), 3, 0, 13600));
        } else if (ticks == 2250) {
            server.execute(() -> checkTimer(server.overworld(), 0, 1, 12000));
        } else if (ticks == 2280) {
            server.execute(() -> check(io.github.jinsh1228.largelights.ConsoleSettings.scene(consoleAt(server.overworld())) == 0,
                    "setting the time back recalls nothing"));
        } else if (ticks == 2300) {
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
            check(light > 0 && light == LightField.sample(level, new BlockPos(spot[0], FLOOR + 1, spot[1])),
                    "floor block light at ({}, {}) is {} from the illumination field", spot[0], spot[1],
                    light);
        }
        int placed = lightBlocksInHall(level);
        check(placed == 0, "{} invisible light blocks placed in the hall", placed);
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
        // The unlinked floodlights go dark: the linked one shines on in the console's red, its light blocks its own.
        power(level, false);
        FloodlightBlock.Entity center = fixture(level, CENTER_LIGHT);
        check(console.bind(center, 0), "the centre floodlight linked to the console's group 1");
        var group = console.settings.groups[0];
        console.settings.master = 1;
        group.brightness = 0.4f;
        // A linked floodlight's beam width is the floodlight page's, not the group's stage-light spread.
        io.github.jinsh1228.largelights.ConsolePacket.apply(console, new io.github.jinsh1228.largelights.ConsolePacket(
                console.getBlockPos(), 0, io.github.jinsh1228.largelights.ConsolePacket.Action.SPREAD, 0, 18));
        group.range = 120;
        group.colorCount = 1;
        group.colors[0] = 0xFF3040;
        group.colorCycle = false;
        group.audio = false;
        // Ceiling controls tilt the head 30° toward its own right, here west.
        group.pan = 30;
        group.tilt = Aim.NEUTRAL_TILT;
        console.changed();
    }

    private static org.haruyq.createmediasystems.lighting.LightingConsoleBlock.Entity consoleAt(ServerLevel level) {
        return org.haruyq.createmediasystems.lighting.LightingConsoleBlock.find(level, CONSOLE);
    }

    private static void page(ServerLevel level, io.github.jinsh1228.largelights.ConsolePacket.Action action, int index,
                             float value) {
        var console = consoleAt(level);
        io.github.jinsh1228.largelights.ConsolePacket.apply(console,
                new io.github.jinsh1228.largelights.ConsolePacket(console.getBlockPos(), 0, action, index, value));
        console.changed();
    }

    /** As the floodlight page's knobs set them: width past a floodlight's 70°, the light level held to 8. */
    private static void pageSettings(ServerLevel level) {
        var console = consoleAt(level);
        console.settings.groups[0].brightness = 1;
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.SPREAD, 0, 80);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.MAX_LEVEL, 0, 8);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.FADE, 0, 1.5f);
    }

    private static void checkPageSettings(ServerLevel level) {
        FloodlightBlock.Entity center = fixture(level, CENTER_LIGHT);
        var beam = center.beam();
        check(beam.spread() == 70, "the page's 80° beam width stops at a floodlight's 70°: {}", beam.spread());
        check(beam.lightLimit() == 8, "the page holds its real light to level {}", beam.lightLimit());
        int light = blockLight(level, -15, 0);
        check(light > 0 && light <= 8, "the field respects the page's maximum level 8: {}", light);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.REAL_LIGHT, 0, 0);
    }

    private static void checkRealLightOff(ServerLevel level) {
        FloodlightBlock.Entity center = fixture(level, CENTER_LIGHT);
        check(LightField.sample(level, new BlockPos(-15, FLOOR + 1, 0)) == 0 && center.beam().strength() > 0,
                "real light off on the page: visual beam remains lit and the real field is zero");
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.REAL_LIGHT, 0, 1);
        // Recall "off" (every group at 0%) over the 1.5 s fade.
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.RECALL, 3, 0);
    }

    private static void checkFadeMidway(ServerLevel level) {
        float brightness = consoleAt(level).settings.groups[0].brightness;
        check(brightness > 0.05f && brightness < 0.95f, "a recalled scene fades: half a second in, {}", brightness);
    }

    private static void checkFadeDone(ServerLevel level) {
        var console = consoleAt(level);
        check(console.settings.groups[0].brightness == 0 && console.settings.master == 1,
                "the fade ends on the scene: brightness {}, master {}", console.settings.groups[0].brightness,
                console.settings.master);
        check(io.github.jinsh1228.largelights.ConsoleSettings.scene(console) == 3, "the console shows the scene recalled");
        // Saved from the faders, then the match scene brought back for the photo.
        console.settings.groups[0].brightness = 0.5f;
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.SAVE, 1, 0);
        check(io.github.jinsh1228.largelights.ConsoleSettings.scene(console, 1)[0] == 0.5f,
                "saving a scene keeps the faders: {}", io.github.jinsh1228.largelights.ConsoleSettings.scene(console, 1)[0]);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.RECALL, 0, 0);
    }

    private static void checkPageShows(Minecraft mc) {
        var console = org.haruyq.createmediasystems.lighting.LightingConsoleBlock.find(mc.level, CONSOLE);
        var page = console == null ? null : org.haruyq.createmediasystems.lighting.LightingConsolePage.showing(console);
        check(page instanceof io.github.jinsh1228.largelights.client.FloodlightPage,
                "the console shows the floodlight page for a group with a floodlight: {}", page);
        console.settings.selectedGroup = 1;
        var other = org.haruyq.createmediasystems.lighting.LightingConsolePage.showing(console);
        console.settings.selectedGroup = 0;
        check(other == null, "and its own page for a group without one");
    }

    /** One family to a group: a stage light and a floodlight never share one, so each group shows one page. */
    private static void checkFamilies(ServerLevel level) {
        var console = consoleAt(level);
        BlockPos stagePos = new BlockPos(-6, FLOOR + 1, 20);
        level.setBlock(stagePos, org.haruyq.createmediasystems.CreateMediaSystems.STAGE_LIGHT.get().defaultBlockState(),
                Block.UPDATE_ALL);
        var stage = (org.haruyq.createmediasystems.lighting.StageLightBlock.Entity) level.getBlockEntity(stagePos);
        FloodlightBlock.Entity corner = fixture(level, CORNER_LIGHT);
        check("create_large_lights".equals(console.family(0)), "group 1 is the floodlights': {}", console.family(0));
        check(!console.accepts(stage, 0) && !console.bind(stage, 0) && stage.console == null,
                "a stage light can't join the floodlights' group");
        check(console.bind(stage, 1) && "create_media_systems".equals(console.family(1)),
                "it joins an empty group, which becomes the stage lights': {}", console.family(1));
        check(!console.bind(corner, 1) && corner.console == null, "a floodlight can't join the stage lights' group");
        check(console.bind(corner, 2), "and joins another empty group");
        // Moving a linked fixture (as scrolling it does) is held to the same rule.
        check(!console.bind(corner, 1) && corner.group == 2, "moving it into the stage lights' group is refused");
        corner.unlink();
        stage.unlink();
        level.removeBlock(stagePos, false);
        check(console.family(1) == null && console.family(2) == null, "emptied, groups take any family again");
    }

    private static final int[] STREET_X = {44, 56, 68};
    private static final int STREET_Z = -4;
    private static final int STREET_TOP = FLOOR + 6;

    /** A road out in the open, by night, with three street lights on poles over it. */
    private static void buildStreet(ServerLevel level) {
        for (int x = 36; x <= 76; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR + 1; y <= FLOOR + 12; y++) {
                    set(level, x, y, z, Blocks.AIR.defaultBlockState());
                }
                set(level, x, FLOOR, z, Math.abs(z) <= 3 ? Blocks.GRAY_CONCRETE.defaultBlockState()
                        : Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int x : STREET_X) {
            for (int y = FLOOR + 1; y < STREET_TOP; y++) {
                set(level, x, y, STREET_Z, CreateLargeLights.LIGHT_POLE.get().defaultBlockState());
            }
            level.setBlock(new BlockPos(x, STREET_TOP, STREET_Z), CreateLargeLights.STREET_LIGHT.get().defaultBlockState()
                    .setValue(io.github.jinsh1228.largelights.StreetLightBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        }
    }

    private static io.github.jinsh1228.largelights.StreetLightBlock.Entity street(ServerLevel level, int i) {
        return (io.github.jinsh1228.largelights.StreetLightBlock.Entity) level.getBlockEntity(
                new BlockPos(STREET_X[i], STREET_TOP, STREET_Z));
    }

    private static boolean streetLit(ServerLevel level, int i) {
        return level.getBlockState(new BlockPos(STREET_X[i], STREET_TOP, STREET_Z))
                .getValue(io.github.jinsh1228.largelights.StreetLightBlock.LIT);
    }

    private static void checkStreetNight(ServerLevel level) {
        for (int i = 0; i < STREET_X.length; i++) {
            check(streetLit(level, i) && LightField.sample(level, new BlockPos(STREET_X[i], FLOOR + 1, 0)) > 0,
                    "street light {} lights the road through its field", i + 1);
        }
        // The road under the middle light, 6 blocks down: past a lamp's own reach, lit by its light blocks.
        int light = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, new BlockPos(STREET_X[1], FLOOR + 1, 0));
        check(light >= 10, "the road under a street light is at light {}", light);
    }

    private static void checkMeter() {
        int[] reading = io.github.jinsh1228.largelights.client.LightMeter.reading();
        check(reading[1] > 100 && reading[0] > 0, "the light meter looks over {} floors, {} of them dark", reading[1],
                reading[0]);
    }

    private static void checkStreetDay(ServerLevel level) {
        for (int i = 0; i < STREET_X.length; i++) {
            check(!streetLit(level, i) && LightField.sample(level, new BlockPos(STREET_X[i], FLOOR + 1, 0)) == 0,
                    "street light {} out by day, field removed", i + 1);
        }
        level.setDayTime(18000);
    }

    private static void wrenchStreet(ServerLevel level) {
        BlockPos pos = new BlockPos(STREET_X[0], STREET_TOP, STREET_Z);
        ServerPlayer player = level.getServer().getPlayerList().getPlayers().getFirst();
        var state = level.getBlockState(pos);
        ((io.github.jinsh1228.largelights.StreetLightBlock) state.getBlock()).onWrenched(state,
                new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
    }

    private static void checkStreetWrench(ServerLevel level) {
        check(streetLit(level, 0), "night again: street light 1 lit again");
        wrenchStreet(level);
        check(!streetLit(level, 0), "the wrench turns its sensor off: out, though it is night");
    }

    private static void checkStreetBack(ServerLevel level) {
        check(!streetLit(level, 0) && street(level, 0).lightCount() == 0, "with its sensor off it stays out");
        wrenchStreet(level);
        check(streetLit(level, 0), "and the wrench turns it back on");
    }

    /** Timers: at 19:00 the off scene, at 19:30 the match scene; the clock is set just before them. */
    private static void timersSetup(ServerLevel level) {
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.TIMERS_ON, 0, 1);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.TIMER_TIME, 0, 13000);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.TIMER_SCENE, 0, 3);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.TIMER_ON, 0, 1);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.TIMER_TIME, 1, 13500);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.TIMER_SCENE, 1, 0);
        page(level, io.github.jinsh1228.largelights.ConsolePacket.Action.TIMER_ON, 1, 1);
        level.setDayTime(12900);
    }

    private static void checkTimer(ServerLevel level, int scene, float brightness, long next) {
        var console = consoleAt(level);
        check(io.github.jinsh1228.largelights.ConsoleSettings.scene(console) == scene
                        && Math.abs(console.settings.groups[0].brightness - brightness) < 1e-4,
                "passing {} the timer recalls scene {}: brightness {}",
                io.github.jinsh1228.largelights.client.FloodlightPage.clock(level.getDayTime()), scene,
                console.settings.groups[0].brightness);
        level.setDayTime(next);
    }

    private static void checkLinked(net.minecraft.server.MinecraftServer server) {
        ServerLevel level = server.overworld();
        FloodlightBlock.Entity center = fixture(level, CENTER_LIGHT);
        var beam = center.beam();
        check(beam.console() != null, "the centre floodlight is run by the console");
        check(Math.abs(beam.strength() - 0.4f) < 1e-4, "brightness from the console: {}", beam.strength());
        check(beam.spread() == 18, "beam width from the console's floodlight page: {}", beam.spread());
        check(beam.range() == 120, "reach from the console: {}", beam.range());
        int light = blockLight(level, -15, 0);
        check(light > 0 && light <= 6, "the linked field follows brightness and distance: {}", light);
        check(Math.abs(beam.pitch() - 60) < 1e-3 && Math.abs(Math.abs(beam.yaw()) - 90) < 1e-3,
                "aimed by the console's pan and tilt: yaw {}, pitch {}", beam.yaw(), beam.pitch());
        check(LightField.sample(level, new BlockPos(-15, FLOOR + 1, 0)) > 0
                        && LightField.sample(level, new BlockPos(15, FLOOR + 1, 0)) == 0,
                "the linked field illuminates the west target and stays zero outside the cone");
        var group = beam.group();
        group.pan = -30;
        check(Math.abs(center.beam().yaw() + 90) < 1e-3 && Math.abs(center.beam().pitch() - 60) < 1e-3,
                "ceiling pan reaches the opposite side of straight down");
        group.pan = 0;
        group.tilt = Aim.NEUTRAL_TILT + 30;
        check(Math.abs(center.beam().pitch() - 60) < 1e-3 && Math.abs(Math.abs(center.beam().yaw()) - 180) < 1e-3,
                "ceiling tilt crosses straight down without clamping");
        var mounted = center.getBlockState();
        float baseYaw = center.yaw, basePitch = center.pitch;
        level.setBlock(CENTER_LIGHT, mounted.setValue(FloodlightBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
        center.aim(180, 35);
        group.pan = 20;
        group.tilt = 55;
        check(Math.abs(center.beam().yaw() + 160) < 1e-3 && Math.abs(center.beam().pitch() - 55) < 1e-3,
                "wall mounting retains its previous pan and tilt controls");
        level.setBlock(CENTER_LIGHT, mounted, Block.UPDATE_ALL);
        center.aim(baseYaw, basePitch);
        group.pan = 30;
        group.tilt = Aim.NEUTRAL_TILT;
        beam.console().changed();
        float spread = center.beam().spread();
        wrench(level, CENTER_LIGHT);
        check(center.beam().spread() == spread, "the wrench leaves a linked floodlight's beam width to the console");
        check(Math.abs(center.beam().yaw() - beam.yaw()) < 1e-3 && Math.abs(center.beam().pitch() - beam.pitch()) < 1e-3,
                "beam width controls do not change the fixture aim");
        check(center.beam().strength() > 0, "a linked floodlight ignores redstone");
        checkFamilies(level);
        view(server, new Vec3(-14.5, FLOOR + 14, -14.5), new Vec3(0.5, FLOOR, 0.5));
    }

    private static void shot(Minecraft mc, String name) {
        if (Boolean.getBoolean("createLargeLights.demo.noScreenshots")) return;
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
        BlockState floodlight = CreateLargeLights.FLOODLIGHT.get().defaultBlockState()
                .setValue(FloodlightBlock.FACING, Direction.DOWN);
        for (int x = -16; x <= 16; x += 16) {
            for (int z = -16; z <= 16; z += 16) {
                BlockPos pos = new BlockPos(x, CEILING - 1, z);
                level.setBlock(pos, floodlight, Block.UPDATE_ALL);
                aim(level, pos, 3, Vec3.atCenterOf(new BlockPos(x, FLOOR, z)));
                POWER.add(pos.above());
            }
        }
        BlockState stadium = CreateLargeLights.STADIUM_LIGHT.get().defaultBlockState()
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
