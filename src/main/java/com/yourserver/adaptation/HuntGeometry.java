package com.yourserver.adaptation;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Геометрия удара по летающему предмету — считать со стороны клиента, а не сервера.
 *
 * При задержке сервер видит предмет и взгляд игрока устаревшими, поэтому обычная проверка
 * «смотрит ли игрок на предмет» расходится с картинкой на экране: игрок бьёт по предмету,
 * который видит, а сервер к этому моменту уже переставил предмет в другое место — и удар
 * превращается в промах. Здесь это лечится с двух сторон:
 *
 * <ul>
 *   <li>предмет отматывается назад по истории полёта ровно на задержку игрока — мы судим
 *       по той точке, которую он в этот момент видел (клиент рисует предмет с задержкой
 *       в пол-оборота связи плюс один тик сглаживания);</li>
 *   <li>радиус попадания считается не угловым конусом из глаза, а расстоянием от луча
 *       взгляда: у предмета в игре есть свой хитбокс, и промах на волосок ударом не считается.
 *       Чем ближе предмет, тем уже «трубка»; у самого прицела она не уже радиуса предмета.</li>
 * </ul>
 *
 * Класс чистый: только математика, поэтому и проверяется тестами без сервера.
 */
final class HuntGeometry {
    /** Насколько шире конуса может быть взмах, чтобы считаться попыткой, а не случайным кликом:
     *  в стороне от предмета взмахи не штрафуются. */
    static final double ATTEMPT_MULTIPLIER = 2.5;
    /** Дальше этого возраста предмет не отматываем: больше секунды задержки не бывает. */
    static final int MAX_REWIND_TICKS = 20;

    private HuntGeometry() { }

    /** Чем закончился взмах: попал, промахнулся рядом или бьёт вообще не туда. */
    enum Aim { HIT, NEAR, AWAY }

    /** Взгляд и цель в одной системе: forward — сколько до цели вдоль взгляда, offset — насколько
     *  цель ушла в сторону. offset бесконечен, если цель за спиной. */
    record Ray(double forward, double offset) { }

    /** На сколько тиков назад отматывать предмет: половина оборота связи (у Bukkit ping — это
     *  полный круг, поэтому делим на 100) плюс тик, который клиент дорисовывает перелёт. */
    static int rewindTicks(int pingMs, int interpolationTicks) {
        return Math.clamp((int) Math.ceil(Math.max(0, pingMs) / 100.0) + Math.max(0, interpolationTicks),
                0, MAX_REWIND_TICKS);
    }

    /** Точка из истории полёта: ticksBack отсчитывается от последней записи (0 — самая свежая).
     *  Между записями интерполируем — клиент тоже рисует плавно, а не рывками по тикам. */
    static Location sample(List<Location> history, double ticksBack) {
        if (history == null || history.isEmpty()) return null;
        int last = history.size() - 1;
        double position = Math.clamp(last - Math.max(0.0, ticksBack), 0.0, last);
        int lower = (int) Math.floor(position);
        int upper = Math.min(lower + 1, last);
        double part = position - lower;
        Location from = history.get(lower);
        if (part <= 0.0 || upper == lower) return from.clone();
        Location to = history.get(upper);
        return new Location(from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * part,
                from.getY() + (to.getY() - from.getY()) * part,
                from.getZ() + (to.getZ() - from.getZ()) * part);
    }

    /** Взгляд на цель: сколько до неё вдоль взгляда и насколько мимо. */
    static Ray ray(Location eye, Vector direction, Location target) {
        if (target == null || direction == null || direction.lengthSquared() < 1e-9) {
            return new Ray(0, Double.POSITIVE_INFINITY);
        }
        Vector look = direction.clone().normalize();
        Vector to = target.toVector().subtract(eye.toVector());
        double forward = to.dot(look);
        if (forward <= 0) return new Ray(forward, Double.POSITIVE_INFINITY);  // цель за спиной
        double offset = to.clone().subtract(look.clone().multiply(forward)).length();
        return new Ray(forward, offset);
    }

    /** Насколько мимо можно промахнуться: у самого прицела — радиус предмета, дальше трубка
     *  расширяется по обычному углу удара. */
    static double allowedOffset(double forward, double radius, double angleDegrees) {
        double spread = Math.max(0.0, forward) * Math.tan(Math.toRadians(Math.clamp(angleDegrees, 0.0, 89.0)));
        return Math.max(Math.max(0.0, radius), spread);
    }

    /** Судим взмах: далеко/за спиной — не попытка, рядом — промах, в трубке — удар. */
    static Aim aim(Ray ray, double radius, double angleDegrees, double range) {
        if (ray.forward() <= 0 || ray.forward() > range) return Aim.AWAY;
        double allowed = allowedOffset(ray.forward(), radius, angleDegrees);
        if (ray.offset() <= allowed) return Aim.HIT;
        return ray.offset() <= allowed * ATTEMPT_MULTIPLIER ? Aim.NEAR : Aim.AWAY;
    }
}
