package com.yourserver.adaptation;

import java.util.HashSet;
import java.util.Set;

/** Отделяет новый редстоун-сигнал от повторных событий ноты/удара рукой. */
final class PowerEdgeTracker {

    private final Set<String> powered = new HashSet<>();

    boolean update(String blockKey, boolean hasPower) {
        if (!hasPower) {
            powered.remove(blockKey);
            return false;
        }
        return powered.add(blockKey);
    }

    void remove(String blockKey) {
        powered.remove(blockKey);
    }

    void clear() {
        powered.clear();
    }
}
