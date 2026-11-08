package io.github.jinsh1228.largelights.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.util.random.WeightedEntry;

@Mixin(WeightedBakedModel.class)
public interface WeightedModelAccessor {
    @Accessor("list")
    List<WeightedEntry.Wrapper<BakedModel>> createLargeLights$models();
}
