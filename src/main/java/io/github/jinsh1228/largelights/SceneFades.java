package io.github.jinsh1228.largelights;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingSettings;

import net.minecraft.util.Mth;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Scenes fading in on the server: each group's brightness and the master move from where they were to the scene's
 * over the console's fade time, eased in and out, as a hall's lights come up. Moving a fader by hand mid-fade
 * stops it there.
 */
public final class SceneFades {
    private static final Map<LightingConsoleBlock.Entity, Fade> FADES = new WeakHashMap<>();

    private SceneFades() {
    }

    private static final class Fade {
        final float[] from;
        final float[] to;
        final long start;
        final long ticks;
        float[] last;

        Fade(float[] from, float[] to, long start, long ticks) {
            this.from = from;
            this.to = to;
            this.start = start;
            this.ticks = ticks;
            this.last = from;
        }
    }

    /** Fades {@code console} to {@code scene} over its fade time (at once, for none). */
    public static void start(LightingConsoleBlock.Entity console, float[] scene) {
        long ticks = Math.round(ConsoleSettings.fade(console) * 20);
        float[] from = ConsoleSettings.current(console);
        if (ticks <= 0) {
            set(console, scene);
            FADES.remove(console);
        } else {
            FADES.put(console, new Fade(from, scene.clone(), console.getLevel().getGameTime(), ticks));
        }
        console.changed();
    }

    /** Whether {@code console} is fading now (server side). */
    public static boolean fading(LightingConsoleBlock.Entity console) {
        return FADES.containsKey(console);
    }

    static void tick(ServerTickEvent.Post event) {
        for (Iterator<Map.Entry<LightingConsoleBlock.Entity, Fade>> it = FADES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<LightingConsoleBlock.Entity, Fade> entry = it.next();
            LightingConsoleBlock.Entity console = entry.getKey();
            Fade fade = entry.getValue();
            if (console.isRemoved() || console.getLevel() == null || moved(console, fade.last)) {
                it.remove();
                continue;
            }
            long elapsed = console.getLevel().getGameTime() - fade.start;
            float t = Mth.clamp(elapsed / (float) fade.ticks, 0, 1);
            float eased = t * t * (3 - 2 * t);
            float[] now = new float[fade.to.length];
            for (int i = 0; i < now.length; i++) {
                now[i] = Mth.lerp(eased, fade.from[i], fade.to[i]);
            }
            set(console, now);
            fade.last = now;
            if (t >= 1) {
                it.remove();
                console.changed();
            } else if (elapsed % 2 == 0) {
                console.changed();
            }
        }
    }

    /** Whether someone moved a fader since the fade last set them. */
    private static boolean moved(LightingConsoleBlock.Entity console, float[] last) {
        float[] now = ConsoleSettings.current(console);
        for (int i = 0; i < now.length; i++) {
            if (Math.abs(now[i] - last[i]) > 1e-4f) {
                return true;
            }
        }
        return false;
    }

    private static void set(LightingConsoleBlock.Entity console, float[] scene) {
        for (int i = 0; i < LightingSettings.GROUPS; i++) {
            console.settings.groups[i].brightness = Mth.clamp(scene[i], 0, 1);
        }
        console.settings.master = Mth.clamp(scene[ConsoleSettings.SCENE_SIZE - 1], 0, 1);
    }
}
