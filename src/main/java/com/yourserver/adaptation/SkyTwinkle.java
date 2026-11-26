package com.yourserver.adaptation;

/**
 * Медленное мерцание звёзд: у каждой свой период, своя фаза и свой размер.
 *
 * <p>Зачем именно так. Быстрое мигание — это гирлянда, оно читается как
 * «игрушка» и утомляет; небо выглядит живым, когда звёзды еле заметно дышат
 * секунд по пять-двенадцать, каждая в своём ритме. Поэтому здесь только
 * синус на периоде 5..14 с и разброс размера ±10 %, зашитый в ключ звезды:
 * одинаковый ключ всегда даёт одинаковый характер, так что перезаход на сервер
 * не перетасовывает небо.
 *
 * <p>Меняется только масштаб картинки. Центр звезды, направление прицеливания
 * и концы линий созвездия остаются на месте — поэтому мерцание не задевает
 * геометрию и не требует пересчёта линий.
 */
final class SkyTwinkle {

    /** Во сколько раз звезда может быть мельче обычной. */
    static final float MIN_SIZE = 0.88f;
    /** И крупнее — тоже. */
    static final float MAX_SIZE = 1.12f;

    private SkyTwinkle() {
    }

    /** Стабильный хеш ключа (FNV-1a): одинаков на любом запуске сервера. */
    static int hash(String key) {
        int hash = 0x811C9DC5;

        for (int i = 0; i < (key == null ? 0 : key.length()); i++) {
            hash ^= key.charAt(i);
            hash *= 0x01000193;
        }

        return hash;
    }

    /** Детерминированное число 0..1 по ключу и соли. */
    private static float unit(String key, int salt) {
        int mixed = hash((key == null ? "" : key) + "#" + salt);
        return ((mixed >>> 8) % 1000) / 1000f;
    }

    /** Свой размер звезды: чуть мельче или крупнее соседней. */
    static float sizeFactor(String key) {
        return MIN_SIZE + (MAX_SIZE - MIN_SIZE) * unit(key, 1);
    }

    /** Своя яркость: 13..15, чтобы небо не было одноцветной гирляндой. */
    static int brightness(String key) {
        return 13 + Math.round(2f * unit(key, 2));
    }

    /** Своя фаза мерцания, радианы. */
    static float phase(String key) {
        return (float) (unit(key, 3) * Math.PI * 2.0);
    }

    /** Свой период мерцания: 5..14 секунд. */
    static double periodSeconds(String key) {
        return 5.0 + 9.0 * unit(key, 4);
    }

    /** Глубина мерцания: 6..11 % — заметно, но не мигание. */
    static float amplitude(String key) {
        return 0.06f + 0.05f * unit(key, 5);
    }

    /**
     * Множитель размера в момент {@code seconds} секунд работы сервера.
     * Возвращает значение в диапазоне {@code 1 ± amplitude}.
     */
    static float pulse(float phase, double periodSeconds, float amplitude, double seconds) {
        double period = Math.max(0.5, periodSeconds);
        return 1f + amplitude * (float) Math.sin(phase + seconds * Math.PI * 2.0 / period);
    }
}
