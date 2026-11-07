package io.github.jinsh1228.largelights;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingSettings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * What the floodlight page (client.FloodlightPage) sets on a lighting console, kept in the console's extras so they
 * are saved and synced with it: per group, the beam width, whether it lights the world (RealLight) and how brightly
 * at most; and the console's scenes and how long recalling one fades for.
 */
public final class ConsoleSettings {
    public static final String KEY = CreateLargeLights.MOD_ID;
    public static final float DEFAULT_SPREAD = 40;
    public static final float MAX_SPREAD = 80;
    public static final float DEFAULT_FADE = 3;
    public static final float MAX_FADE = 10;
    public static final int SCENES = 4;
    /** A scene: each group's brightness, then the master. */
    public static final int SCENE_SIZE = LightingSettings.GROUPS + 1;
    /** Match, practice, cleaning, off: every group at 100%, 60%, 30%, 0%. */
    private static final float[] SCENE_LEVELS = {1, 0.6f, 0.3f, 0};

    private ConsoleSettings() {
    }

    private static CompoundTag group(LightingConsoleBlock.Entity console, int group) {
        CompoundTag root = console.extras(KEY);
        String key = "Group" + group;
        if (!root.contains(key, Tag.TAG_COMPOUND)) {
            root.put(key, new CompoundTag());
        }
        return root.getCompound(key);
    }

    public static float spread(LightingConsoleBlock.Entity console, int group) {
        CompoundTag tag = group(console, group);
        return tag.contains("Spread") ? Mth.clamp(tag.getFloat("Spread"), 1, MAX_SPREAD) : DEFAULT_SPREAD;
    }

    public static void setSpread(LightingConsoleBlock.Entity console, int group, float spread) {
        group(console, group).putFloat("Spread", Mth.clamp(spread, 1, MAX_SPREAD));
    }

    public static boolean realLight(LightingConsoleBlock.Entity console, int group) {
        CompoundTag tag = group(console, group);
        return !tag.contains("RealLight") || tag.getBoolean("RealLight");
    }

    public static void setRealLight(LightingConsoleBlock.Entity console, int group, boolean on) {
        group(console, group).putBoolean("RealLight", on);
    }

    /** The brightest a group's floodlights light the world, as a light level. */
    public static int maxLevel(LightingConsoleBlock.Entity console, int group) {
        CompoundTag tag = group(console, group);
        return tag.contains("MaxLevel") ? Mth.clamp(tag.getInt("MaxLevel"), 1, 15) : 15;
    }

    public static void setMaxLevel(LightingConsoleBlock.Entity console, int group, int level) {
        group(console, group).putInt("MaxLevel", Mth.clamp(level, 1, 15));
    }

    /** How a group's floodlights may light the world: their light level at most, 0 for not at all. */
    public static int lightLimit(LightingConsoleBlock.Entity console, int group) {
        return realLight(console, group) ? maxLevel(console, group) : 0;
    }

    /** Seconds a recalled scene takes to fade in. */
    public static float fade(LightingConsoleBlock.Entity console) {
        CompoundTag root = console.extras(KEY);
        return root.contains("Fade") ? Mth.clamp(root.getFloat("Fade"), 0, MAX_FADE) : DEFAULT_FADE;
    }

    public static void setFade(LightingConsoleBlock.Entity console, float seconds) {
        console.extras(KEY).putFloat("Fade", Mth.clamp(seconds, 0, MAX_FADE));
    }

    /** Scene {@code index}: each group's brightness, then the master (SCENE_SIZE values, 0 to 1). */
    public static float[] scene(LightingConsoleBlock.Entity console, int index) {
        ListTag scenes = console.extras(KEY).getList("Scenes", Tag.TAG_LIST);
        float[] scene = defaultScene(index);
        if (index < scenes.size()) {
            ListTag saved = scenes.getList(index);
            for (int i = 0; i < SCENE_SIZE && i < saved.size(); i++) {
                scene[i] = Mth.clamp(saved.getFloat(i), 0, 1);
            }
        }
        return scene;
    }

    static float[] defaultScene(int index) {
        float[] scene = new float[SCENE_SIZE];
        java.util.Arrays.fill(scene, SCENE_LEVELS[Mth.clamp(index, 0, SCENES - 1)]);
        scene[SCENE_SIZE - 1] = 1;
        return scene;
    }

    /** The console's brightnesses and master now, as a scene. */
    public static float[] current(LightingConsoleBlock.Entity console) {
        float[] scene = new float[SCENE_SIZE];
        for (int i = 0; i < LightingSettings.GROUPS; i++) {
            scene[i] = console.settings.groups[i].brightness;
        }
        scene[SCENE_SIZE - 1] = console.settings.master;
        return scene;
    }

    public static void saveScene(LightingConsoleBlock.Entity console, int index) {
        CompoundTag root = console.extras(KEY);
        ListTag scenes = root.getList("Scenes", Tag.TAG_LIST);
        while (scenes.size() < SCENES) {
            ListTag empty = new ListTag();
            for (float value : defaultScene(scenes.size())) {
                empty.add(FloatTag.valueOf(value));
            }
            scenes.add(empty);
        }
        ListTag scene = new ListTag();
        for (float value : current(console)) {
            scene.add(FloatTag.valueOf(value));
        }
        scenes.set(index, scene);
        root.put("Scenes", scenes);
        setScene(console, index);
    }

    /** The scene last recalled or saved, or -1. */
    public static int scene(LightingConsoleBlock.Entity console) {
        CompoundTag root = console.extras(KEY);
        return root.contains("Scene") ? root.getInt("Scene") : -1;
    }

    public static void setScene(LightingConsoleBlock.Entity console, int index) {
        console.extras(KEY).putInt("Scene", index);
    }
}
