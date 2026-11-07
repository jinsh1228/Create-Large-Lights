package io.github.cmsfloodlights;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per world (serverconfig/cms_floodlights-server.toml): how floodlights light the world itself. */
public final class FloodlightConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue REAL_LIGHT;
    public static final ModConfigSpec.IntValue SPACING;
    public static final ModConfigSpec.IntValue MAX_LIGHTS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        REAL_LIGHT = builder
                .comment("Light the world where the beams fall, with invisible light blocks (minecraft:light), so the",
                        "light level there rises: mobs stop spawning, crops grow. Off, floodlights only look lit.")
                .define("realLight", true);
        SPACING = builder
                .comment("Blocks between the light blocks a beam places. Closer is more even, and more blocks.")
                .defineInRange("lightSpacing", 5, 3, 8);
        MAX_LIGHTS = builder
                .comment("The most light blocks one floodlight places.")
                .defineInRange("maxLightsPerFixture", 256, 16, 1024);
        SPEC = builder.build();
    }

    private FloodlightConfig() {
    }
}
