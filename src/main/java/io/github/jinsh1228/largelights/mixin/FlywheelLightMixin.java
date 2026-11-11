package io.github.jinsh1228.largelights.mixin;

import io.github.jinsh1228.largelights.LightFieldNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Retain Flywheel's fast bulk collector while adding CLL's immutable client section overlay. */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.LightDataCollector", remap = false)
abstract class FlywheelLightMixin {
    @Shadow
    private static Long2ObjectFunction<DataLayer> createFastBlockDataGetter(LayerLightEventListener listener) {
        throw new AssertionError();
    }

    @Redirect(method = "of", at = @At(value = "INVOKE", target =
            "Ldev/engine_room/flywheel/backend/engine/LightDataCollector;createFastBlockDataGetter(Lnet/minecraft/world/level/lighting/LayerLightEventListener;)Lit/unimi/dsi/fastutil/longs/Long2ObjectFunction;"))
    private static Long2ObjectFunction<DataLayer> createLargeLights$sections(LayerLightEventListener listener, LevelAccessor world) {
        Long2ObjectFunction<DataLayer> original = createFastBlockDataGetter(listener);
        if (original == null || !(world instanceof Level level) || !level.isClientSide) return original;
        return section -> LightFieldNetwork.section(level, section, original.get(section));
    }
}
