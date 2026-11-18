package io.github.jinsh1228.largelights.mixin;

import java.util.BitSet;
import java.util.Iterator;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import io.github.jinsh1228.largelights.client.LightOnlyUpdates;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Vanilla marks sections dirty with their neighbours from these two methods only, for light data: a chunk's light
 * enabled after it loaded (its blocks are reported as a chunk load) and received light sections. Shapes are unchanged.
 */
@Mixin(ClientPacketListener.class)
abstract class LightOnlySectionsMixin {
    @WrapMethod(method = "enableChunkLight")
    private void createLargeLights$enableChunkLight(LevelChunk chunk, int x, int z, Operation<Void> original) {
        LightOnlyUpdates.run(() -> original.call(chunk, x, z));
    }

    @WrapMethod(method = "readSectionList")
    private void createLargeLights$readSectionList(int x, int z, LevelLightEngine engine, LightLayer layer,
                                                   BitSet present, BitSet empty, Iterator<byte[]> data,
                                                   Operation<Void> original) {
        LightOnlyUpdates.run(() -> original.call(x, z, engine, layer, present, empty, data));
    }
}
