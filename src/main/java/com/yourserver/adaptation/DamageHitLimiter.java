package com.yourserver.adaptation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Один лимитер для набора, продления и повышения адаптации.
 * Считаем игровые тики, как и таймер самого эффекта: при низком TPS
 * реальное время не должно давать больше продлений на один тик таймера.
 */
final class DamageHitLimiter {

    private final Map<UUID, Map<String, Integer>> lastHits = new HashMap<>();

    boolean tryAcquire(UUID player, String damageType, int tick, long intervalMs) {
        long millis = Math.max(1L, intervalMs);
        long intervalTicks = millis / 50L + (millis % 50L == 0 ? 0 : 1);
        Map<String, Integer> byType = lastHits.computeIfAbsent(player, id -> new HashMap<>());
        Integer last = byType.get(damageType);

        // Вычитание int с unsigned-преобразованием переживает переполнение
        // Bukkit.getCurrentTick(). Отклонённый удар НЕ сдвигает интервал.
        if (last != null && Integer.toUnsignedLong(tick - last) < intervalTicks) {
            return false;
        }

        byType.put(damageType, tick);
        return true;
    }

    void remove(UUID player) {
        lastHits.remove(player);
    }

    void clear() {
        lastHits.clear();
    }
}
