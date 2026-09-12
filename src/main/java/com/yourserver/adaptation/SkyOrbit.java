package com.yourserver.adaptation;

import org.joml.Vector3f;

/** Геометрия движущейся дальней сферы, единая для картинки, лучей и прицеливания. */
final class SkyOrbit {
    private SkyOrbit() { }

    /** Положительная скорость — вправо при взгляде из центра сферы. */
    static double advance(double radians, double degreesPerSecond, long ticks) {
        return Math.IEEEremainder(radians - Math.toRadians(degreesPerSecond) * ticks / 20.0, Math.PI * 2);
    }

    static Vector3f local(Vector3f world, double radians, float depthScale) {
        return new Vector3f(world).rotateY((float) -radians).div(depthScale);
    }

    /**
     * Насколько отодвинуть сферу: чем дальше, тем правдоподобнее «небо» — звезду
     * перекрывают и деревья, и облака, потому что она дальше них. {@code fill}
     * задаёт долю видимой клиентом дальности, которую занимает сфера (0.5..1.0).
     */
    static float depthScale(double farthestGeometry, int clientChunks, int serverChunks, double multiplier, double fill) {
        int chunks = Math.max(2, Math.min(clientChunks > 0 ? clientChunks : serverChunks, serverChunks));
        double safeDistance = chunks * 16.0 * Math.clamp(fill, 0.5, 1.0);
        return (float) Math.clamp(safeDistance / Math.max(1, farthestGeometry), 1.0, Math.max(1.0, multiplier));
    }
}
