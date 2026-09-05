package com.yourserver.adaptation;

import org.joml.Vector3d;

/** Модель клиентской интерполяции общей точки неба. Только XYZ, никаких yaw/pitch. */
final class SkyFollow {
    private Vector3d start;
    private Vector3d target;
    private Vector3d lastEye;
    private int startedAt;
    private int observedAt;
    private int duration;

    SkyFollow(Vector3d eye, int tick) { reset(eye, tick); }

    void reset(Vector3d eye, int tick) {
        start = new Vector3d(eye);
        target = new Vector3d(eye);
        lastEye = new Vector3d(eye);
        startedAt = observedAt = tick;
        duration = 0;
    }

    Vector3d sample(int tick) {
        long elapsed = Integer.toUnsignedLong(tick - startedAt);
        double fraction = duration <= 0 ? 1.0 : Math.min(1.0, (double) elapsed / duration);
        return new Vector3d(start).lerp(target, fraction);
    }

    Vector3d target() { return new Vector3d(target); }

    boolean isJump(Vector3d eye) { return lastEye.distanceSquared(eye) > 16.0 * 16.0; }

    boolean follow(Vector3d eye, int tick, int interpolationTicks, double maxLead) {
        Vector3d velocity = new Vector3d(eye).sub(lastEye);
        long elapsed = Integer.toUnsignedLong(tick - observedAt);
        lastEye.set(eye);
        observedAt = tick;
        // Не экстраполируем устаревшее движение после паузы/телепортации.
        if (elapsed == 0 || elapsed > 20) {
            velocity.zero();
        } else {
            velocity.mul((double) interpolationTicks / elapsed);
        }
        double length = velocity.length();
        if (length > maxLead && length > 0) {
            velocity.mul(maxLead / length);
        }
        Vector3d next = new Vector3d(eye).add(velocity);
        if (next.distanceSquared(target) < 1e-10) {
            return false;
        }
        start = sample(tick);
        target = next;
        startedAt = tick;
        duration = interpolationTicks;
        return true;
    }
}
