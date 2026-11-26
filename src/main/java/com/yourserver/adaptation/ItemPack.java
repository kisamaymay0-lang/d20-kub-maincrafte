package com.yourserver.adaptation;

import java.util.List;

/**
 * Общее у пака префиксов и пака косметики: имя сундучка, срок кейса и то,
 * что из него выпадает.
 *
 * <p>Нужен, чтобы меню паков и команды администратора ({@code /profile case pack}
 * и {@code /profile case cpack}) работали одинаково: экран пака рисуется по
 * этому описанию, а какое именно меню открыто — префиксов или косметики —
 * решает {@code PackKind} в {@link ProfileManager}.
 */
interface ItemPack {

    /** id пака — имя файла пака без {@code .yml}. */
    String id();

    /** Название на сундучке. */
    String name();

    /** Срок кейса в часах с момента выдачи; {@code -1} — бессрочный. */
    int timeHours();

    /** Сколько предметов внутри. */
    int size();

    /** id предметов внутри — префиксов или косметики. */
    List<String> itemIds();
}
