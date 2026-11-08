package io.github.jinsh1228.largelights;

import java.util.Map;
import java.util.WeakHashMap;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;

import net.minecraft.world.level.Level;

/**
 * The floodlight page's timers, on the server: when the day's time passes a timer's, its scene is recalled (with
 * the console's fade). Looked at by the console's floodlights as they tick (RealLight), once a tick at most for a
 * console, so a console's timers run while any floodlight of it is loaded. Time jumping ahead (sleep, /time add)
 * recalls the last timer passed; time set back recalls nothing.
 */
public final class ConsoleTimers {
    private static final Map<LightingConsoleBlock.Entity, State> STATES = new WeakHashMap<>();

    private ConsoleTimers() {
    }

    private static final class State {
        long dayTime;
        long checked;

        State(long dayTime, long checked) {
            this.dayTime = dayTime;
            this.checked = checked;
        }
    }

    static void check(LightingConsoleBlock.Entity console, Level level) {
        long now = level.getDayTime();
        long tick = level.getGameTime();
        State state = STATES.get(console);
        if (state == null) {
            STATES.put(console, new State(now, tick));
            return;
        }
        if (state.checked == tick) {
            return;
        }
        state.checked = tick;
        long last = state.dayTime;
        state.dayTime = now;
        ConsoleSettings.View view = ConsoleSettings.view(console);
        if (!view.timersOn()) {
            return;
        }
        int timer = passed(view.timers(), last, now);
        if (timer >= 0) {
            int scene = view.timers()[timer].scene();
            ConsoleSettings.setScene(console, scene);
            SceneFades.start(console, ConsoleSettings.scene(console, scene));
        }
    }

    /**
     * The timer on whose time is passed last going from day time {@code last} (exclusive) to {@code now}
     * (inclusive), or -1. Nothing passes going back, and a day's jump passes each at most once.
     */
    static int passed(ConsoleSettings.Timer[] timers, long last, long now) {
        if (now <= last) {
            return -1;
        }
        int best = -1;
        long bestAt = Long.MIN_VALUE;
        for (int i = 0; i < timers.length; i++) {
            ConsoleSettings.Timer timer = timers[i];
            if (!timer.on()) {
                continue;
            }
            // The timer's last time at or before now, and whether it is after last.
            long day = Math.floorDiv(now - timer.time(), ConsoleSettings.DAY);
            long at = day * ConsoleSettings.DAY + timer.time();
            if (at > last && at <= now && at > bestAt) {
                best = i;
                bestAt = at;
            }
        }
        return best;
    }
}
