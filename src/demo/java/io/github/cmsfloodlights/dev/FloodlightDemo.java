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
        } else if (ticks == 600) {
            shot(mc, "1_floodlights_on.png");
            server.execute(() -> power(server.overworld(), false));
        } else if (ticks == 700) {
            shot(mc, "2_floodlights_off.png");
            server.execute(() -> {
                power(server.overworld(), true);
                view(server, new Vec3(0.5, FLOOR + 2.6, -26.5), new Vec3(0.5, FLOOR + 1, 10));
            });
        } else if (ticks == 1100) {
            shot(mc, "3_floor_view.png");
        } else if (ticks == 1140) {
            LogUtils.getLogger().info("Floodlight demo done");
            mc.stop();
        }
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
