package com.yourserver.adaptation;

import org.bukkit.Location;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Общая геометрия отрисовки и прицеливания; не зависит от поворота камеры. */
final class SkyGeometry {

    private SkyGeometry() {
    }

    static Location anchor(Location eye) {
        // Location игрока содержит yaw/pitch. У display-сущностей эти углы
        // поворачивают ВСЁ локальное смещение, поэтому их нельзя копировать.
        return new Location(eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(), 0f, 0f);
    }

    static Matrix4f starTransform(Vector3f direction, float distance, float scale) {
        Vector3f offset = new Vector3f(direction).mul(distance);
        Quaternionf faceObserver = new Quaternionf().rotationTo(
                new Vector3f(0f, 0f, 1f), new Vector3f(direction).negate()
        );
        // T * R * S: поворачиваем только картинку вокруг её центра,
        // НЕ смещение от игрока. Использовать вместе с Billboard.FIXED.
        return new Matrix4f().translation(offset).rotate(faceObserver).scale(scale);
    }

    static Matrix4f lineTransform(Vector3f a, Vector3f b, float thickness) {
        Vector3f delta = new Vector3f(b).sub(a);
        float length = delta.length();
        if (length < 1e-4f) {
            throw new IllegalArgumentException("Звёзды линии совпадают");
        }
        Quaternionf rotation = new Quaternionf().rotationTo(
                new Vector3f(0f, 1f, 0f), delta.normalize()
        );
        return new Matrix4f()
                .translation(new Vector3f(a).add(b).mul(0.5f))
                .rotate(rotation)
                .scale(thickness, length, thickness)
                // BlockDisplay рисует блок от угла (0,0,0), а не от центра.
                .translate(-0.5f, -0.5f, -0.5f);
    }

    static Vector3f directionToStar(Vector3f eyeFromAnchor, Vector3f offset) {
        return new Vector3f(offset).sub(eyeFromAnchor).normalize();
    }

    static boolean isNight(long worldTime) {
        long time = Math.floorMod(worldTime, 24000L);
        return time >= 13000L && time < 23000L;
    }
}
