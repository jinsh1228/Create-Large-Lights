package io.github.jinsh1228.largelights.client;

import io.github.jinsh1228.largelights.LightField;
import io.github.jinsh1228.largelights.LightFieldNetwork;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Rebuild only affected, visible terrain sections; the light field never writes vanilla light storage. */
public final class LightFieldUpdates {
    private static final int REBUILDS_PER_TICK = 32;
    private static final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private static ClientLevel queuedLevel;

    private LightFieldUpdates() {
    }

    public static void register() {
        LightField.setChangeListener(LightFieldUpdates::changed);
        NeoForge.EVENT_BUS.addListener(LightFieldUpdates::tick);
        NeoForge.EVENT_BUS.addListener(LightFieldUpdates::unload);
    }

    private static void changed(Level level, AABB bounds) {
        if (!level.isClientSide) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level != level || mc.player == null) {
                return;
            }
            if (queuedLevel != mc.level) {
                pending.clear();
                queuedLevel = mc.level;
            }
            int cameraX = viewSection(mc.player.getX()), cameraZ = viewSection(mc.player.getZ());
            int distance = mc.options.getEffectiveRenderDistance();
            // One block of padding includes adjacent faces and ambient-occlusion samples.
            int minX = Math.max(SectionPos.blockToSectionCoord(Mth.floor(bounds.minX - 1)), cameraX - distance);
            int maxX = Math.min(SectionPos.blockToSectionCoord(Mth.floor(bounds.maxX + 1)), cameraX + distance);
            int minZ = Math.max(SectionPos.blockToSectionCoord(Mth.floor(bounds.minZ - 1)), cameraZ - distance);
            int maxZ = Math.min(SectionPos.blockToSectionCoord(Mth.floor(bounds.maxZ + 1)), cameraZ + distance);
            int minY = Math.max(SectionPos.blockToSectionCoord(Mth.floor(bounds.minY - 1)), level.getMinSection());
            int maxY = Math.min(SectionPos.blockToSectionCoord(Mth.floor(bounds.maxY + 1)), level.getMaxSection() - 1);
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    var chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (int y = minY; y <= maxY; y++) {
                        // Empty terrain sections can still contain instanced models and their light samples.
                        pending.add(SectionPos.asLong(x, y, z));
                    }
                }
            }
        });
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != queuedLevel) {
            pending.clear();
            queuedLevel = mc.level;
        }
        if (queuedLevel == null || mc.player == null) {
            return;
        }
        LightFieldNetwork.requestMissing(queuedLevel);
        int cameraX = viewSection(mc.player.getX()), cameraZ = viewSection(mc.player.getZ());
        int distance = mc.options.getEffectiveRenderDistance();
        int rebuilt = 0;
        while (rebuilt < REBUILDS_PER_TICK && !pending.isEmpty()) {
            long section = pending.removeFirstLong();
            int x = SectionPos.x(section), y = SectionPos.y(section), z = SectionPos.z(section);
            // ViewArea wraps section coordinates, so never dirty a section outside the current view grid.
            if (Math.abs(x - cameraX) <= distance && Math.abs(z - cameraZ) <= distance) {
                mc.level.getChunkSource().onLightUpdate(LightLayer.BLOCK, SectionPos.of(x, y, z));
                rebuilt++;
            }
        }
    }

    private static int viewSection(double position) {
        // Match ViewArea.repositionCamera, including exact section-boundary positions.
        return Math.floorDiv(Mth.ceil(position) - 1, 16);
    }

    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            Minecraft.getInstance().execute(() -> {
                if (event.getLevel() == queuedLevel) {
                    pending.clear();
                    queuedLevel = null;
                }
            });
        }
    }
}
