package io.github.jinsh1228.largelights.dev;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;

import com.mojang.logging.LogUtils;

import io.github.jinsh1228.largelights.ConsoleSettings;
import io.github.jinsh1228.largelights.LightField;
import io.github.jinsh1228.largelights.LightFieldNetwork;
import io.github.jinsh1228.largelights.SceneFades;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Normal field demo only: exercise an actual console fade and its delivery while the fade is still running. */
public final class FieldFadeDemo {
    private static final int DURATION = 160;
    private static final int TIMEOUT = 300;
    private static final AtomicBoolean pending = new AtomicBoolean();
    private static volatile Snapshot status;
    private static Run run; // Server thread only.
    private static BlockPos sample;
    private static int ticks, failures;
    private static boolean completed;

    private record Snapshot(int elapsed, int baseline, int levels, boolean done, String error,
                            LightFieldNetwork.Metrics metrics) {
    }

    private static final class Run {
        final LightingConsoleBlock.Entity console;
        final ServerLevel level;
        final long start;
        final int baseline;
        int levels;

        Run(LightingConsoleBlock.Entity console, ServerLevel level, int baseline) {
            this.console = console;
            this.level = level;
            this.start = level.getGameTime();
            this.baseline = baseline;
        }

        void publish(int elapsed, boolean done, String error) {
            status = new Snapshot(elapsed, baseline, levels, done, error, LightFieldNetwork.metrics(level));
        }
    }

    private FieldFadeDemo() {
    }

    public static void start(Minecraft mc, BlockPos consolePos, BlockPos samplePos) {
        sample = samplePos.immutable();
        ticks = failures = 0;
        completed = false;
        status = null;
        pending.set(false);
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var console = LightingConsoleBlock.find(level, consolePos);
            if (console == null || !console.formed) {
                status = new Snapshot(0, 0, 0, true, "formed console missing", LightFieldNetwork.metrics(level));
                return;
            }
            console = console.controller();
            int baseline = LightField.sample(level, samplePos);
            run = new Run(console, level, baseline);
            if (baseline < 6) {
                run.publish(0, true, "sample is too dim to verify three intermediate levels");
                return;
            }
            float[] target = ConsoleSettings.current(console);
            target[0] = .1f;
            target[ConsoleSettings.SCENE_SIZE - 1] = 1;
            ConsoleSettings.setFade(console, DURATION / 20f);
            SceneFades.start(console, target);
            run.publish(0, false, "");
            LogUtils.getLogger().info("SHAPE FIELD FADE started actual_console=true duration_ticks={} target_group_brightness=0.1 baseline={} sample={}",
                    DURATION, baseline, samplePos);
        });
    }

    /** Called once per client tick; true means that the caller can continue with its switch-off test. */
    public static boolean tick(Minecraft mc) {
        if (completed) return true;
        Snapshot snapshot = status;
        if (snapshot != null && snapshot.done) {
            finish(snapshot.error.isEmpty() && Integer.bitCount(snapshot.levels) >= 3,
                    snapshot.error, snapshot);
            return true;
        }
        if (++ticks >= TIMEOUT || mc.level == null || mc.getSingleplayerServer() == null) {
            finish(false, "fade delivery timed out after " + ticks + " client ticks", snapshot);
            return true;
        }
        if (snapshot != null && pending.compareAndSet(false, true)) {
            int observed = LightField.sample(mc.level, sample);
            mc.getSingleplayerServer().execute(() -> {
                try {
                    accept(observed);
                } finally {
                    pending.set(false);
                }
            });
        }
        return false;
    }

    private static void accept(int observed) {
        int elapsed = (int) (run.level.getGameTime() - run.start);
        boolean fading = SceneFades.fading(run.console);
        // Acknowledging on the server proves the client observation preceded the actual fade's completion.
        if (fading && elapsed > 0 && elapsed < DURATION
                && observed > Math.max(1, Math.round(run.baseline * .1f)) && observed < run.baseline && observed < 15) {
            int bit = 1 << observed;
            if ((run.levels & bit) == 0) {
                run.levels |= bit;
                LogUtils.getLogger().info("SHAPE FIELD FADE intermediate client_level={} server_elapsed_ticks={} fading=true",
                        observed, elapsed);
            }
        }
        run.publish(elapsed, !fading, !fading && elapsed < DURATION ? "console fade stopped early" : "");
    }

    private static void finish(boolean passed, String error, Snapshot snapshot) {
        completed = true;
        if (!passed) failures++;
        var levels = new ArrayList<Integer>();
        if (snapshot != null) {
            for (int level = 1; level < 15; level++) {
                if ((snapshot.levels & 1 << level) != 0) levels.add(level);
            }
        }
        LogUtils.getLogger().info("SHAPE CHECK {}: client sees >=3 distinct intermediate levels before actual 8-second console fade ends; levels={}, client_ticks={}, server_elapsed_ticks={}, baseline={}, error={}, metrics={}",
                passed ? "PASS" : "FAIL", levels, ticks, snapshot == null ? -1 : snapshot.elapsed,
                snapshot == null ? -1 : snapshot.baseline, error, snapshot == null ? null : snapshot.metrics);
    }

    public static int failures() {
        return failures;
    }
}
