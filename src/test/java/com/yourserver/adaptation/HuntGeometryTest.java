package com.yourserver.adaptation;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Удар по летающему предмету считается со стороны клиента: предмет берётся из истории полёта
 * на момент задержки игрока, а попадание — это близость к лучу взгляда, а не угол из глаза.
 *
 * Здесь проверяется ровно математика: отмотка, интерполяция истории, трубка попадания
 * и три исхода взмаха — удар, промах рядом и взмах в стороне (за него не наказываем).
 */
class HuntGeometryTest {

    private static final double DELTA = 1e-9;
    /** Око игрока смотрит строго по +X: так удобно считать «сколько мимо» в блоках. */
    private static final Vector LOOK = new Vector(1, 0, 0);

    private static Location eye() { return new Location(null, 0, 64, 0); }

    private static Location target(double x, double y, double z) { return new Location(null, x, y, z); }

    private static HuntGeometry.Ray ray(Location to) {
        return HuntGeometry.ray(eye(), LOOK, to);
    }

    @Test
    void rewindCountsHalfTheConnectionAndTheClientSmoothing() {
        // У Bukkit ping — это полный круг (туда и обратно), поэтому в тиках идёт половина: ping/100.
        assertEquals(1, HuntGeometry.rewindTicks(0, 1));       // без задержки — только сглаживание клиента
        assertEquals(2, HuntGeometry.rewindTicks(50, 1));
        assertEquals(3, HuntGeometry.rewindTicks(120, 1));
        assertEquals(3, HuntGeometry.rewindTicks(200, 1));
        assertEquals(6, HuntGeometry.rewindTicks(500, 1));
        assertEquals(0, HuntGeometry.rewindTicks(0, 0));       // сглаживание можно и не учитывать
        assertEquals(11, HuntGeometry.rewindTicks(1000, 1));
        assertEquals(HuntGeometry.MAX_REWIND_TICKS, HuntGeometry.rewindTicks(4000, 1));   // больше секунды не отматываем
        assertTrue(HuntGeometry.rewindTicks(50, 0) < HuntGeometry.rewindTicks(50, 1));
    }

    @Test
    void historyIsSampledSmoothlyAndClampedAtTheEnd() {
        List<Location> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) history.add(target(i, 64, 0));

        assertEquals(3.0, HuntGeometry.sample(history, 0).getX(), DELTA);       // 0 — самая свежая точка
        assertEquals(2.5, HuntGeometry.sample(history, 0.5).getX(), DELTA);     // между тиками интерполируем
        assertEquals(1.5, HuntGeometry.sample(history, 1.5).getX(), DELTA);
        assertEquals(0.0, HuntGeometry.sample(history, 10).getX(), DELTA);      // раньше истории не уходим
        assertNull(HuntGeometry.sample(List.of(), 1));
        assertNull(HuntGeometry.sample(null, 1));
    }

    @Test
    void rewindShowsThePointThePlayerActuallySaw() {
        // Предмет рванул в сторону после удара и летит по 0.8 блока за тик. Игрок с задержкой
        // 150 мс видит точку на 3 тика назад — на 2.4 блока позади серверной.
        List<Location> dash = new ArrayList<>();
        for (int i = 0; i <= 4; i++) dash.add(target(i * 0.8, 64, 0));
        int back = HuntGeometry.rewindTicks(150, 1);
        assertEquals(3, back);
        assertEquals(0.8, HuntGeometry.sample(dash, back).getX(), DELTA);
        assertEquals(3.2, HuntGeometry.sample(dash, 0).getX(), DELTA);
        assertNotEquals(HuntGeometry.sample(dash, back).getX(), HuntGeometry.sample(dash, 0).getX());
    }

    @Test
    void rayMeasuresTheOffsetFromTheLookDirection() {
        HuntGeometry.Ray straight = ray(target(3, 64.5, 0));
        assertEquals(3.0, straight.forward(), DELTA);
        assertEquals(0.5, straight.offset(), DELTA);

        HuntGeometry.Ray side = ray(target(3, 64, 0.7));
        assertEquals(3.0, side.forward(), DELTA);
        assertEquals(0.7, side.offset(), DELTA);

        // За спиной и на нулевом взгляде цели нет.
        assertEquals(Double.POSITIVE_INFINITY, ray(target(-1, 64, 0)).offset());
        assertTrue(ray(target(-1, 64, 0)).forward() < 0);
        assertTrue(HuntGeometry.ray(eye(), new Vector(), target(1, 64, 0)).offset() == Double.POSITIVE_INFINITY);
        assertTrue(HuntGeometry.ray(eye(), LOOK, null).offset() == Double.POSITIVE_INFINITY);
    }

    @Test
    void theTubeIsWideAtRangeAndNeverNarrowerThanTheItem() {
        // У самого прицела трубка не уже радиуса предмета: у летящего предмета в игре есть хитбокс,
        // поэтому «промах на волосок» ударом не считается (старый угловой конус давал бы 35°).
        assertEquals(0.8, HuntGeometry.allowedOffset(0, 0.8, 22.0), DELTA);
        assertEquals(0.8, HuntGeometry.allowedOffset(1, 0.8, 22.0), DELTA);
        assertEquals(1.616105, HuntGeometry.allowedOffset(4, 0.8, 22.0), 1e-6);
        // На дистанции зацепа трубка совпадает с прежним углом: 3 * tan(22°) — те же 22 градуса.
        assertEquals(1.212079, HuntGeometry.allowedOffset(3, 0.8, 22.0), 1e-6);
        assertEquals(0.8, HuntGeometry.allowedOffset(-5, 0.8, 22.0), DELTA);       // за спиной — только радиус
    }

    @Test
    void aimSplitsHitsNearMissesAndSwingsIntoNowhere() {
        double radius = 0.8, angle = 22.0, range = 4.0;

        assertEquals(HuntGeometry.Aim.HIT, HuntGeometry.aim(ray(target(3, 64.5, 0)), radius, angle, range));
        assertEquals(HuntGeometry.Aim.HIT, HuntGeometry.aim(ray(target(1, 64.5, 0)), radius, angle, range));
        assertEquals(HuntGeometry.Aim.HIT, HuntGeometry.aim(ray(target(4, 64, 0)), radius, angle, range));

        // Чуть мимо — это попытка и промах: звучит неприятно, но не «взмах в пустоту».
        assertEquals(HuntGeometry.Aim.NEAR, HuntGeometry.aim(ray(target(3, 66, 0)), radius, angle, range));
        assertEquals(HuntGeometry.Aim.NEAR, HuntGeometry.aim(ray(target(1, 65, 0)), radius, angle, range));

        // Намного мимо, за спиной и за пределом дальности — попыткой не считается.
        assertEquals(HuntGeometry.Aim.AWAY, HuntGeometry.aim(ray(target(3, 70, 0)), radius, angle, range));
        assertEquals(HuntGeometry.Aim.AWAY, HuntGeometry.aim(ray(target(-1, 64, 0)), radius, angle, range));
        assertEquals(HuntGeometry.Aim.AWAY, HuntGeometry.aim(ray(target(4.5, 64, 0)), radius, angle, range));
        assertEquals(HuntGeometry.Aim.AWAY, HuntGeometry.aim(ray(target(0, 64, 0)), radius, angle, range));
    }

    @Test
    void aSwingAtTheCrosshairHitsEvenWhenTheItemIsOffCentre() {
        // Предмет прошёл 0.7 блока от прицела на расстоянии одного блока: старый угловой конус
        // дал бы 35° и записал промах, а хитбокс предмета такой удар засчитывает.
        assertTrue(Math.toDegrees(Math.atan2(0.7, 1.0)) > 22.0, "старый конус записал бы такой удар промахом");
        assertEquals(HuntGeometry.Aim.HIT, HuntGeometry.aim(ray(target(1, 64.7, 0)), 0.8, 22.0, 4.0));
        // Но на той же дистанции в 1.2 блока мимо — уже промах.
        assertEquals(HuntGeometry.Aim.NEAR, HuntGeometry.aim(ray(target(1, 65.2, 0)), 0.8, 22.0, 4.0));
    }

    @Test
    void attemptMultiplierStaysGenerousSoLaggySwingsAreNotPunished() {
        assertEquals(2.5, HuntGeometry.ATTEMPT_MULTIPLIER, DELTA);
        double allowed = HuntGeometry.allowedOffset(3, 0.8, 22.0);
        // Промах считается только рядом с трубкой попадания: на 2.5 её ширины — и всё, дальше не попытка.
        assertEquals(HuntGeometry.Aim.NEAR, HuntGeometry.aim(ray(target(3, 64 + allowed * 2.4, 0)), 0.8, 22.0, 4.0));
        assertEquals(HuntGeometry.Aim.AWAY, HuntGeometry.aim(ray(target(3, 64 + allowed * 2.6, 0)), 0.8, 22.0, 4.0));
    }
}
