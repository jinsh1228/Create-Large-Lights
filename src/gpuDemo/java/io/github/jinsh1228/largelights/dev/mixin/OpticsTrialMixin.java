package io.github.jinsh1228.largelights.dev.mixin;

import org.haruyq.createmediasystems.api.lighting.client.FixtureOptics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.github.jinsh1228.largelights.client.FloodlightOptics;
import io.github.jinsh1228.largelights.dev.GpuProjectionDemo;

/** The CMS comparison keeps its default optics. Only the add-on's registration is intercepted. */
@Mixin(value = FloodlightOptics.class, remap = false)
abstract class OpticsTrialMixin {
    @Redirect(method = "register", at = @At(value = "INVOKE", target =
            "Lorg/haruyq/createmediasystems/api/lighting/client/FixtureOptics;register(Ljava/lang/String;Lorg/haruyq/createmediasystems/api/lighting/client/FixtureOptics;)V"))
    private static void optics(String family, FixtureOptics optics) {
        if (!GpuProjectionDemo.cms()) FixtureOptics.register(family, optics);
    }
}
