package io.github.jinsh1228.largelights.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Arrays;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.ConsoleSettings;
import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.FloodlightConfig;
import io.github.jinsh1228.largelights.LightField;
import io.github.jinsh1228.largelights.LightFieldNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingMonitorBlock;
import org.haruyq.createmediasystems.lighting.LightingRaycast;
import org.lwjgl.system.MemoryUtil;

/**
 * Dev only (-PshapeDemo): repeatable partial-block projection scene. Screenshots are taken externally through
 * Computer Use. Each camera remains still for several hundred ticks, then the game saves and exits.
 */
@EventBusSubscriber(modid = CreateLargeLights.MOD_ID, value = Dist.CLIENT)
public final class ShapeLightingDemo {
    private static final String WORLD = "ShapeLightingDemo";
    private static final int FLOOR = 150;
    private static final boolean GPU_TRIAL = Boolean.getBoolean("createLargeLights.gpuDemo");
    private static final boolean PERFORMANCE = Boolean.getBoolean("createLargeLights.gpuDemo.performance");
    private static final boolean FIELD_DEMO = Boolean.getBoolean("createLargeLights.fieldDemo");
    private static final boolean VERTICAL = "vertical".equals(System.getProperty("createLargeLights.gpuDemo.angle", "oblique"));
    private static final BlockPos LIGHT = new BlockPos(0, FLOOR + (PERFORMANCE ? 60 : GPU_TRIAL ? 24 : 8), GPU_TRIAL && VERTICAL ? 5 : -10);
    private static final BlockPos CONSOLE = new BlockPos(16, FLOOR + 1, -9);
    private static final BlockPos FIELD_TEST = new BlockPos(0, FLOOR + 40, 0);
    private static final BlockPos VANILLA_TEST = FIELD_TEST.offset(0, 0, -24);
    private static final BlockPos[] SYNC_POINTS = {
            new BlockPos(0, FLOOR + 3, 2), new BlockPos(0, FLOOR + 1, 6), new BlockPos(0, FLOOR + 3, -12)
    };
    private static boolean started;
    private static volatile boolean linkRequested;
    private static boolean linked;
    private static int linkAttempts;
    private static int ticks = -1;
    private static volatile int failures;
    private static BlockState vanillaTestState;
    private static volatile FieldSample fieldSample;
    private static int syncPhase;
    private static int syncChangedAt;

    private record FieldSample(boolean enabled, int[] field, int[] block) {
    }

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
        if (GpuFarDemo.enabled()) {
            GpuFarDemo.tick(mc);
            return;
        }
        if (!GPU_TRIAL) GpuProductionDemo.tick(mc, ticks, syncPhase == 4, failures);
        if (!enabled() || mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
            return;
        }
        ticks++;
        var server = mc.getSingleplayerServer();
        if (!GPU_TRIAL) {
            failures += ModelDataDemo.tick(mc, ticks);
            checkFieldSync(mc);
            if (FIELD_DEMO && !GpuProductionDemo.enabled() && ticks == 1100) {
                check(syncPhase == 4, "client/server illumination sync, live fade and switch-off checks completed");
                LogUtils.getLogger().info("SHAPE field demo done: {} checks failed", failures);
                mc.stop();
                return;
            }
        }
        if (ticks == 20) {
            server.execute(() -> build(server.overworld()));
        } else if (ticks == 80) {
            server.execute(() -> {
                view(server, new Vec3(17.5, FLOOR + 7, -9.5), new Vec3(0, FLOOR + 1.5, 5));
                LogUtils.getLogger().info("SHAPE VIEW overview: ticks 80-799; lanes left-to-right bars, fence, bottom slab, top slab, stairs, cube, double slab");
            });
        } else if (ticks == 120) {
            linkRequested = true;
        } else if (ticks == 240) {
            server.execute(() -> checkScene(server.overworld()));
        } else if (ticks == 360) {
            if (!GPU_TRIAL) {
                checkRenderingRays(mc);
                server.execute(() -> checkLightField(server.overworld()));
            }
        } else if (!GpuProductionDemo.enabled() && ticks == (GPU_TRIAL ? 550 : 800)) {
            server.execute(() -> {
                view(server, GPU_TRIAL ? new Vec3(-3.5, FLOOR + 7, -7.5)
                        : new Vec3(-15.5, FLOOR + 4.5, -2.5),
                        GPU_TRIAL ? new Vec3(-12.5, FLOOR + 2, 5.5) : new Vec3(-9.5, FLOOR + 1.8, 5.5));
                LogUtils.getLogger().info("SHAPE VIEW bars and fence");
            });
        } else if (!GpuProductionDemo.enabled() && ticks == (GPU_TRIAL ? 800 : 1400)) {
            server.execute(() -> {
                view(server, new Vec3(8.5, FLOOR + 4.5, -2.5), new Vec3(0.5, FLOOR + 1, 5.5));
                LogUtils.getLogger().info("SHAPE VIEW slabs and stairs");
            });
        } else if (ticks == (GPU_TRIAL ? 1000 : 2000)) {
            LogUtils.getLogger().info("SHAPE demo done: {} checks failed", failures);
            mc.stop();
        }
    }

    private static void build(ServerLevel level) {
        level.setDayTime(18000);
        level.setWeatherParameters(6000, 0, false, false);
        FloodlightConfig.REAL_LIGHT.set(!GPU_TRIAL || PERFORMANCE);
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        int roof = FLOOR + (PERFORMANCE ? 66 : GPU_TRIAL ? 30 : 14);
        int minX = PERFORMANCE ? -40 : -20, maxX = PERFORMANCE ? 40 : 20;
        int minZ = PERFORMANCE ? -40 : -14, maxZ = PERFORMANCE ? 40 : 18;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlock(new BlockPos(x, FLOOR, z), Blocks.WHITE_CONCRETE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = FLOOR + 1; y < roof; y++) {
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    level.setBlock(new BlockPos(x, y, z), edge ? wall : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
                level.setBlock(new BlockPos(x, roof, z), wall, Block.UPDATE_CLIENTS);
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
            float[] aim = GPU_TRIAL && VERTICAL ? new float[]{0, 90}
                    : Aim.toward(new Vec3(0.5, FLOOR + 1, 6).subtract(Vec3.atCenterOf(LIGHT)), 0);
            light.spread = 3;
            light.aim(aim[0], aim[1]);
        }
        buildConsole(level);
        if (!GPU_TRIAL) {
            vanillaTestState = level.getBlockState(VANILLA_TEST);
            level.setBlock(VANILLA_TEST, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        }
        LogUtils.getLogger().info("SHAPE scene built at Y{}; only fixture light, bars x-12, fence x-8, bottom slabs x-4, top slabs x0, stairs x4, cubes x8, double slabs x12", FLOOR);
    }

    private static BlockState connected(BlockState state) {
        return state.setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true)
                .setValue(BlockStateProperties.NORTH, false).setValue(BlockStateProperties.SOUTH, false);
    }

    private static void buildConsole(ServerLevel level) {
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                level.setBlock(org.haruyq.createmediasystems.lighting.ConsoleLayout.at(CONSOLE, Direction.NORTH, x, 0, z),
                        org.haruyq.createmediasystems.lighting.LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState()
                                .setValue(LightingConsoleBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
            }
            level.setBlock(org.haruyq.createmediasystems.lighting.ConsoleLayout.at(CONSOLE, Direction.NORTH, x, 1, 1),
                    org.haruyq.createmediasystems.lighting.LightingFeature.LIGHTING_MONITOR.get().defaultBlockState()
                            .setValue(LightingMonitorBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
        }
    }

    @SubscribeEvent
    public static void linkWhenFormed(LevelTickEvent.Post event) {
        if (!enabled() || !linkRequested || linked || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return;
        }
        var console = LightingConsoleBlock.find(level, CONSOLE);
        if (console == null || !console.formed || !(level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light)) {
            if (++linkAttempts == 40) {
                check(false, "lighting console formed within 40 server ticks");
                linkRequested = false;
            }
            return;
        }
        linked = true;
        check(true, "lighting console formed");
        console = console.controller();
        check(console.bind(light, 0), "stadium light linked");
        console.settings.master = 1;
        console.settings.enabled = true;
        var group = console.settings.groups[0];
        group.brightness = 1;
        group.range = PERFORMANCE ? 128 : 64;
        group.pan = 0;
        group.tilt = Aim.NEUTRAL_TILT;
        group.motion = org.haruyq.createmediasystems.lighting.LightingSettings.byId(org.haruyq.createmediasystems.lighting.LightingSettings.Motion.values(), PERFORMANCE ? Integer.getInteger("createLargeLights.gpuDemo.motion", 0) : 0);
        group.colorCount = 1;
        group.colors[0] = 0x55CCFF;
        group.colorCycle = false;
        group.audio = false;
        ConsoleSettings.setSpread(console, 0, 80);
        ConsoleSettings.setRealLight(console, 0, !GPU_TRIAL || PERFORMANCE);
        console.changed();
    }

    private static void checkScene(ServerLevel level) {
        check(level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity, "stadium light exists");
        if (level.getBlockEntity(LIGHT) instanceof FloodlightBlock.Entity light) {
            var beam = light.beam();
            check(beam.strength() == 1 && beam.console() != null, "cyan beam enabled");
            check(FloodlightConfig.REAL_LIGHT.get() == (!GPU_TRIAL || PERFORMANCE),
                    !GPU_TRIAL || PERFORMANCE ? "real lighting enabled" : "real lighting disabled to isolate the projection trial");
            int physical = 0;
            for (BlockPos at : BlockPos.betweenClosed(-20, FLOOR + 1, -14, 20, FLOOR + 13, 18)) {
                if (level.getBlockState(at).is(Blocks.LIGHT)) physical++;
            }
            check(physical == 0, "the room contains no physical light blocks");
        }
        check(level.getBlockState(new BlockPos(-12, FLOOR + 2, 5)).is(Blocks.IRON_BARS), "iron bars panel exists");
        check(level.getBlockState(new BlockPos(-8, FLOOR + 1, 5)).is(Blocks.OAK_FENCE), "connected fence panel exists");
        check(level.getBlockState(new BlockPos(-4, FLOOR + 1, 5)).getValue(SlabBlock.TYPE) == SlabType.BOTTOM,
                "bottom slab surface is half-height");
        check(level.getBlockState(new BlockPos(0, FLOOR + 1, 5)).getValue(SlabBlock.TYPE) == SlabType.TOP,
                "top slab has an open lower half");
        check(level.getBlockState(new BlockPos(4, FLOOR + 1, 5)).is(Blocks.QUARTZ_STAIRS), "stairs exist");
    }

    /** Query the installed block-light hook, including world changes that must invalidate cached rays. */
    private static void checkLightField(ServerLevel level) {
        BlockPos second = FIELD_TEST.east();
        BlockPos target = FIELD_TEST.offset(0, 0, 8);
        BlockPos obstruction = FIELD_TEST.offset(0, 0, 4);
        BlockState previous = level.getBlockState(obstruction);
        Vec3 origin = Vec3.atCenterOf(FIELD_TEST).add(0, .25, 0);
        LightField.Beam beam = new LightField.Beam(origin, new Vec3(0, 0, 1), .5, 32, 1, 6, 4);
        int sky = level.getBrightness(LightLayer.SKY, target);
        try {
            check(level.getBrightness(LightLayer.BLOCK, target) == 0, "isolated field test starts dark");
            LightField.update(level, FIELD_TEST, beam);
            check(level.getBrightness(LightLayer.BLOCK, target) == 6, "block-light engine reads fixture level 6");
            check(level.getBrightness(LightLayer.BLOCK, FIELD_TEST.offset(4, 0, 8)) == 0,
                    "fixture light stops exactly outside the cone");
            check(level.getBrightness(LightLayer.BLOCK, FIELD_TEST.offset(0, 0, -2)) == 0,
                    "fixture light does not propagate behind its source");
            check(level.getBrightness(LightLayer.BLOCK, VANILLA_TEST) == 15,
                    "vanilla sea-lantern light remains 15 outside the cone");
            check(level.getBrightness(LightLayer.SKY, target) == sky, "fixture light leaves sky light unchanged");

            LightField.update(level, second, beam);
            check(level.getBrightness(LightLayer.BLOCK, target) == 12, "overlapping level 6 fixtures add to 12");
            LightField.update(level, second, new LightField.Beam(origin, new Vec3(0, 0, 1), .5, 32, 1, 15, 4));
            check(level.getBrightness(LightLayer.BLOCK, target) == 15, "overlapping fixture levels saturate at 15");
            LightField.remove(level, second);
            check(level.getBrightness(LightLayer.BLOCK, target) == 6, "removing one fixture preserves the other");

            level.setBlock(obstruction, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            check(level.getBrightness(LightLayer.BLOCK, target) == 0, "adding an opaque block invalidates cached light");
            level.setBlock(obstruction, Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), Block.UPDATE_ALL);
            check(level.getBrightness(LightLayer.BLOCK, target) == 6, "light passes above a bottom slab");
            level.setBlock(obstruction, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP), Block.UPDATE_ALL);
            check(level.getBrightness(LightLayer.BLOCK, target) == 0, "the same ray stops at a top slab");
            for (Block transparent : new Block[]{Blocks.IRON_BARS, Blocks.OAK_FENCE, Blocks.GLASS, Blocks.WATER}) {
                level.setBlock(obstruction, transparent.defaultBlockState(), Block.UPDATE_ALL);
                check(level.getBrightness(LightLayer.BLOCK, target) == 6,
                        "common block light transmits through " + transparent);
            }
            level.setBlock(obstruction, previous, Block.UPDATE_ALL);
            check(level.getBrightness(LightLayer.BLOCK, target) == 6, "removing the obstruction restores cached light");
            LightField.remove(level, FIELD_TEST);
            check(level.getBrightness(LightLayer.BLOCK, target) == 0, "removing all fixtures removes their illumination");

            int physical = 0;
            for (BlockPos at : BlockPos.betweenClosed(FIELD_TEST.offset(-4, -4, -4), FIELD_TEST.offset(4, 4, 36))) {
                if (level.getBlockState(at).is(Blocks.LIGHT)) physical++;
            }
            check(physical == 0, "light-field updates create no physical light blocks");
        } finally {
            LightField.remove(level, FIELD_TEST);
            LightField.remove(level, second);
            level.setBlock(obstruction, previous, Block.UPDATE_ALL);
            level.setBlock(VANILLA_TEST, vanillaTestState, Block.UPDATE_ALL);
        }
    }

    /** Wait for the actual fixture's subscribed sections, then verify that switching it off clears the client. */
    private static void checkFieldSync(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (ticks == 420) {
            syncPhase = 1;
            syncChangedAt = ticks;
            server.execute(() -> recordFieldSample(server.overworld(), true));
        }
        if (syncPhase == 6 && GpuProductionDemo.readyForFade(mc, ticks)) {
            syncPhase = 2;
            FieldFadeDemo.start(mc, CONSOLE, SYNC_POINTS[0]);
            GpuProductionDemo.fadeStarted();
        }
        if (syncPhase == 2 && FieldFadeDemo.tick(mc)) {
            failures += FieldFadeDemo.failures();
            GpuProductionDemo.fadeFinished(mc, ticks);
            syncPhase = 5;
            syncChangedAt = ticks;
            server.execute(() -> {
                var console = LightingConsoleBlock.find(server.overworld(), CONSOLE);
                check(console != null, "fixture console exists for illumination switch-off");
                if (console != null) {
                    console = console.controller();
                    ConsoleSettings.setRealLight(console, 0, false);
                    console.changed();
                }
            });
        }
        if (syncPhase == 5 && ticks - syncChangedAt >= 30) {
            syncPhase = 3;
            syncChangedAt = ticks;
            server.execute(() -> recordFieldSample(server.overworld(), false));
        }
        FieldSample expected = fieldSample;
        if (expected == null || syncPhase != 1 && syncPhase != 3 || expected.enabled != (syncPhase == 1)) {
            return;
        }
        int[] field = new int[SYNC_POINTS.length], block = new int[SYNC_POINTS.length];
        for (int i = 0; i < SYNC_POINTS.length; i++) {
            field[i] = LightField.sample(mc.level, SYNC_POINTS[i]);
            block[i] = mc.level.getBrightness(LightLayer.BLOCK, SYNC_POINTS[i]);
        }
        boolean matches = Arrays.equals(field, expected.field) && Arrays.equals(block, expected.block);
        int waited = ticks - syncChangedAt;
        if (!matches && waited < (expected.enabled ? 230 : 80)) {
            return;
        }
        check(matches && (!expected.enabled || expected.field[0] > 0),
                "client illumination " + (expected.enabled ? "arrives" : "clears after switch-off")
                        + "; field=" + Arrays.toString(field) + ", expected=" + Arrays.toString(expected.field)
                        + ", block=" + Arrays.toString(block) + ", waited=" + waited + " ticks");
        checkSectionLight(mc, expected.block);
        server.execute(() -> LogUtils.getLogger().info("SHAPE FIELD SYNC metrics {}",
                LightFieldNetwork.metrics(server.overworld())));
        if (expected.enabled) {
            syncPhase = GpuProductionDemo.enabled() ? 6 : 2;
            syncChangedAt = ticks;
            if (syncPhase == 2) FieldFadeDemo.start(mc, CONSOLE, SYNC_POINTS[0]);
        } else {
            syncPhase = 4;
        }
    }

    /** Exercise bulk light consumers too: Flywheel's fast collector bypasses individual light queries. */
    private static void checkSectionLight(Minecraft mc, int[] expected) {
        int[] section = new int[SYNC_POINTS.length], flywheel = new int[SYNC_POINTS.length];
        try {
            Class<?> type = Class.forName("dev.engine_room.flywheel.backend.engine.LightDataCollector");
            Class<?> storage = Class.forName("dev.engine_room.flywheel.backend.engine.LightStorage");
            Object collector = type.getMethod("of", LevelAccessor.class).invoke(null, mc.level);
            check(collector.getClass().getName().endsWith("$Fast"), "Flywheel retains its fast light collector");
            long memory = MemoryUtil.nmemCalloc(1, storage.getField("SECTION_SIZE_BYTES").getInt(null));
            try {
                for (int i = 0; i < SYNC_POINTS.length; i++) {
                    BlockPos pos = SYNC_POINTS[i];
                    SectionPos at = SectionPos.of(pos);
                    DataLayer data = mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(at);
                    section[i] = data == null ? 0 : data.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    type.getMethod("collectSection", long.class, long.class).invoke(collector, memory, at.asLong());
                    int index = ((pos.getY() & 15) + 1) * 18 * 18 + ((pos.getZ() & 15) + 1) * 18 + (pos.getX() & 15) + 1;
                    flywheel[i] = MemoryUtil.memGetByte(memory + storage.getField("SOLID_SIZE_BYTES").getInt(null) + index) & 15;
                }
            } finally {
                MemoryUtil.nmemFree(memory);
            }
            check(Arrays.equals(section, expected), "section light agrees with point queries: "
                    + Arrays.toString(section) + ", expected=" + Arrays.toString(expected));
            check(Arrays.equals(flywheel, expected), "Flywheel light agrees with point queries: "
                    + Arrays.toString(flywheel) + ", expected=" + Arrays.toString(expected));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect Flywheel light data", failure);
        }
    }

    private static void recordFieldSample(ServerLevel level, boolean enabled) {
        int[] field = new int[SYNC_POINTS.length], block = new int[SYNC_POINTS.length];
        for (int i = 0; i < SYNC_POINTS.length; i++) {
            field[i] = LightField.sample(level, SYNC_POINTS[i]);
            block[i] = level.getBrightness(LightLayer.BLOCK, SYNC_POINTS[i]);
        }
        check(enabled ? field[0] > 0 && field[2] == 0 : Arrays.stream(field).allMatch(value -> value == 0),
                "server illumination " + (enabled ? "has a lit air sample and dark rear sample" : "is off")
                        + "; field=" + Arrays.toString(field));
        LogUtils.getLogger().info("SHAPE FIELD SYNC server enabled={}, field={}, block={}, metrics={}",
                enabled, Arrays.toString(field), Arrays.toString(block), LightFieldNetwork.metrics(level));
        fieldSample = new FieldSample(enabled, field, block);
    }

    /** Exercise the installed CMS mixin against the actual baked models and their texture alpha. */
    private static void checkRenderingRays(Minecraft mc) {
        LightingRaycast rays = LightingRaycast.rendered(mc.level, LIGHT);
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

    private static synchronized void check(boolean passed, String message) {
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
