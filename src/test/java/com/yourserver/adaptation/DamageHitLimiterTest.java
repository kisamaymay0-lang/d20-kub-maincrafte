package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DamageHitLimiterTest {

    private final UUID player = UUID.randomUUID();
    private final DamageHitLimiter limiter = new DamageHitLimiter();

    @Test
    void firstHitIsImmediateButRepeatedEventsInSameTickAreRejected() {
        assertTrue(limiter.tryAcquire(player, "MELEE", 0, 2000));
        assertFalse(limiter.tryAcquire(player, "MELEE", 0, 2000));
    }

    @Test
    void lavaEveryTickCountsOnlyOncePerFortyTicks() {
        int counted = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (limiter.tryAcquire(player, "MELEE", tick, 2000)) {
                counted++;
                assertEquals(0, tick % 40);
            }
        }
        assertEquals(5, counted);
    }

    @Test
    void normalHitIntervalIsNineTicksAndIgnoredHitsDoNotSlideIt() {
        assertTrue(limiter.tryAcquire(player, "MELEE", 0, 450));
        for (int tick = 1; tick < 9; tick++) {
            assertFalse(limiter.tryAcquire(player, "MELEE", tick, 450));
        }
        assertTrue(limiter.tryAcquire(player, "MELEE", 9, 450));
    }

    @Test
    void intervalsRoundUpNotDown() {
        assertTrue(limiter.tryAcquire(player, "MELEE", 0, 451));
        assertFalse(limiter.tryAcquire(player, "MELEE", 9, 451));
        assertTrue(limiter.tryAcquire(player, "MELEE", 10, 451));
    }

    @Test
    void sameLimiterIsKeptAcrossAdaptationPhasesAndEnvironmentalCauses() {
        // Последний удар для обычной адаптации (лава).
        assertTrue(limiter.tryAcquire(player, "MELEE", 100, 2000));
        // Уже активная адаптация: огонь не должен обходить ту же группу MELEE.
        assertFalse(limiter.tryAcquire(player, "MELEE", 101, 2000));
        assertTrue(limiter.tryAcquire(player, "MELEE", 140, 2000));
        // Переход к повышенной адаптации не выдаёт ещё один бонус в том же тике.
        assertFalse(limiter.tryAcquire(player, "MELEE", 140, 2000));
    }

    @Test
    void playersAndDamageTypesAreIndependent() {
        assertTrue(limiter.tryAcquire(player, "MELEE", 0, 2000));
        assertTrue(limiter.tryAcquire(player, "RANGED", 0, 450));
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), "MELEE", 0, 2000));
        assertFalse(limiter.tryAcquire(player, "MELEE", 1, 2000));
    }

    @Test
    void tickCounterOverflowDoesNotBypassTheInterval() {
        int start = Integer.MAX_VALUE - 10;
        assertTrue(limiter.tryAcquire(player, "MELEE", start, 2000));
        assertFalse(limiter.tryAcquire(player, "MELEE", start + 39, 2000));
        assertTrue(limiter.tryAcquire(player, "MELEE", start + 40, 2000));
    }

    @Test
    void leavingClearsOnlyThatPlayersState() {
        UUID other = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player, "MELEE", 0, 2000));
        assertTrue(limiter.tryAcquire(other, "MELEE", 0, 2000));
        limiter.remove(player);
        assertTrue(limiter.tryAcquire(player, "MELEE", 1, 2000));
        assertFalse(limiter.tryAcquire(other, "MELEE", 1, 2000));
        limiter.clear();
        assertTrue(limiter.tryAcquire(other, "MELEE", 1, 2000));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void continuousLavaCannotKeepDefaultAdaptationAlive(boolean superMode) {
        double maxTime = superMode ? 40.0 : 100.0;
        double time = maxTime; // десятые доли секунды, как в обработчике
        double bonus = superMode ? 4.0 : 2.0;
        limiter.tryAcquire(player, "MELEE", 0, 2000); // активирующий удар
        for (int tick = 1; tick <= 400 && time > 0; tick++) {
            if (tick % 2 == 0) {
                time -= 1.0;
            }
            if (time > 0 && limiter.tryAcquire(player, "MELEE", tick, 2000)) {
                time = Math.min(maxTime, time + bonus);
            }
        }
        assertTrue(time <= 0, "Адаптация должна закончиться даже при уроне каждый тик");
    }
}
