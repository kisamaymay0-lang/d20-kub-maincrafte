package com.yourserver.adaptation;

import org.bukkit.block.Block;

/**
 * Древний кувшин — кастомный блок CraftEngine, два id: пустой и налитый.
 *
 * Все прежние носители проваливались по одной причине: ресурспак перерисовывает
 * блок целиком, а не конкретный экземпляр. Стекло превращало в кувшин каждое
 * окно сервера, рамка портала Края есть в каждой крепости. У CraftEngine блок
 * свой, ванильного id у него нет, и встретить его можно только там, где его
 * поставили.
 *
 * Устройство блоков и защита от {@code NoClassDefFoundError} — в
 * {@link CraftEngineSupport}; здесь только id кувшина и его виды.
 */
final class CraftEngineJug {

    static final String EMPTY_ID = "f8resurs:ancient_jug";
    static final String FILLED_ID = "f8resurs:ancient_jug_filled";

    private CraftEngineJug() { }

    /** Стоит ли CraftEngine и включён ли. Без него кувшин поставить нельзя. */
    static boolean available() {
        return CraftEngineSupport.available();
    }

    /** id кувшина под содержимое — строкой, для лога и сообщений. */
    static String keyName(int count) {
        return count > 0 ? FILLED_ID : EMPTY_ID;
    }

    /** Зарегистрирован ли нужный кувшин. */
    static boolean registered(int count) {
        return CraftEngineSupport.registered(keyName(count));
    }

    /**
     * Оба кувшина зарегистрированы и с ними можно работать.
     *
     * Проверять одно только «плагин включён» нельзя: CraftEngine грузит паки в
     * отложенной фазе включения, и в это окно {@code byId} отдаёт null при
     * полностью верном конфиге. Всё, что блок снимает, переставляет или считает
     * пропавшим, обязано идти только после этой проверки — иначе на старте
     * сервера кувшины терялись: блок снимали, поставить взамен было нечего, а
     * записи в jugs.yml считались осиротевшими и удалялись.
     */
    static boolean ready() {
        return CraftEngineSupport.registeredAll(EMPTY_ID, FILLED_ID);
    }

    /** Короткий диагноз для лога: почему кувшин не работает. Пусто — всё в порядке. */
    static String diagnosis() {
        return CraftEngineSupport.diagnosis(EMPTY_ID, FILLED_ID);
    }

    /** id кастомного блока на этом месте или null, если блок ванильный. */
    static String idAt(Block block) {
        return CraftEngineSupport.idAt(block);
    }

    /** id — один из двух наших кувшинов (пустой или налитый). */
    static boolean isJugId(String id) {
        return EMPTY_ID.equals(id) || FILLED_ID.equals(id);
    }

    /** Наш ли это кувшин — пустой или налитый. */
    static boolean isJug(Block block) {
        return isJugId(idAt(block));
    }

    /** Поставить кувшин под содержимое. */
    static boolean place(Block block, int count) {
        return CraftEngineSupport.place(block, keyName(count));
    }

    /** Убрать кувшин из мира. Дроп не заказываем — его роняет AncientJug. */
    static boolean remove(Block block) {
        return CraftEngineSupport.remove(block);
    }
}
