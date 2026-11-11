package io.github.jinsh1228.largelights.dev;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingMonitorBlock;
import org.haruyq.createmediasystems.lighting.LightingIllumination;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.LightFieldNetwork;
import io.github.jinsh1228.largelights.ConsoleSettings;
import io.github.jinsh1228.largelights.client.GpuLightProjection;
import io.github.jinsh1228.largelights.client.GpuLightGeometry;
import it.unimi.dsi.fastutil.floats.FloatArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Isolated static-scene trial. This class and its shaders are absent from normal builds. */
@EventBusSubscriber(modid = CreateLargeLights.MOD_ID, value = Dist.CLIENT)
public final class GpuProjectionDemo {
    private static final int FLOOR = 150;
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};
    private static final int STRIDE = 11;
    private static final float NEAR = .25f;
    private static final boolean ENABLED = Boolean.getBoolean("createLargeLights.gpuDemo");
    private static final boolean PERFORMANCE = Boolean.getBoolean("createLargeLights.gpuDemo.performance");
    private static final String MODE = System.getProperty("createLargeLights.gpuDemo.mode", "gpu");
    private static final boolean GPU = "gpu".equals(MODE);
    private static final int SIZE = Math.max(256, Math.min(4096, Integer.getInteger("createLargeLights.gpuDemo.shadowSize", 1024)));
    private static final int LIGHT_COUNT = Math.max(1, Math.min(PERFORMANCE ? 128 : 16, Integer.getInteger("createLargeLights.gpuDemo.lightCount", 1)));
    private static final int RANGE = Integer.getInteger("createLargeLights.gpuDemo.range", PERFORMANCE ? 128 : 64);
    private static final BlockPos LAMP = new BlockPos(0, FLOOR + Integer.getInteger("createLargeLights.gpuDemo.height", PERFORMANCE ? 60 : 24), vertical() ? 5 : -10);
    private static final List<BlockPos> LAMPS = lampPositions();
    private static final BlockPos SECOND_CONSOLE = new BlockPos(32, FLOOR + 1, 24);
    private static final List<Projector> projectors = new ArrayList<>();
    private static Level knownLevel;
    private static int ticks;
    private static int vao, vbo, vertexCount, depthProgram, colorProgram;
    private static final int[] queries = new int[4];
    private static final boolean[] pending = new boolean[4];
    private static int nextQuery, shadowQuery;
    private static boolean shadowPending;
    private static final double[] frames = new double[20000];
    private static final double[] gpuTimes = new double[20000];
    private static int frameCount, gpuCount, phase = -1;
    private static long previousFrame;
    private static long productionStart, productionNanos, phaseShadows, phaseSurfaces;
    private static int productionSamples;
    private static boolean productionQuery;
    private static boolean ready;
    private static volatile boolean linked;
    private static boolean configurationChecked;
    private static boolean nativeLocated;
    private static Field nativeBeams, nativeGpu;

    private record Projector(Matrix4f projection, Vector3f origin, Vector3f direction, Vector3f right,
                             Vector3f up, float tangent, float range, float strength, int color,
                             int depthTexture, int framebuffer) {
    }

    private GpuProjectionDemo() {
    }

    public static boolean production() {
        return "production".equals(MODE);
    }

    public static boolean gpu() {
        return ENABLED && GPU;
    }

    public static BlockPos lamp() {
        return LAMP;
    }

    public static boolean isLamp(BlockPos pos) {
        return LAMPS.contains(pos);
    }

    private static List<BlockPos> lampPositions() {
        List<BlockPos> positions = new ArrayList<>(LIGHT_COUNT);
        positions.add(lamp());
        for (int i = 0; positions.size() < LIGHT_COUNT; i++) {
            int side = Math.max(1, (int) Math.ceil(Math.sqrt(LIGHT_COUNT)));
            BlockPos pos = PERFORMANCE
                    ? new BlockPos(-(side - 1) * 3 + (i % side) * 6, LAMP.getY(), -(side - 1) * 3 + (i / side) * 6)
                    : new BlockPos(-12 + (i % 4) * 8, LAMP.getY(), -8 + (i / 4) * 6);
            if (!positions.contains(pos)) positions.add(pos);
        }
        return List.copyOf(positions);
    }

    private static boolean vertical() {
        return "vertical".equals(System.getProperty("createLargeLights.gpuDemo.angle", "oblique"));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != knownLevel) {
            release();
            knownLevel = mc.level;
            ticks = 0;
            ready = false;
            linked = false;
            configurationChecked = false;
            previousFrame = 0;
            phase = -1;
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
        }
        if (mc.level == null || mc.getSingleplayerServer() == null) return;
        ticks++;
        if (PERFORMANCE && linked && ticks % 100 == 0) {
            mc.getSingleplayerServer().execute(() -> LogUtils.getLogger().info("PERF_FIELD ticks={} range={} server_tick_mean_ms={} metrics={}",
                    ticks, RANGE, mc.getSingleplayerServer().getAverageTickTimeNanos() / 1e6, LightFieldNetwork.metrics(mc.getSingleplayerServer().overworld())));
        }
        if (ticks == 40) {
            mc.getSingleplayerServer().execute(() -> longRows(mc.getSingleplayerServer().overworld()));
        } else if (ticks >= 160 && ticks % 20 == 0 && !linked) {
            mc.getSingleplayerServer().execute(() -> link(mc.getSingleplayerServer().overworld()));
        }
    }

    private static boolean configured(FloodlightBlock.Entity.Beam beam) {
        return beam.console() != null && beam.group() != null && beam.strength() == 1
                && beam.range() == RANGE && beam.spread() == 80 && beam.group().colors[0] == 0x55CCFF;
    }

    private static void link(ServerLevel level) {
        if (!(level.getBlockEntity(lamp()) instanceof FloodlightBlock.Entity anchor) || !configured(anchor.beam())) return;
        LightingConsoleBlock.Entity console = anchor.beam().console();
        LightingConsoleBlock.Entity second = LIGHT_COUNT <= 64 ? null : LightingConsoleBlock.find(level, SECOND_CONSOLE);
        if (LIGHT_COUNT > 64 && (second == null || !second.formed)) return;
        if (second != null) {
            second.settings.enabled = true;
            second.settings.master = 1;
            var group = second.settings.groups[0];
            group.brightness = 1; group.range = RANGE; group.pan = 0; group.tilt = Aim.NEUTRAL_TILT;
            group.motion = org.haruyq.createmediasystems.lighting.LightingSettings.byId(org.haruyq.createmediasystems.lighting.LightingSettings.Motion.values(), Integer.getInteger("createLargeLights.gpuDemo.motion", 0));
            group.colorCount = 1; group.colors[0] = 0x55CCFF;
            ConsoleSettings.setSpread(second, 0, 80);
            ConsoleSettings.setRealLight(second, 0, true);
        }
        for (int i = 0; i < LAMPS.size(); i++) {
            BlockPos pos = LAMPS.get(i);
            LightingConsoleBlock.Entity owner = i < 64 ? console : second;
            if (!(level.getBlockEntity(pos) instanceof FloodlightBlock.Entity light)) return;
            if ((light.group() != 0 || light.beam().console() != owner) && !owner.bind(light, 0)) return;
        }
        for (BlockPos pos : LAMPS) {
            if (!configured(((FloodlightBlock.Entity) level.getBlockEntity(pos)).beam())) return;
        }
        linked = true;
        if (second != null) second.changed();
    }

    private static boolean checkConfiguration(Level level) {
        int signature = 1;
        for (BlockPos pos : LAMPS) {
            if (!(level.getBlockEntity(pos) instanceof FloodlightBlock.Entity light)) return false;
            var beam = light.beam();
            if (light.group() != 0 || !configured(beam)) return false;
            signature = 31 * signature + pos.hashCode();
            signature = 31 * signature + Float.floatToIntBits(beam.yaw());
            signature = 31 * signature + Float.floatToIntBits(beam.pitch());
        }
        LogUtils.getLogger().info("GPU_TRIAL_CONFIG mode={} angle={} lights={} signature={} strength=1 spread=80 range={} color=55CCFF all_fixtures_verified=true",
                MODE, vertical() ? "vertical" : "oblique", LIGHT_COUNT, signature, RANGE);
        return true;
    }

    /** Connected geometry, rather than the old isolated three-block examples. */
    private static void longRows(Level level) {
        if (PERFORMANCE) {
            BlockState[] shapes = {Blocks.IRON_BARS.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(),
                    Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.QUARTZ_STAIRS.defaultBlockState(),
                    Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                    AllBlocks.COPYCAT_PANEL.getDefaultState(), AllBlocks.COPYCAT_STEP.getDefaultState()};
            int count = 0;
            for (int x = -36; x <= 36; x += 3) {
                for (int z = -36; z <= 36; z += 3) {
                    if (x >= 10 && x <= 20 && z >= -14 && z <= -6) continue;
                    for (int y = 1; y <= 3; y++) {
                        BlockPos pos = new BlockPos(x, FLOOR + y, z);
                        level.setBlock(pos, shapes[Math.floorMod(x + z + y, shapes.length)], Block.UPDATE_ALL);
                        if (level.getBlockEntity(pos) instanceof CopycatBlockEntity copycat) {
                            copycat.setMaterial((x & 1) == 0 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.STONE.defaultBlockState());
                        }
                    }
                    count += 3;
                }
            }
            LogUtils.getLogger().info("PERF_SCENE room=81x81x66 partial_blocks={} real_light=true", count);
        }
        if (LIGHT_COUNT > 64) {
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 2; z++) level.setBlock(org.haruyq.createmediasystems.lighting.ConsoleLayout.at(SECOND_CONSOLE, Direction.NORTH, x, 0, z),
                        org.haruyq.createmediasystems.lighting.LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState()
                                .setValue(LightingConsoleBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
                level.setBlock(org.haruyq.createmediasystems.lighting.ConsoleLayout.at(SECOND_CONSOLE, Direction.NORTH, x, 1, 1),
                        org.haruyq.createmediasystems.lighting.LightingFeature.LIGHTING_MONITOR.get().defaultBlockState()
                                .setValue(LightingMonitorBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
            }
        }
        for (int z = -3; z <= 14; z++) {
            set(level, -16, FLOOR + 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
            for (int y = 1; y <= 3; y++) set(level, -12, FLOOR + y, z, Blocks.IRON_BARS.defaultBlockState());
            set(level, -8, FLOOR + 1, z, Blocks.OAK_FENCE.defaultBlockState());
            set(level, -4, FLOOR + 1, z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            set(level, 0, FLOOR + 1, z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            set(level, 4, FLOOR + 1, z, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            set(level, 8, FLOOR + 1, z, Blocks.WHITE_CONCRETE.defaultBlockState());
            set(level, 12, FLOOR + 1, z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        }
        for (BlockPos pos : LAMPS) {
            if (pos.equals(lamp())) continue;
            level.setBlock(pos, CreateLargeLights.STADIUM_LIGHT.get().defaultBlockState()
                    .setValue(FloodlightBlock.FACING, Direction.DOWN).setValue(FloodlightBlock.POWER, 15), Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof FloodlightBlock.Entity light) {
                float[] aim = vertical() ? new float[]{0, 90}
                        : Aim.toward(new Vec3(.5, FLOOR + 1, 6).subtract(Vec3.atCenterOf(pos)), 0);
                light.spread = 3;
                light.aim(aim[0], aim[1]);
            }
        }
        LogUtils.getLogger().info("GPU_TRIAL_SCENE 18-block connected rows: walls, iron bars, fences, bottom/top/double slabs, stairs; angle={} lights={}",
                vertical() ? "vertical" : "oblique", LIGHT_COUNT);
    }

    private static void set(Level level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (!ENABLED || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ticks < 200 || !(mc.level.getBlockEntity(lamp()) instanceof FloodlightBlock.Entity)) return;
        if (!configurationChecked) {
            configurationChecked = checkConfiguration(mc.level);
            if (!configurationChecked) return;
        }
        benchmarkFrame();
        if (!GPU) return;
        State saved = new State();
        try {
            if (!ready) {
                for (BlockPos pos : LAMPS) {
                    if (!(mc.level.getBlockEntity(pos) instanceof FloodlightBlock.Entity)) return;
                }
                initialize(mc);
            }
            pollQueries();
            int query = nextQuery;
            boolean measure = !pending[query];
            if (measure) GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[query]);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saved.framebuffer);
            GL11.glViewport(saved.viewport[0], saved.viewport[1], saved.viewport[2], saved.viewport[3]);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(-1, -1);
            GL20.glUseProgram(colorProgram);
            Vec3 camera = event.getCamera().getPosition();
            Matrix4f cameraProjection = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix())
                    .translate((float) (lamp().getX() - camera.x), (float) (lamp().getY() - camera.y), (float) (lamp().getZ() - camera.z));
            matrix(colorProgram, "ViewProjection", cameraProjection);
            GL20.glUniform1i(GL20.glGetUniformLocation(colorProgram, "Atlas"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(colorProgram, "Shadow"), 1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            for (Projector light : projectors) {
                matrix(colorProgram, "LightProjection", light.projection);
                vector(colorProgram, "Origin", light.origin);
                vector(colorProgram, "Direction", light.direction);
                vector(colorProgram, "LightRight", light.right);
                vector(colorProgram, "LightUp", light.up);
                GL20.glUniform3f(GL20.glGetUniformLocation(colorProgram, "LightColor"), (light.color >> 16 & 255) / 255f,
                        (light.color >> 8 & 255) / 255f, (light.color & 255) / 255f);
                uniform(colorProgram, "Tangent", light.tangent);
                uniform(colorProgram, "Range", light.range);
                uniform(colorProgram, "Strength", light.strength);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, light.depthTexture);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
            }
            if (measure) {
                GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                pending[query] = true;
                nextQuery = (query + 1) % queries.length;
            }
        } finally {
            saved.restore();
        }
    }

    private static void initialize(Minecraft mc) {
        long start = System.nanoTime();
        FloatArrayList vertices = new FloatArrayList();
        RandomSource random = RandomSource.create(42);
        int quads = 0, dataModels = 0, blockEntities = 0;
        BlockPos anchor = lamp();
        List<BlockPos> sources = LAMPS;
        for (BlockPos pos : BlockPos.betweenClosed(-20, FLOOR, -14, 20, FLOOR + 30, 18)) {
            if (!mc.level.isLoaded(pos) || sources.contains(pos)) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            var model = mc.getBlockRenderer().getBlockModel(state);
            ModelData raw = mc.level.getModelDataManager().getAt(pos);
            ModelData data = model.getModelData(mc.level, pos, state, raw == null ? ModelData.EMPTY : raw);
            if (data != ModelData.EMPTY) dataModels++;
            if (mc.level.getBlockEntity(pos) != null) blockEntities++;
            Vec3 offset = Vec3.atLowerCornerOf(pos).add(state.getOffset(mc.level, pos)).subtract(Vec3.atLowerCornerOf(anchor));
            random.setSeed(state.getSeed(pos));
            for (RenderType layer : model.getRenderTypes(state, random, data)) {
                for (int side = 0; side <= 6; side++) {
                    Direction direction = side == 6 ? null : Direction.from3DDataValue(side);
                    if (direction != null && !Block.shouldRenderFace(state, mc.level, pos, direction, pos.relative(direction))) continue;
                    random.setSeed(state.getSeed(pos));
                    for (BakedQuad quad : model.getQuads(state, direction, random, data, layer)) {
                        append(vertices, quad, offset, quad.isTinted()
                                ? mc.getBlockColors().getColor(state, mc.level, pos, quad.getTintIndex()) : 0xFFFFFF);
                        quads++;
                    }
                }
            }
        }
        double snapshotMs = (System.nanoTime() - start) / 1e6;
        vertexCount = vertices.size() / STRIDE;
        vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer buffer = MemoryUtil.memAllocFloat(vertices.size());
        try {
            buffer.put(vertices.elements(), 0, vertices.size()).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        } finally { MemoryUtil.memFree(buffer); }
        int[] widths = {3, 2, 3, 3}; int offset = 0;
        for (int i = 0; i < widths.length; i++) {
            GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(i, widths[i], GL11.GL_FLOAT, false, STRIDE * 4, offset * 4L);
            offset += widths[i];
        }
        depthProgram = program("depth.fsh"); colorProgram = program("projector.fsh");
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LESS); GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND); GL11.glColorMask(false, false, false, false);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL); GL11.glClearDepth(1);
        GL20.glUseProgram(depthProgram);
        GL20.glUniform1i(GL20.glGetUniformLocation(depthProgram, "Atlas"), 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
        for (BlockPos pos : sources) {
            projectors.add(projector(pos, anchor, (FloodlightBlock.Entity) mc.level.getBlockEntity(pos)));
        }
        shadowQuery = GL15.glGenQueries();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, shadowQuery);
        for (Projector light : projectors) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, light.framebuffer);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            matrix(depthProgram, "ViewProjection", light.projection);
            matrix(depthProgram, "LightProjection", light.projection);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        }
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED); shadowPending = true;
        for (int i = 0; i < queries.length; i++) queries[i] = GL15.glGenQueries();
        ready = true;
        LogUtils.getLogger().info("GPU_TRIAL_READY mode=gpu angle={} lights={} shadow={} quads={} vertices={} modelData={} blockEntities={} snapshot_cpu_ms={} setup_cpu_ms={} vbo_bytes={} depth_bytes={} static_scene=true BER_and_fluids_not_captured=true",
                vertical() ? "vertical" : "oblique", LIGHT_COUNT, SIZE, quads, vertexCount, dataModels, blockEntities, snapshotMs,
                (System.nanoTime() - start) / 1e6, (long) vertices.size() * 4, (long) LIGHT_COUNT * SIZE * SIZE * 4);
    }

    private static Projector projector(BlockPos pos, BlockPos anchor, FloodlightBlock.Entity fixture) {
        int depthTexture = GL11.glGenTextures(); GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, SIZE, SIZE, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        int framebuffer = GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Shadow framebuffer: " + status);
        var beam = fixture.beam();
        var head = Aim.head(beam.yaw(), beam.pitch());
        Vector3f lightOrigin = head.transformPosition(new Vector3f(0, 0, fixture.kind().lensZ + .01f))
                .add(pos.getX() - anchor.getX(), pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ());
        Vector3f lightDirection = head.transformDirection(new Vector3f(0, 0, 1)).normalize();
        Vector3f up = head.transformDirection(new Vector3f(0, 1, 0)).normalize();
        // lookAt's right is forward cross up; it is opposite the head's local +X.
        Vector3f right = new Vector3f(lightDirection).cross(up).normalize();
        up = new Vector3f(right).cross(lightDirection).normalize();
        float range = beam.range();
        Matrix4f lightProjection = new Matrix4f().perspective((float) Math.toRadians(beam.spread()), 1, NEAR, range)
                .lookAt(lightOrigin, new Vector3f(lightOrigin).add(lightDirection), up);
        return new Projector(lightProjection, lightOrigin, lightDirection, right, up,
                (float) Math.tan(Math.toRadians(beam.spread()) / 2), range, beam.strength() * fixture.kind().intensity,
                beam.group() == null ? 0xFFF1DC : beam.group().colors[0], depthTexture, framebuffer);
    }

    private static void append(FloatArrayList out, BakedQuad quad, Vec3 offset, int tint) {
        int[] data = quad.getVertices();
        for (int vertex : TRIANGLES) {
            int base = vertex * IQuadTransformer.STRIDE;
            for (int axis = 0; axis < 3; axis++) out.add((float) (Float.intBitsToFloat(data[base + IQuadTransformer.POSITION + axis])
                    + (axis == 0 ? offset.x : axis == 1 ? offset.y : offset.z)));
            out.add(Float.intBitsToFloat(data[base + IQuadTransformer.UV0]));
            out.add(Float.intBitsToFloat(data[base + IQuadTransformer.UV0 + 1]));
            int normal = data[base + IQuadTransformer.NORMAL];
            if (normal == 0) {
                out.add(quad.getDirection().getStepX()); out.add(quad.getDirection().getStepY()); out.add(quad.getDirection().getStepZ());
            } else {
                out.add((byte) normal / 127f); out.add((byte) (normal >> 8) / 127f); out.add((byte) (normal >> 16) / 127f);
            }
            int rgba = data[base + IQuadTransformer.COLOR];
            out.add((rgba & 255) / 255f * (tint >> 16 & 255) / 255f);
            out.add((rgba >> 8 & 255) / 255f * (tint >> 8 & 255) / 255f);
            out.add((rgba >> 16 & 255) / 255f * (tint & 255) / 255f);
        }
    }

    private static void benchmarkFrame() {
        int window = ticks >= 300 && ticks <= 500 ? 0 : ticks >= 650 && ticks <= 750 ? 1 : ticks >= 875 && ticks <= 975 ? 2 : -1;
        if (window != phase) {
            if (phase >= 0) report();
            frameCount = gpuCount = 0; previousFrame = 0; phase = window;
            productionNanos = productionSamples = 0;
            var metrics = GpuLightProjection.metrics();
            phaseShadows = metrics.shadowUpdates(); phaseSurfaces = metrics.surfaceDraws();
        }
        if (phase < 0) return;
        long now = System.nanoTime();
        if (previousFrame != 0 && frameCount < frames.length) frames[frameCount++] = (now - previousFrame) / 1e6;
        previousFrame = now;
    }

    public static void beginProduction() {
        if (!ENABLED || !production() || phase < 0) return;
        pollQueries();
        int query = nextQuery;
        productionQuery = !pending[query];
        if (productionQuery) {
            if (queries[query] == 0) queries[query] = GL15.glGenQueries();
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[query]);
        }
        productionStart = System.nanoTime();
    }

    public static void endProduction() {
        if (!ENABLED || !production() || phase < 0) return;
        productionNanos += System.nanoTime() - productionStart;
        productionSamples++;
        if (productionQuery) {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            pending[nextQuery] = true;
            nextQuery = (nextQuery + 1) % queries.length;
        }
    }

    private static void pollQueries() {
        if (shadowPending && GL15.glGetQueryObjecti(shadowQuery, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            LogUtils.getLogger().info("GPU_TRIAL_SHADOW gpu_ms={} lights={} shadow={} cached_until_scene_changes=true",
                    GL33.glGetQueryObjectui64(shadowQuery, GL15.GL_QUERY_RESULT) / 1e6, LIGHT_COUNT, SIZE);
            shadowPending = false;
        }
        for (int i = 0; i < queries.length; i++) {
            if (pending[i] && GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                double ms = GL33.glGetQueryObjectui64(queries[i], GL15.GL_QUERY_RESULT) / 1e6;
                if (phase >= 0 && gpuCount < gpuTimes.length) gpuTimes[gpuCount++] = ms;
                pending[i] = false;
            }
        }
    }

    private static void report() {
        if (frameCount == 0) return;
        double[] sorted = Arrays.copyOf(frames, frameCount); Arrays.sort(sorted);
        double[] gpuSorted = Arrays.copyOf(gpuTimes, gpuCount); Arrays.sort(gpuSorted);
        LogUtils.getLogger().info("GPU_TRIAL_BENCH mode={} angle={} lights={} view={} shadow={} frame_samples={} frame_p50_ms={} frame_p95_ms={} frame_p99_ms={} frame_max_ms={} overlay_gpu_samples={} overlay_gpu_p50_ms={} overlay_gpu_p95_ms={} fps_cap=260 vsync=false",
                MODE, vertical() ? "vertical" : "oblique", LIGHT_COUNT, phase, SIZE, frameCount,
                percentile(sorted, .5), percentile(sorted, .95), percentile(sorted, .99), sorted[sorted.length - 1],
                gpuCount, percentile(gpuSorted, .5), percentile(gpuSorted, .95));
        if ("cms".equals(MODE)) reportNativePath();
        if (production()) {
            var metrics = GpuLightProjection.metrics();
            LogUtils.getLogger().info("PERF_PRODUCTION lights={} view={} samples={} render_cpu_mean_ms={} shadows_per_frame={} surfaces_per_frame={} depth_bytes={} geometry_bytes={} drawn_lights={}",
                    LIGHT_COUNT, phase, productionSamples, productionSamples == 0 ? 0 : productionNanos / 1e6 / productionSamples,
                    productionSamples == 0 ? 0 : (double) (metrics.shadowUpdates() - phaseShadows) / productionSamples,
                    productionSamples == 0 ? 0 : (double) (metrics.surfaceDraws() - phaseSurfaces) / productionSamples,
                    metrics.depthBytes(), GpuLightGeometry.bytes(), metrics.drawnLights());
        }
    }

    private static void reportNativePath() {
        int cpu = 0, gpu = 0, unknown = 0;
        try {
            if (!nativeLocated) {
                nativeLocated = true;
                nativeBeams = LightingIllumination.class.getDeclaredField("BEAMS");
                nativeBeams.setAccessible(true);
                try {
                    nativeGpu = Class.forName(LightingIllumination.class.getName() + "$Beam").getDeclaredField("gpu");
                    nativeGpu.setAccessible(true);
                } catch (NoSuchFieldException absentBeforeGpuPath) {
                    // CMS 1.2.0 has only the CPU surface path.
                }
            }
            Map<?, ?> beams = (Map<?, ?>) nativeBeams.get(null);
            for (BlockPos pos : LAMPS) {
                Object fixture = Minecraft.getInstance().level.getBlockEntity(pos);
                Object beam = beams.get(fixture);
                if (beam == null) unknown++;
                else if (nativeGpu != null && nativeGpu.getBoolean(beam)) gpu++;
                else cpu++;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            cpu = gpu = 0;
            unknown = LIGHT_COUNT;
            LogUtils.getLogger().warn("GPU_TRIAL_NATIVE path inspection failed", error);
        }
        LogUtils.getLogger().info("GPU_TRIAL_NATIVE mode={} angle={} lights={} view={} cpu={} gpu={} unknown={} gpu_field={}",
                MODE, vertical() ? "vertical" : "oblique", LIGHT_COUNT, phase, cpu, gpu, unknown, nativeGpu != null);
    }

    private static double percentile(double[] values, double q) {
        return values.length == 0 ? Double.NaN : values[Math.min(values.length - 1, (int) Math.floor((values.length - 1) * q))];
    }

    private static int program(String fragment) {
        int vertex = shader(GL20.GL_VERTEX_SHADER, "projector.vsh"); int pixel = shader(GL20.GL_FRAGMENT_SHADER, fragment);
        int program = GL20.glCreateProgram(); GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, pixel);
        String[] attributes = {"Position", "UV", "Normal", "Tint"};
        for (int i = 0; i < attributes.length; i++) GL20.glBindAttribLocation(program, i, attributes[i]);
        GL20.glLinkProgram(program); GL20.glDeleteShader(vertex); GL20.glDeleteShader(pixel);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
        return program;
    }

    private static int shader(int type, String name) {
        String path = "/assets/create_large_lights/shaders/prototype/" + name;
        try (var in = GpuProjectionDemo.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException(path);
            int shader = GL20.glCreateShader(type); GL20.glShaderSource(shader, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            return shader;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void matrix(int program, String name, Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, name), false, matrix.get(stack.mallocFloat(16)));
        }
    }

    private static void vector(int program, String name, Vector3f value) {
        GL20.glUniform3f(GL20.glGetUniformLocation(program, name), value.x, value.y, value.z);
    }

    private static void uniform(int program, String name, float value) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, name), value);
    }

    private static void release() {
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        for (Projector light : projectors) {
            GL30.glDeleteFramebuffers(light.framebuffer);
            GL11.glDeleteTextures(light.depthTexture);
        }
        projectors.clear();
        if (depthProgram != 0) GL20.glDeleteProgram(depthProgram);
        if (colorProgram != 0) GL20.glDeleteProgram(colorProgram);
        if (shadowQuery != 0) GL15.glDeleteQueries(shadowQuery);
        for (int i = 0; i < queries.length; i++) {
            if (queries[i] != 0) GL15.glDeleteQueries(queries[i]);
            queries[i] = 0; pending[i] = false;
        }
        vao = vbo = depthProgram = colorProgram = shadowQuery = 0;
    }

    /** Restore actual GL state as well as keeping Minecraft's cached state unchanged. */
    private static final class State {
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        final int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), destRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), destAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), polygon = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final float polygonFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), polygonUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        final int[] viewport = new int[4];
        final boolean[] colors = new boolean[4];
        final int texture0, texture1;

        State() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer v = stack.mallocInt(4); GL11.glGetIntegerv(GL11.GL_VIEWPORT, v); v.get(viewport);
                var mask = stack.malloc(4); GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colors[i] = mask.get(i) != 0;
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0); texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        void restore() {
            GL20.glUseProgram(program); GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture1);
            GL13.glActiveTexture(activeTexture);
            GL11.glDepthFunc(depthFunction); GL11.glDepthMask(depthMask);
            GL14.glBlendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha); GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            GL11.glPolygonOffset(polygonFactor, polygonUnits); GL11.glColorMask(colors[0], colors[1], colors[2], colors[3]);
            enabled(GL11.GL_DEPTH_TEST, depth); enabled(GL11.GL_BLEND, blend); enabled(GL11.GL_CULL_FACE, cull); enabled(GL11.GL_POLYGON_OFFSET_FILL, polygon);
        }

        private static void enabled(int cap, boolean value) { if (value) GL11.glEnable(cap); else GL11.glDisable(cap); }
    }
}
