package io.github.jinsh1228.largelights.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.haruyq.createmediasystems.api.client.WorldChanges;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.model.data.ModelDataManager;

/** Model data can change the rendered shape without changing its block state. */
@Mixin(value = ModelDataManager.class, remap = false)
abstract class LightModelDataMixin {
    @Inject(method = "requestRefresh", at = @At("RETURN"))
    private void createLargeLights$changed(BlockEntity blockEntity, CallbackInfo ci) {
        var pos = blockEntity.getBlockPos();
        if (RenderSystem.isOnRenderThread()) {
            WorldChanges.blocksChanged(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        } else {
            Minecraft.getInstance().execute(() -> WorldChanges.blocksChanged(
                    pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ()));
        }
    }
}
