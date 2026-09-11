package com.yourserver.adaptation;

import org.joml.Vector3d;

/** Прямая привязка сферы к глазам без зоны покоя, прогноза скорости и инертного догоняния. */
final class SkyFollow {
    private Vector3d start;
    private Vector3d target;
    private int startedAt;
    private int duration;

    SkyFollow(Vector3d reference, int tick) { reset(reference, tick); }
    void reset(Vector3d reference, int tick) {
        start = new Vector3d(reference); target = new Vector3d(reference); startedAt = tick; duration = 0;
    }
    Vector3d sample(int tick) {
        long elapsed = Integer.toUnsignedLong(tick - startedAt);
        double fraction = duration == 0 ? 1 : Math.min(1, (double) elapsed / duration);
        return new Vector3d(start).lerp(target, fraction);
    }
    Vector3d target() { return new Vector3d(target); }
    boolean isJump(Vector3d reference) { return target.distanceSquared(reference) > 16 * 16; }
    boolean follow(Vector3d reference, int tick) {
        if (target.distanceSquared(reference) < 1e-10) return false;
        start = sample(tick); target = new Vector3d(reference); startedAt = tick; duration = 1;
        return true;
    }
}
