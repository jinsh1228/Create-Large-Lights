package io.github.cmsfloodlights;

/**
 * What sets a floodlight apart from a stadium light: how wide its beam may be set (degrees across), how far it
 * reaches, and how far across the surface it falls on its light is laid (LightingIllumination.render's maxRadius).
 */
public enum FloodlightKind {
    FLOODLIGHT(new float[]{20, 35, 50, 70}, 1, 128, 24, 9),
    STADIUM_LIGHT(new float[]{25, 40, 60, 80}, 2, 256, 48, 12);

    final float[] spreads;
    final int defaultSpread;
    public final float range;
    public final int radius;
    /** How strongly the beam shows in the air, out of 255 at full strength. */
    public final float haze;

    FloodlightKind(float[] spreads, int defaultSpread, float range, int radius, float haze) {
        this.spreads = spreads;
        this.defaultSpread = defaultSpread;
        this.range = range;
        this.radius = radius;
        this.haze = haze;
    }

    public int spreads() {
        return spreads.length;
    }

    /** The beam's width across, in degrees, at setting {@code index} (kept within the settings there are). */
    public float spread(int index) {
        return spreads[Math.floorMod(index, spreads.length)];
    }
}
