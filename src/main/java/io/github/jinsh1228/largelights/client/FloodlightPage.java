package io.github.jinsh1228.largelights.client;

import java.util.function.Consumer;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingConsolePage;
import org.haruyq.createmediasystems.lighting.LightingConsoleSurface;
import org.haruyq.createmediasystems.lighting.LightingPackets;
import org.haruyq.createmediasystems.lighting.LightingSettings;
import org.haruyq.createmediasystems.lighting.LightingText;
import org.jetbrains.annotations.Nullable;

import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.ConsolePacket;
import io.github.jinsh1228.largelights.ConsoleSettings;
import io.github.jinsh1228.largelights.FloodlightBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The lighting console's floodlight page: while its selected group holds a floodlight, the console's three right
 * bays become a hall's lighting desk. Scenes (match, practice, cleaning, off) recalled with a fade and saved from the
 * faders, with the master; colour temperatures and fine RGB; and the beam: aim, reach, width up to 80°, and how the
 * group lights the world (real light on or off, its brightest light level).
 */
public final class FloodlightPage implements LightingConsolePage {
    private static final int[] TEMPERATURES = {2700, 3000, 4000, 5000, 5700, 6500};
    /** Each temperature's white, as near as a monitor shows it. */
    private static final int[] TEMPERATURE_COLORS = {0xFFA957, 0xFFB46B, 0xFFD1A3, 0xFFE4CE, 0xFFEEE3, 0xFFF9FD};
    private static final int[] SCENE_COLORS = {0x5C8AFF, 0x32D999, 0xFFC75C, 0x4A5468};
    private static final int[] ACCENTS = {0xED525A, 0x56D76A, 0x4A89FF};
    private static final float KNOB_Z = 1.35f;
    private static final float ROW = 0.74f / 6;
    /** The console whose next scene key saves instead of recalls. */
    @Nullable
    private static LightingConsoleBlock.Entity saving;

    public static void register() {
        LightingConsolePage.register(new FloodlightPage());
    }

    @Override
    public boolean active(LightingConsoleBlock.Entity console) {
        return fixtures(console)[0] > 0;
    }

    /** The selected group's floodlights here, and the light blocks they keep. */
    private static int[] fixtures(LightingConsoleBlock.Entity console) {
        int count = 0;
        int lights = 0;
        var level = console.getLevel();
        if (level != null) {
            for (BlockPos pos : console.fixtures) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof FloodlightBlock.Entity light
                        && light.group == console.settings.selectedGroup) {
                    count++;
                    lights += light.lightBlocks().length;
                }
            }
        }
        return new int[]{count, lights};
    }

    private static MutableComponent text(String key, Object... args) {
        return Component.translatable("create_large_lights.console." + key, args);
    }

    private static String plain(String key, Object... args) {
        return text(key, args).getString();
    }

    private static int group(LightingConsoleBlock.Entity console) {
        return console.settings.selectedGroup;
    }

    private static LightingSettings.Group selected(LightingConsoleBlock.Entity console) {
        return console.settings.groups[group(console)];
    }

    private static int color(LightingConsoleBlock.Entity console) {
        LightingSettings.Group group = selected(console);
        return group.colors[Math.min(console.settings.selectedColor, group.colors.length - 1)];
    }

    // Where things are: deck areas (x, z) and monitor rows (x, y).

    private static Area sceneKey(int index) {
        float x = 1.06f + index % 2 * 0.34f;
        float z = 0.22f + index / 2 * 0.44f;
        return new Area(x, z, x + 0.28f, z + 0.36f);
    }

    private static final Area SAVE = new Area(1.06f, 1.14f, 1.34f, 1.48f);
    private static final Area FADE = new Area(1.43f, 1.24f, 1.65f, 1.46f);
    private static final Area MASTER = new Area(1.81f, 0.15f, 1.95f, 1.3f);

    private static Area temperatureKey(int index) {
        float x = 2.07f + index % 3 * 0.29f;
        float z = 0.22f + index / 3 * 0.44f;
        return new Area(x, z, x + 0.26f, z + 0.36f);
    }

    private static Area rgbKnob(int channel) {
        return new Area(2.1f + channel * 0.3f, 1.24f, 2.3f + channel * 0.3f, 1.46f);
    }

    private static final Area AIM = new Area(3.1f, 0.15f, 3.9f, 1.1f);

    private static Area beamKnob(int index) {
        return new Area(3.1f + index * 0.3f, 1.24f, 3.3f + index * 0.3f, 1.46f);
    }

    private static float rowTop(int row) {
        return 1.86f - row * ROW;
    }

    private static Area row(int bay, int row) {
        return new Area(bay + 0.065f, rowTop(row) - 0.117f, bay + 0.935f, rowTop(row));
    }

    /** The real light row, on the right monitor: pressed, it switches the group's real light. */
    private static final int REAL_LIGHT_ROW = 2;
    /** The group's motion and its target, as the console's own page sets them (it doesn't show for this group). */
    private static final int MOTION_ROW = 4;
    private static final int TARGET_ROW = 5;

    private static Component target(LightingConsoleBlock.Entity console) {
        var uuid = selected(console).target;
        var player = uuid == null || console.getLevel() == null ? null : console.getLevel().getPlayerByUUID(uuid);
        return player == null ? LightingText.text("—", LightingText.MUTED) : player.getName().copy().withColor(LightingText.MODE);
    }

    /** The aim pad's point for pan and tilt, centred on the neutral aim (Aim.NEUTRAL_TILT). */
    private static float aimX(float pan) {
        return 3.5f + Mth.clamp(pan / 180, -1, 1) * 0.4f;
    }

    private static float aimZ(float tilt) {
        return 0.625f + Mth.clamp((tilt - Aim.NEUTRAL_TILT) / 85, -1, 1) * 0.475f;
    }

    @Override
    public void render(LightingConsoleBlock.Entity console, LightingConsoleSurface surface) {
        LightingSettings settings = console.settings;
        LightingSettings.Group group = selected(console);
        int g = group(console);
        int scene = ConsoleSettings.scene(console);
        boolean saving = FloodlightPage.saving == console;

        // Scenes: the left of the three.
        surface.deckText(1.08f, 0.12f, plain(saving ? "save_where" : "scenes"), saving ? LightingText.WARNING : LightingText.MODE, 0.84f);
        for (int i = 0; i < ConsoleSettings.SCENES; i++) {
            Area key = sceneKey(i);
            surface.key(key.left(), key.bottom(), key.right(), key.top(), i == scene ? 0xFFE3A0 : SCENE_COLORS[i], false);
            surface.deckText(key.left() + 0.02f, key.top() - 0.08f, plain("scene." + i),
                    i == scene || i < 3 ? 0x101923 : 0xE6F1FF, 0.24f);
            if (saving) {
                surface.panelBorder(key.left(), key.bottom(), key.right(), key.top(), 0.01f, 0xFFC75C);
            }
        }
        surface.key(SAVE.left(), SAVE.bottom(), SAVE.right(), SAVE.top(), saving ? 0xFFC75C : 0x24374C, saving);
        surface.deckText(SAVE.left() + 0.02f, SAVE.top() - 0.1f, plain(saving ? "cancel" : "save"),
                saving ? 0x101923 : 0xE6F1FF, 0.24f);
        float fade = ConsoleSettings.fade(console);
        surface.knob(1.54f, KNOB_Z, 0xFFC75C, fade / ConsoleSettings.MAX_FADE);
        surface.deckText(1.45f, 1.2f, plain("fade"), 0xE6F1FF, 0.2f);
        surface.fader(1.88f, settings.master, 0xF0F4FF);
        surface.deckText(1.82f, 1.46f, plain("master"), 0xE6F1FF, 0.12f);
        surface.monitorText(1.08f, rowTop(0) - 0.015f, text("scene_now", scene < 0 ? LightingText.text("—", LightingText.MUTED)
                : text("scene." + scene).withColor(LightingText.MODE)), 0.84f);
        surface.monitorText(1.08f, rowTop(1) - 0.015f, text("fade_now",
                LightingText.number(String.format(java.util.Locale.ROOT, "%.1f", fade))), 0.84f);
        surface.monitorText(1.08f, rowTop(2) - 0.015f, text("master_now",
                LightingText.number(Math.round(settings.master * 100) + "%")), 0.84f);
        surface.monitorText(1.08f, rowTop(4) - 0.015f, text(saving ? "save_help" : "scene_help")
                .withColor(saving ? LightingText.WARNING : LightingText.MUTED), 0.84f);

        // Colour: the middle.
        int current = color(console);
        surface.deckText(2.08f, 0.12f, plain("color"), LightingText.readable(current), 0.84f);
        int nearest = nearestTemperature(current);
        for (int i = 0; i < TEMPERATURES.length; i++) {
            Area key = temperatureKey(i);
            surface.key(key.left(), key.bottom(), key.right(), key.top(), TEMPERATURE_COLORS[i], false);
            surface.deckText(key.left() + 0.02f, key.top() - 0.08f, TEMPERATURES[i] + "K", 0x101923, 0.22f);
            if (i == nearest && TEMPERATURE_COLORS[i] == current) {
                surface.panelBorder(key.left(), key.bottom(), key.right(), key.top(), 0.01f, 0xFFFFFF);
            }
        }
        for (int i = 0; i < 3; i++) {
            Area knob = rgbKnob(i);
            surface.knob((knob.left() + knob.right()) / 2, KNOB_Z, ACCENTS[i], (current >> ((2 - i) * 8) & 255) / 255f);
            surface.deckText(knob.left() + 0.07f, 1.2f, new String[]{"R", "G", "B"}[i], ACCENTS[i], 0.06f);
        }
        surface.monitor(2.08f, 2.3f, rowTop(0) - 0.1f, rowTop(0) - 0.02f, current);
        surface.monitorText(2.36f, rowTop(0) - 0.015f, LightingText.text(String.format("#%06X", current),
                LightingText.readable(current)), 0.55f);
        surface.monitorText(2.08f, rowTop(1) - 0.015f, text("temperature", TEMPERATURE_COLORS[nearest] == current
                ? LightingText.number(TEMPERATURES[nearest] + "K") : text("custom").withColor(LightingText.MODE)), 0.84f);
        surface.monitorText(2.08f, rowTop(2) - 0.015f, text("color_help").withColor(LightingText.MUTED), 0.84f);

        // The beam: the right.
        surface.deckText(3.08f, 0.12f, plain("beam"), LightingText.NUMBER, 0.84f);
        surface.panel(AIM.left(), AIM.bottom(), AIM.right(), AIM.top(), 0x102532);
        surface.panelBorder(AIM.left(), AIM.bottom(), AIM.right(), AIM.top(), 0.012f, 0x63DAEE);
        // The neutral aim: where each floodlight was aimed by hand.
        surface.panel(3.49f, 0.615f, 3.51f, 0.635f, 0x3A6B7A);
        float x = aimX(group.pan);
        float z = aimZ(group.tilt);
        surface.panel(x - 0.07f, z - 0.009f, x + 0.07f, z + 0.009f, 0x7DEBFF);
        surface.panel(x - 0.009f, z - 0.07f, x + 0.009f, z + 0.07f, 0x7DEBFF);
        float spread = ConsoleSettings.spread(console, g);
        int level = ConsoleSettings.maxLevel(console, g);
        boolean real = ConsoleSettings.realLight(console, g);
        float[] values = {(group.range - 2) / (LightingSettings.MAX_RANGE - 2), (spread - 1) / (ConsoleSettings.MAX_SPREAD - 1),
                (level - 1) / 14f};
        String[] knobs = {"range", "spread", "level"};
        for (int i = 0; i < 3; i++) {
            Area knob = beamKnob(i);
            surface.knob((knob.left() + knob.right()) / 2, KNOB_Z, i == 2 && !real ? 0x4A5468 : 0xB6C3D9, values[i]);
            surface.deckText(knob.left() - 0.01f, 1.2f, plain(knobs[i]), 0xE6F1FF, 0.22f);
        }
        int[] fixtures = fixtures(console);
        surface.monitorText(3.08f, rowTop(0) - 0.015f, text("spread_now",
                LightingText.number(Math.round(spread) + "°"), LightingText.number(Math.round(group.range) + " m")), 0.84f);
        surface.monitorText(3.08f, rowTop(1) - 0.015f, text("aim_now", LightingText.number(signed(group.pan) + "°"),
                LightingText.number(signed(group.tilt - Aim.NEUTRAL_TILT) + "°")), 0.84f);
        surface.monitor(3.065f, 3.935f, rowTop(REAL_LIGHT_ROW) - 0.117f, rowTop(REAL_LIGHT_ROW),
                real ? 0x1F4A33 : 0x3A1F25);
        surface.monitorText(3.08f, rowTop(REAL_LIGHT_ROW) - 0.015f, text("real_now", LightingText.on(real),
                LightingText.number(level)), 0.84f);
        surface.monitorText(3.08f, rowTop(3) - 0.015f, text("fixtures_now", LightingText.number(fixtures[0]),
                LightingText.number(fixtures[1])), 0.84f);
        surface.monitorText(3.08f, rowTop(MOTION_ROW) - 0.015f, text("motion_now",
                LightingText.mode("motion", group.motion)), 0.84f);
        surface.monitorText(3.08f, rowTop(TARGET_ROW) - 0.015f, text("target_now", target(console)), 0.84f);
    }

    private static String signed(float degrees) {
        int rounded = Math.round(degrees);
        return rounded > 0 ? "+" + rounded : Integer.toString(rounded);
    }

    static int nearestTemperature(int color) {
        int best = 0;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < TEMPERATURE_COLORS.length; i++) {
            int other = TEMPERATURE_COLORS[i];
            int r = (color >> 16 & 255) - (other >> 16 & 255);
            int g = (color >> 8 & 255) - (other >> 8 & 255);
            int b = (color & 255) - (other & 255);
            int d = r * r + g * g + b * b;
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    @Nullable
    @Override
    public LightingConsolePage.Control hit(LightingConsoleBlock.Entity console, Vec3 point, boolean monitor) {
        int g = group(console);
        if (monitor) {
            if (row(3, REAL_LIGHT_ROW).contains(point.x, point.y)) {
                boolean real = ConsoleSettings.realLight(console, g);
                return new Thing(row(3, REAL_LIGHT_ROW), true, text("real_light"),
                        () -> LightingText.on(ConsoleSettings.realLight(console, g)))
                        .onPress(shift -> send(console, ConsolePacket.Action.REAL_LIGHT, 0, real ? 0 : 1));
            }
            if (row(3, MOTION_ROW).contains(point.x, point.y)) {
                return new Thing(row(3, MOTION_ROW), true, text("motion"),
                        () -> LightingText.mode("motion", selected(console).motion))
                        .onPress(shift -> LightingConsolePage.send(console, LightingPackets.Control.MOTION, g, 0,
                                Math.floorMod(selected(console).motion + (shift ? -1 : 1), 4), 0))
                        .onScroll((step, shift) -> LightingConsolePage.send(console, LightingPackets.Control.MOTION, g, 0,
                                Math.floorMod(selected(console).motion + step, 4), 0));
            }
            if (row(3, TARGET_ROW).contains(point.x, point.y)) {
                return new Thing(row(3, TARGET_ROW), true, text("target"), () -> target(console))
                        .onPress(shift -> LightingConsolePage.send(console, LightingPackets.Control.TARGET, g, 0,
                                shift ? -1 : 1, 0))
                        .onScroll((step, shift) -> LightingConsolePage.send(console, LightingPackets.Control.TARGET, g, 0,
                                step, 0));
            }
            return null;
        }
        double x = point.x;
        double z = point.z;
        for (int i = 0; i < ConsoleSettings.SCENES; i++) {
            int index = i;
            if (sceneKey(i).contains(x, z)) {
                return new Thing(sceneKey(i), false, text("scene." + i), () -> text(saving == console ? "save_into" : "recall",
                        LightingText.number(String.format(java.util.Locale.ROOT, "%.1f", ConsoleSettings.fade(console)))))
                        .onPress(shift -> {
                            boolean save = saving == console;
                            saving = null;
                            send(console, save ? ConsolePacket.Action.SAVE : ConsolePacket.Action.RECALL, index, 0);
                        });
            }
        }
        if (SAVE.contains(x, z)) {
            return new Thing(SAVE, false, text("save"), () -> text(saving == console ? "save_help" : "save_start"))
                    .onPress(shift -> saving = saving == console ? null : console);
        }
        if (FADE.contains(x, z)) {
            return new Thing(FADE, false, text("fade"), () -> LightingText.number(String.format(java.util.Locale.ROOT,
                    "%.1f s", ConsoleSettings.fade(console))))
                    .onScroll((step, shift) -> send(console, ConsolePacket.Action.FADE, 0,
                            ConsoleSettings.fade(console) + step * (shift ? 0.1f : 0.5f)));
        }
        if (MASTER.contains(x, z)) {
            return new Thing(MASTER, false, text("master"), () -> LightingText.number(Math.round(console.settings.master * 100) + "%"))
                    .onScroll((step, shift) -> LightingConsolePage.send(console, LightingPackets.Control.MASTER, g, 0,
                            console.settings.master + step * 0.05f, 0))
                    .onDrag(at -> LightingConsolePage.send(console, LightingPackets.Control.MASTER, g, 0,
                            Mth.clamp(LightingConsoleSurface.faderValue(at.z), 0, 1), 0));
        }
        for (int i = 0; i < TEMPERATURES.length; i++) {
            int index = i;
            if (temperatureKey(i).contains(x, z)) {
                return new Thing(temperatureKey(i), false, text("temperature_key"), () -> LightingText.number(TEMPERATURES[index] + "K"))
                        .onPress(shift -> {
                            // One colour, still: the whole group in this white.
                            LightingConsolePage.send(console, LightingPackets.Control.COLOR, g, 0, TEMPERATURE_COLORS[index], 0);
                            LightingConsolePage.send(console, LightingPackets.Control.COUNT, g, 0, 1, 0);
                            LightingConsolePage.send(console, LightingPackets.Control.CYCLE, g, 0, 0, 0);
                            LightingConsolePage.send(console, LightingPackets.Control.VIDEO, g, 0, 0, 0);
                        });
            }
        }
        for (int i = 0; i < 3; i++) {
            int channel = i;
            if (rgbKnob(i).contains(x, z)) {
                return new Thing(rgbKnob(i), false, Component.literal(new String[]{"R", "G", "B"}[i]),
                        () -> LightingText.number(color(console) >> ((2 - channel) * 8) & 255))
                        .onScroll((step, shift) -> LightingConsolePage.send(console, LightingPackets.Control.RGB, g,
                                channel + console.settings.selectedColor * 3,
                                (color(console) >> ((2 - channel) * 8) & 255) + step * (shift ? 1 : 8), 0));
            }
        }
        if (AIM.contains(x, z)) {
            LightingSettings.Group group = selected(console);
            return new Thing(AIM, false, text("aim"), () -> text("aim_value", LightingText.number(signed(group.pan) + "°"),
                    LightingText.number(signed(group.tilt - Aim.NEUTRAL_TILT) + "°")))
                    .onDrag(at -> LightingConsolePage.send(console, LightingPackets.Control.AIM, g, 0,
                            (float) Mth.clamp((at.x - 3.5) / 0.4 * 180, -180, 180),
                            (float) Mth.clamp(Aim.NEUTRAL_TILT + (at.z - 0.625) / 0.475 * 85, -85, 85)))
                    // Sneak-use puts it back where each floodlight was aimed.
                    .onPress(shift -> {
                        if (shift) {
                            LightingConsolePage.send(console, LightingPackets.Control.AIM, g, 0, 0, Aim.NEUTRAL_TILT);
                        }
                    });
        }
        if (beamKnob(0).contains(x, z)) {
            return new Thing(beamKnob(0), false, text("range"), () -> LightingText.number(Math.round(selected(console).range) + " m"))
                    .onScroll((step, shift) -> LightingConsolePage.send(console, LightingPackets.Control.RANGE, g, 0,
                            selected(console).range + step * (shift ? 10 : 1), 0));
        }
        if (beamKnob(1).contains(x, z)) {
            return new Thing(beamKnob(1), false, text("spread"),
                    () -> LightingText.number(Math.round(ConsoleSettings.spread(console, g)) + "°"))
                    .onScroll((step, shift) -> send(console, ConsolePacket.Action.SPREAD, 0,
                            ConsoleSettings.spread(console, g) + step * (shift ? 5 : 1)));
        }
        if (beamKnob(2).contains(x, z)) {
            return new Thing(beamKnob(2), false, text("level"), () -> LightingText.number(ConsoleSettings.maxLevel(console, g)))
                    .onScroll((step, shift) -> send(console, ConsolePacket.Action.MAX_LEVEL, 0,
                            ConsoleSettings.maxLevel(console, g) + step));
        }
        return null;
    }

    private static void send(LightingConsoleBlock.Entity console, ConsolePacket.Action action, int index, float value) {
        ConsolePacket packet = new ConsolePacket(console.getBlockPos(), group(console), action, index, value);
        PacketDistributor.sendToServer(packet);
    }

    /** One thing on the page, its handlers set as it is made. */
    private static final class Thing implements LightingConsolePage.Control {
        private final Area area;
        private final boolean monitor;
        private final Component label;
        private final java.util.function.Supplier<Component> value;
        private Consumer<Boolean> press = shift -> {
        };
        private Scroll scroll = (step, shift) -> {
        };
        private Consumer<Vec3> drag;

        Thing(Area area, boolean monitor, Component label, java.util.function.Supplier<Component> value) {
            this.area = area;
            this.monitor = monitor;
            this.label = label;
            this.value = value;
        }

        interface Scroll {
            void scroll(int step, boolean shift);
        }

        Thing onPress(Consumer<Boolean> press) {
            this.press = press;
            return this;
        }

        Thing onScroll(Scroll scroll) {
            this.scroll = scroll;
            return this;
        }

        Thing onDrag(Consumer<Vec3> drag) {
            this.drag = drag;
            return this;
        }

        @Override
        public Area area() {
            return area;
        }

        @Override
        public boolean monitor() {
            return monitor;
        }

        @Override
        public Component label() {
            return label;
        }

        @Override
        public Component value() {
            return value.get();
        }

        @Override
        public void press(boolean shift) {
            press.accept(shift);
        }

        @Override
        public void scroll(int step, boolean shift, boolean control) {
            scroll.scroll(step, shift);
        }

        @Override
        public boolean drags() {
            // Sneaking on the aim pad presses it (back to neutral) rather than dragging it.
            return drag != null && !(area == AIM && Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.isShiftKeyDown());
        }

        @Override
        public void drag(Vec3 point) {
            if (drag != null) {
                drag.accept(point);
            }
        }
    }
}
