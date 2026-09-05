package com.yourserver.adaptation;

import org.bukkit.Location;
import org.bukkit.util.Transformation;
import org.joml.Matrix3f;
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

    static Transformation starTransform(Vector3f direction, float distance, float scale) {
        return starTransform(direction, distance, scale, 0f);
    }

    static Transformation starTransform(Vector3f direction, float distance, float scale, float spinRadians) {
        Vector3f offset = new Vector3f(direction).mul(distance);
        Quaternionf faceObserver = new Quaternionf().rotationTo(
                new Vector3f(0f, 0f, 1f), new Vector3f(direction).negate()
        );
        // T * R_face * R_spin * S: вращается только картинка вокруг её
        // локальной оси Z. Центр и направление прицеливания не меняются.
        // Явный quaternion, а не декомпозиция матрицы через SVD: у равных
        // scale-осей SVD может менять пары вращений между кадрами и портить
        // клиентскую интерполяцию. Вращаем только leftRotation.
        return new Transformation(offset, faceObserver.rotateZ(spinRadians),
                new Vector3f(scale), new Quaternionf());
    }

    /**
     * Плоская полоса с постоянной шириной (локальные x/y от -0.5 до 0.5).
     * Проецируем оба конца на общую плоскость ЗА всей сферой звёзд. Простого
     * сдвига концов недостаточно: середина обычной хорды оказывается ближе
     * к наблюдателю и перекрывает звёзды при пересечении линий.
     * Возвращает null для вырожденного/антиподального направления.
     */
    static Matrix4f beamTransform(Vector3f a, Vector3f b, float thickness, float depthOffset) {
        if (!a.isFinite() || !b.isFinite() || a.lengthSquared() < 1e-8f || b.lengthSquared() < 1e-8f
                || !Float.isFinite(thickness) || thickness <= 0 || !Float.isFinite(depthOffset)) {
            return null;
        }
        Vector3f u = new Vector3f(a).normalize();
        Vector3f v = new Vector3f(b).normalize();
        if (u.distanceSquared(v) < 1e-8f) {
            return null;
        }
        Vector3f normal = new Vector3f(u).add(v);
        if (normal.lengthSquared() < 0.01f) {
            return null; // Почти противоположные точки не задают видимый прямой отрезок.
        }
        normal.normalize();
        float radius = Math.max(a.length(), b.length());
        float depth = radius + Math.max(0.5f, depthOffset);
        Vector3f behindA = u.mul(depth / u.dot(normal));
        Vector3f behindB = v.mul(depth / v.dot(normal));
        Vector3f along = new Vector3f(behindB).sub(behindA);
        float length = along.length();
        along.normalize();
        Vector3f front = new Vector3f(normal).negate();
        Vector3f across = new Vector3f(along).cross(front).normalize();
        Quaternionf rotation = new Quaternionf().setFromNormalized(new Matrix3f(across, along, front));

        return new Matrix4f()
                .translation(new Vector3f(behindA).add(behindB).mul(0.5f))
                .rotate(rotation)
                // Компенсируем перенос назад, сохраняя привычную толщину.
                .scale(thickness * depth / radius, length, 1f);
    }

    static Vector3f directionToStar(Vector3f eyeFromAnchor, Vector3f offset) {
        return new Vector3f(offset).sub(eyeFromAnchor).normalize();
    }

    static boolean isNight(long worldTime) {
        long time = Math.floorMod(worldTime, 24000L);
        return time >= 13000L && time < 23000L;
    }
}
