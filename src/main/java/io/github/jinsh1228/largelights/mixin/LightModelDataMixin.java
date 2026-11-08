package io.github.jinsh1228.largelights.mixin;

import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.model.data.ModelDataManager;

/** Model data can change the rendered shape without changing its block state. */
@Mixin(value = ModelDataManager.class, remap = false)
abstract class LightModelDataMixin {
    @Inject(method = "requestRefresh", at = @At("RETURN"))
    private void createLargeLights$changed(BlockEntity blockEntity, CallbackInfo ci) {
        var pos = blockEntity.getBlockPos();
        LightingIllumination.blocksChanged(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }
}
