package io.github.jinsh1228.largelights.client;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;

import io.github.jinsh1228.largelights.CreateLargeLights;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The light meter, held: the floors around (where a mob could stand) tinted by their light from lamps, red where
 * it is 0 (mobs may spawn there in the dark), yellow up to 7, green from 8; and above the hotbar, the light where it
 * points and how many dark spots are around. The floors are looked over every few ticks, or on stepping to
 * another block, and only drawn each frame.
 */
public final class LightMeter {
    /** Blocks around the player looked over, across and down and up. */
    static final int RADIUS = 12;
    static final int BELOW = 6;
    static final int ABOVE = 3;
    private static final int RESCAN = 10;
    private static final int DARK = 0xE8403A;
    private static final int DIM = 0xF0C030;
    private static final int LIT = 0x3CC864;
    /** Each floor found: x, y, z (of the air over it) and colour, packed in 4 ints. */
    private static IntArrayList floors = new IntArrayList();
    private static int darkSpots;
    private static BlockPos scannedAt;
    private static long scannedTick = Long.MIN_VALUE;

    private LightMeter() {
    }

    static boolean held(Player player) {
        return player != null && (player.getMainHandItem().is(CreateLargeLights.LIGHT_METER.get())
                || player.getOffhandItem().is(CreateLargeLights.LIGHT_METER.get()));
    }

    /** The tint for {@code light} from lamps. */
    static int color(int light) {
        return light == 0 ? DARK : light < 8 ? DIM : LIT;
    }

    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !held(mc.player)) {
            if (scannedAt != null) {
                floors = new IntArrayList();
                scannedAt = null;
            }
            return;
        }
        BlockPos at = mc.player.blockPosition();
        long now = mc.level.getGameTime();
        if (at.equals(scannedAt) && now - scannedTick < RESCAN) {
            return;
        }
        scannedAt = at;
        scannedTick = now;
        scan(mc.level, at);
        BlockPos pointed = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos().relative(hit.getDirection()) : at;
        mc.player.displayClientMessage(Component.translatable("create_large_lights.light_meter.reading",
                mc.level.getMaxLocalRawBrightness(pointed), mc.level.getBrightness(LightLayer.BLOCK, pointed),
                mc.level.getBrightness(LightLayer.SKY, pointed), darkSpots), true);
    }

    private static void scan(Level level, BlockPos center) {
        IntArrayList found = new IntArrayList();
        int dark = 0;
        BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos air = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) {
                    continue;
                }
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    air.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    floor.set(air.getX(), air.getY() - 1, air.getZ());
                    if (!level.isLoaded(air)) {
                        continue;
                    }
                    BlockState above = level.getBlockState(air);
                    // Somewhere a mob could stand: a solid top, and room over it.
                    if (!above.getCollisionShape(level, air).isEmpty() || !above.getFluidState().isEmpty()
                            || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                        continue;
                    }
                    int light = level.getBrightness(LightLayer.BLOCK, air);
                    if (light == 0) {
                        dark++;
                    }
                    found.add(air.getX());
                    found.add(air.getY());
                    found.add(air.getZ());
                    found.add(color(light));
                }
            }
        }
        floors = found;
        darkSpots = dark;
    }

    /** Count of dark spots and floors last looked over (for the demo). */
    public static int[] reading() {
        return new int[]{darkSpots, floors.size() / 4};
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || floors.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        // As CMS draws its beams: the frame's view and projection set for this, the tiles relative to the camera.
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            Matrix4f matrix = new Matrix4f().translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            VertexConsumer out = buffers.getBuffer(RenderType.debugQuads());
            int[] data = floors.elements();
            for (int i = 0, n = floors.size(); i < n; i += 4) {
                float x = data[i], y = data[i + 1] + 0.02f, z = data[i + 2];
                int color = data[i + 3];
                int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
                // A tile just over the floor, a little in from its edges, so neighbours read apart.
                out.addVertex(matrix, x + 0.1f, y, z + 0.1f).setColor(r, g, b, 96);
                out.addVertex(matrix, x + 0.1f, y, z + 0.9f).setColor(r, g, b, 96);
                out.addVertex(matrix, x + 0.9f, y, z + 0.9f).setColor(r, g, b, 96);
                out.addVertex(matrix, x + 0.9f, y, z + 0.1f).setColor(r, g, b, 96);
            }
            buffers.endBatch(RenderType.debugQuads());
        } finally {
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
