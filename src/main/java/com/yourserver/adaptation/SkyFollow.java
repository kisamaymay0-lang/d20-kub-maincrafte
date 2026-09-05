package com.yourserver.adaptation;

import org.joml.Vector3d;

/** Инертная точка небесной сферы: не копирует камеру, приседание и короткие прыжки. */
final class SkyFollow {
    private Vector3d start;
    private Vector3d target;
    private Vector3d lastReference;
    private double groundHeight;
    private int airborneTicks;
    private int startedAt;
    private int observedAt;
    private int duration;

    SkyFollow(Vector3d reference, int tick) { reset(reference, tick); }

    void reset(Vector3d reference, int tick) {
        start = new Vector3d(reference);
        target = new Vector3d(reference);
        lastReference = new Vector3d(reference);
        groundHeight = reference.y;
        airborneTicks = 0;
        startedAt = observedAt = tick;
        duration = 0;
    }

    Vector3d sample(int tick) {
        long elapsed = Integer.toUnsignedLong(tick - startedAt);
        double fraction = duration <= 0 ? 1.0 : Math.min(1.0, (double) elapsed / duration);
        return new Vector3d(start).lerp(target, fraction);
    }

    Vector3d target() { return new Vector3d(target); }
    boolean isJump(Vector3d reference) { return lastReference.distanceSquared(reference) > 16.0 * 16.0; }

    boolean follow(Vector3d reference, int tick, int interpolationTicks, double maxDrift, boolean grounded) {
        int elapsed = (int) Math.min(20L, Integer.toUnsignedLong(tick - observedAt));
        lastReference.set(reference);
        observedAt = tick;
        if (elapsed == 0) return false;
        if (grounded) {
            airborneTicks = 0;
            groundHeight = reference.y;
        } else {
            airborneTicks = Math.min(40, airborneTicks + elapsed);
            // Обычный прыжок короче секунды: высота неба в это время остаётся прежней.
            // Полёт, долгое падение и подъём по лестнице всё же переносят сферу.
            if (airborneTicks >= 20) groundHeight = reference.y;
        }
        double horizontalDeadZone = Math.min(1.5, maxDrift * 0.4);
        double verticalDeadZone = Math.min(2.0, Math.max(0.5, maxDrift * 0.6));
        double alpha = -Math.expm1(-elapsed / 12.0);
        Vector3d next = new Vector3d(target);
        double dx = reference.x - target.x;
        double dz = reference.z - target.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal > horizontalDeadZone) {
            double movement = (horizontal - horizontalDeadZone) * alpha / horizontal;
            next.add(dx * movement, 0, dz * movement);
        }
        double dy = groundHeight - target.y;
        if (Math.abs(dy) > verticalDeadZone) next.y += Math.copySign((Math.abs(dy) - verticalDeadZone) * alpha, dy);
        // Ограниченный отрыв: игрок не сможет добежать/долететь до сферы.
        dx = next.x - reference.x;
        dz = next.z - reference.z;
        horizontal = Math.hypot(dx, dz);
        if (horizontal > maxDrift) {
            next.x = reference.x + dx * maxDrift / horizontal;
            next.z = reference.z + dz * maxDrift / horizontal;
        }
        double verticalLimit = Math.max(2.5, maxDrift);
        next.y = Math.clamp(next.y, reference.y - verticalLimit, reference.y + verticalLimit);
        if (next.distanceSquared(target) < 1e-6) return false;
        start = sample(tick);
        target = next;
        startedAt = tick;
        duration = interpolationTicks;
        return true;
    }
}
