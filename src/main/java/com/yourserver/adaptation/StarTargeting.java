package com.yourserver.adaptation;

import org.joml.Vector3f;

import java.util.Map;
import java.util.function.Function;

/** Единый поиск звезды для клика и превью: принадлежность/рёбра не фильтруют наведение. */
final class StarTargeting {

    private StarTargeting() {
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
