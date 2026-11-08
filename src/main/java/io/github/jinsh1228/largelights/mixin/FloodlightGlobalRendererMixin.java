package io.github.jinsh1228.largelights.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import io.github.jinsh1228.largelights.FloodlightBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Terrain section recycling and renderer reloads must retain loaded CLL off-screen fixtures. */
@Mixin(LevelRenderer.class)
abstract class FloodlightGlobalRendererMixin {
    @Shadow @Final private Set<BlockEntity> globalBlockEntities;
    @Shadow private ClientLevel level;
    @Unique private Collection<BlockEntity> createLargeLights$reload = List.of();

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void createLargeLights$beforeReload(CallbackInfo ci) {
        var retained = new ArrayList<BlockEntity>();
        synchronized (globalBlockEntities) {
            for (BlockEntity entity : globalBlockEntities) {
                if (createLargeLights$loaded(entity)) retained.add(entity);
            }
        }
        createLargeLights$reload = retained;
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void createLargeLights$afterReload(CallbackInfo ci) {
        synchronized (globalBlockEntities) {
            for (BlockEntity entity : createLargeLights$reload) {
                if (createLargeLights$loaded(entity)) globalBlockEntities.add(entity);
            }
        }
        createLargeLights$reload = List.of();
    }

    @ModifyVariable(method = "updateGlobalBlockEntities", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Collection<BlockEntity> createLargeLights$keepLoaded(Collection<BlockEntity> removing) {
        for (BlockEntity entity : removing) {
            if (createLargeLights$loaded(entity)) {
                var filtered = new ArrayList<BlockEntity>(removing.size());
                for (BlockEntity candidate : removing) {
                    if (!createLargeLights$loaded(candidate)) filtered.add(candidate);
                }
                return filtered;
            }
        }
        return removing;
    }

    @Inject(method = "updateGlobalBlockEntities", at = @At("RETURN"))
    private void createLargeLights$discardRemoved(Collection<BlockEntity> removing, Collection<BlockEntity> adding,
                                                   CallbackInfo ci) {
        synchronized (globalBlockEntities) {
            for (BlockEntity entity : adding) {
                if (entity instanceof FloodlightBlock.Entity && !createLargeLights$loaded(entity)) {
                    globalBlockEntities.remove(entity);
                }
            }
        }
    }

    @Unique
    private boolean createLargeLights$loaded(BlockEntity entity) {
        if (!(entity instanceof FloodlightBlock.Entity) || entity.isRemoved()) return false;
        var world = entity.getLevel();
        return world != null && world.isClientSide && world == level;
    }
}
