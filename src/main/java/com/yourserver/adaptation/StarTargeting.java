package com.yourserver.adaptation;

import org.joml.Vector3f;

import java.util.Map;
import java.util.function.Function;

/** Единый поиск звезды для клика и превью: принадлежность/рёбра не фильтруют наведение. */
final class StarTargeting {

    private StarTargeting() {
    }

    /** Кэшированные смещения; одна позиция глаз на поиск, без аллокаций на каждую звезду. */
    static String closestOffsets(Map<String, Vector3f> offsets, Vector3f look, Vector3f eyeFromAnchor,
                                 double toleranceDegrees, String excludedKey) {
        double threshold = Math.cos(Math.toRadians(toleranceDegrees));
        double best = -1.0;
        String result = null;
        for (var entry : offsets.entrySet()) {
            if (entry.getKey().equals(excludedKey)) continue;
            Vector3f offset = entry.getValue();
            double x = offset.x - eyeFromAnchor.x;
            double y = offset.y - eyeFromAnchor.y;
            double z = offset.z - eyeFromAnchor.z;
            double lengthSquared = x * x + y * y + z * z;
            if (lengthSquared < 1e-10) continue;
            double dot = (look.x * x + look.y * y + look.z * z) / Math.sqrt(lengthSquared);
            if (dot >= threshold && dot > best) {
                best = dot;
                result = entry.getKey();
            }
        }
        return result;
    }

    static String closest(Iterable<Constellation> constellations, Vector3f look,
                          double toleranceDegrees, String excludedKey,
                          Function<Constellation.StarDef, Vector3f> direction) {
        double threshold = Math.cos(Math.toRadians(toleranceDegrees));
        double best = -1.0;
        String result = null;
        for (Constellation constellation : constellations) {
            if (!constellation.pinned) {
                continue;
            }
            for (Map.Entry<String, Constellation.StarDef> entry : constellation.stars.entrySet()) {
                String key = constellation.id + ":" + entry.getKey();
                if (key.equals(excludedKey)) {
                    continue;
                }
                double dot = look.dot(direction.apply(entry.getValue()));
                if (dot >= threshold && dot > best) {
                    best = dot;
                    result = key;
                }
            }
        }
        return result;
    }
}
