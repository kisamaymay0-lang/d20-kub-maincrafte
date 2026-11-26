package com.yourserver.adaptation;

/**
 * Пьяная походка: математика покачивания без единого класса Bukkit.
 *
 * <p>Здесь только два числа и один поворот, зато именно из них получается «идёт
 * дугами»: на каждом шаге (два тика) игрока тянет в сторону на {@link #sway}, а его
 * же курс подкручивается на небольшой угол — поэтому траектория плавно заворачивает,
 * а не ломается рывками.
 *
 * <p>Класс отдельный, чтобы это можно было проверить тестами: сервер для расчётов не
 * нужен, все значения — чистые числа.
 */
final class DrunkWalk {

    private DrunkWalk() {
    }

    /** С какой стадии начинается плавное покачивание (раньше — только рывки). */
    static boolean sways(DrunkStage stage) {
        return stage != null && stage.atLeast(DrunkStage.TALKATIVE);
    }

    /**
     * Насколько сильно тянет в сторону за один шаг покачивания.
     *
     * <p>Значения растут со стадией: на «Разговорило» это едва заметно, на «Краю» —
     * честные дуги. Ноль означает «не качает».
     */
    static double sway(DrunkStage stage) {
        if (stage == null) return 0.0;
        return switch (stage) {
            case TALKATIVE -> 0.012;
            case SWAYING -> 0.020;
            case QUEASY -> 0.028;
            case WASTED -> 0.036;
            case EDGE, BLACKOUT -> 0.042;
            default -> 0.0;
        };
    }

    /** С какой стадии начинается «уводит в случайную сторону». */
    static boolean wanders(DrunkStage stage) {
        return stage != null && stage.atLeast(DrunkStage.SWAYING);
    }

    /**
     * Сила постоянного увода в случайную сторону: игрок идёт туда, куда сам не собирался,
     * и держит это направление несколько секунд. Растёт со стадией.
     */
    static double wander(DrunkStage stage) {
        if (stage == null) return 0.0;
        return switch (stage) {
            case SWAYING -> 0.012;
            case QUEASY -> 0.020;
            case WASTED -> 0.030;
            case EDGE -> 0.040;
            case BLACKOUT -> 0.050;
            default -> 0.0;
        };
    }

    /** Стоящего на высоких стадиях тоже ведёт: там покачивание усиливаем. */
    static double standingFactor(DrunkStage stage) {
        return stage != null && stage.atLeast(DrunkStage.WASTED) ? 1.7 : 0.0;
    }

    /** Угол подворота курса за шаг: чем выше стадия, тем круче дуга. */
    static double curve(DrunkStage stage) {
        if (stage == null) return 0.0;
        return switch (stage) {
            case TALKATIVE -> 0.04;
            case SWAYING -> 0.07;
            case QUEASY -> 0.09;
            case WASTED -> 0.11;
            case EDGE, BLACKOUT -> 0.13;
            default -> 0.0;
        };
    }

    /**
     * Повернуть горизонтальное движение вокруг вертикали.
     *
     * @param x     скорость по X
     * @param z     скорость по Z
     * @param angle угол в радианах (положительный — вправо)
     * @return новые {@code {x, z}} той же длины
     */
    static double[] rotate(double x, double z, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new double[]{x * cos - z * sin, x * sin + z * cos};
    }
}
