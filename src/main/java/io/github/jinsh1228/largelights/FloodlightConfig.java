package io.github.jinsh1228.largelights;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per world (serverconfig/create_large_lights-server.toml): how floodlights light the world itself. */
public final class FloodlightConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue REAL_LIGHT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        REAL_LIGHT = builder
                .comment("Add floodlight illumination to the world's block light, including mob spawning and crop growth.",
                        "Illumination is added across fixtures and clipped to their beams; no light blocks are placed.")
                .define("realLight", true);
        SPEC = builder.build();
    }

    private FloodlightConfig() {
    }
}
