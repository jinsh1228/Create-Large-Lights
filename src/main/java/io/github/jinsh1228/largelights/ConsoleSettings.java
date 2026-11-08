package io.github.jinsh1228.largelights;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

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
 * at most; the console's scenes, how long recalling one fades for, and the timers that recall them at times of day.
 *
 * <p>Floodlights read these every frame, so they are read from the tag once per change into a View: setters bump
 * the tag's revision, and a sync replaces the tag on the client.
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
    public static final int TIMERS = 4;
    /** Ticks in a day; a timer's time is the day's time (0 is 6:00). */
    public static final int DAY = 24000;
    /** Match, practice, cleaning, off: every group at 100%, 60%, 30%, 0%. */
    private static final float[] SCENE_LEVELS = {1, 0.6f, 0.3f, 0};
    private static final String[] GROUP_KEYS = new String[LightingSettings.GROUPS];
    /** Each console's settings as last read, by console: the client's and the server's are separate entities. */
    private static final Map<LightingConsoleBlock.Entity, View> VIEWS = Collections.synchronizedMap(new WeakHashMap<>());

    static {
        for (int i = 0; i < GROUP_KEYS.length; i++) {
            GROUP_KEYS[i] = "Group" + i;
        }
    }

    private ConsoleSettings() {
    }

    /** A timer: at {@code time} of day (0 to DAY), recall scene {@code scene}, if {@code on}. */
    public record Timer(boolean on, int time, int scene) {
        /** The time as a clock reads it, hours and minutes. */
        public int hour() {
            return (time / 1000 + 6) % 24;
        }

        public int minute() {
            return time % 1000 * 60 / 1000;
        }
    }

    /** One console's settings, read from its tag at one revision. */
    public record View(CompoundTag root, int revision, float[] spread, boolean[] realLight, int[] maxLevel, float fade,
                       float[][] scenes, int scene, boolean timersOn, Timer[] timers) {
        public int lightLimit(int group) {
            return realLight[group] ? maxLevel[group] : 0;
        }
    }

    /** {@code console}'s settings now. */
    public static View view(LightingConsoleBlock.Entity console) {
        CompoundTag root = console.extras(KEY);
        int revision = root.getInt("Rev");
        View view = VIEWS.get(console);
        if (view == null || view.root != root || view.revision != revision) {
            view = read(root, revision);
            VIEWS.put(console, view);
        }
        return view;
    }

    private static View read(CompoundTag root, int revision) {
        int groups = LightingSettings.GROUPS;
        float[] spread = new float[groups];
        boolean[] real = new boolean[groups];
        int[] level = new int[groups];
        for (int g = 0; g < groups; g++) {
            CompoundTag tag = root.getCompound(GROUP_KEYS[g]);
            spread[g] = tag.contains("Spread") ? Mth.clamp(tag.getFloat("Spread"), 1, MAX_SPREAD) : DEFAULT_SPREAD;
            real[g] = !tag.contains("RealLight") || tag.getBoolean("RealLight");
            level[g] = tag.contains("MaxLevel") ? Mth.clamp(tag.getInt("MaxLevel"), 1, 15) : 15;
        }
        float fade = root.contains("Fade") ? Mth.clamp(root.getFloat("Fade"), 0, MAX_FADE) : DEFAULT_FADE;
        ListTag saved = root.getList("Scenes", Tag.TAG_LIST);
        float[][] scenes = new float[SCENES][];
        for (int i = 0; i < SCENES; i++) {
            scenes[i] = defaultScene(i);
            if (i < saved.size()) {
                ListTag scene = saved.getList(i);
                for (int j = 0; j < SCENE_SIZE && j < scene.size(); j++) {
                    scenes[i][j] = Mth.clamp(scene.getFloat(j), 0, 1);
                }
            }
        }
        int scene = root.contains("Scene") ? root.getInt("Scene") : -1;
        ListTag timerTags = root.getList("Timers", Tag.TAG_COMPOUND);
        Timer[] timers = new Timer[TIMERS];
        for (int i = 0; i < TIMERS; i++) {
            timers[i] = i < timerTags.size() ? timer(timerTags.getCompound(i)) : defaultTimer(i);
        }
        return new View(root, revision, spread, real, level, fade, scenes, scene, root.getBoolean("TimersOn"), timers);
    }

    private static Timer timer(CompoundTag tag) {
        return new Timer(tag.getBoolean("On"), Math.floorMod(tag.getInt("Time"), DAY),
                Mth.clamp(tag.getInt("Scene"), 0, SCENES - 1));
    }

    /** Off at first: dusk (18:00) to the match scene, then practice, cleaning, and off at midnight. */
    static Timer defaultTimer(int index) {
        int[] times = {12000, 15000, 16000, 18000};
        return new Timer(false, times[Mth.clamp(index, 0, TIMERS - 1)], Mth.clamp(index, 0, SCENES - 1));
    }

    static float[] defaultScene(int index) {
        float[] scene = new float[SCENE_SIZE];
        Arrays.fill(scene, SCENE_LEVELS[Mth.clamp(index, 0, SCENES - 1)]);
        scene[SCENE_SIZE - 1] = 1;
        return scene;
    }

    // Reading, as the rest of the mod asks.

    public static float spread(LightingConsoleBlock.Entity console, int group) {
        return view(console).spread[group];
    }

    public static boolean realLight(LightingConsoleBlock.Entity console, int group) {
        return view(console).realLight[group];
    }

    /** The brightest a group's floodlights light the world, as a light level. */
    public static int maxLevel(LightingConsoleBlock.Entity console, int group) {
        return view(console).maxLevel[group];
    }

    /** How a group's floodlights may light the world: their light level at most, 0 for not at all. */
    public static int lightLimit(LightingConsoleBlock.Entity console, int group) {
        return view(console).lightLimit(group);
    }

    /** Seconds a recalled scene takes to fade in. */
    public static float fade(LightingConsoleBlock.Entity console) {
        return view(console).fade;
    }

    /** Scene {@code index}: each group's brightness, then the master (SCENE_SIZE values, 0 to 1). A copy. */
    public static float[] scene(LightingConsoleBlock.Entity console, int index) {
        return view(console).scenes[index].clone();
    }

    /** The scene last recalled or saved, or -1. */
    public static int scene(LightingConsoleBlock.Entity console) {
        return view(console).scene;
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

    // Changing, on the server: each bumps the revision, so views are read again.

    private static CompoundTag edit(LightingConsoleBlock.Entity console) {
        CompoundTag root = console.extras(KEY);
        root.putInt("Rev", root.getInt("Rev") + 1);
        return root;
    }

    private static CompoundTag group(LightingConsoleBlock.Entity console, int group) {
        CompoundTag root = edit(console);
        if (!root.contains(GROUP_KEYS[group], Tag.TAG_COMPOUND)) {
            root.put(GROUP_KEYS[group], new CompoundTag());
        }
        return root.getCompound(GROUP_KEYS[group]);
    }

    public static void setSpread(LightingConsoleBlock.Entity console, int group, float spread) {
        group(console, group).putFloat("Spread", Mth.clamp(spread, 1, MAX_SPREAD));
    }

    public static void setRealLight(LightingConsoleBlock.Entity console, int group, boolean on) {
        group(console, group).putBoolean("RealLight", on);
    }

    public static void setMaxLevel(LightingConsoleBlock.Entity console, int group, int level) {
        group(console, group).putInt("MaxLevel", Mth.clamp(level, 1, 15));
    }

    public static void setFade(LightingConsoleBlock.Entity console, float seconds) {
        edit(console).putFloat("Fade", Mth.clamp(seconds, 0, MAX_FADE));
    }

    public static void saveScene(LightingConsoleBlock.Entity console, int index) {
        float[][] scenes = view(console).scenes;
        CompoundTag root = edit(console);
        ListTag list = new ListTag();
        for (int i = 0; i < SCENES; i++) {
            ListTag scene = new ListTag();
            for (float value : i == index ? current(console) : scenes[i]) {
                scene.add(FloatTag.valueOf(value));
            }
            list.add(scene);
        }
        root.put("Scenes", list);
        root.putInt("Scene", index);
    }

    public static void setScene(LightingConsoleBlock.Entity console, int index) {
        edit(console).putInt("Scene", index);
    }

    public static void setTimersOn(LightingConsoleBlock.Entity console, boolean on) {
        edit(console).putBoolean("TimersOn", on);
    }

    /** Sets timer {@code index}; null leaves that part as it was. */
    public static void setTimer(LightingConsoleBlock.Entity console, int index, Boolean on, Integer time, Integer scene) {
        Timer[] timers = view(console).timers;
        Timer old = timers[index];
        timers = timers.clone();
        timers[index] = new Timer(on == null ? old.on() : on, time == null ? old.time() : Math.floorMod(time, DAY),
                scene == null ? old.scene() : Math.floorMod(scene, SCENES));
        ListTag list = new ListTag();
        for (Timer timer : timers) {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("On", timer.on());
            tag.putInt("Time", timer.time());
            tag.putInt("Scene", timer.scene());
            list.add(tag);
        }
        edit(console).put("Timers", list);
    }
}
