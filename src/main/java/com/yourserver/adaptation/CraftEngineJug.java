package com.yourserver.adaptation;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;

/**
 * Кувшин как настоящий кастомный блок CraftEngine.
 *
 * Все прежние носители проваливались по одной причине: ресурспак перерисовывает
 * блок целиком, а не конкретный экземпляр. Стекло превращало в кувшин каждое
 * окно сервера, рамка портала Края есть в каждой крепости. CraftEngine
 * регистрирует блок по-настоящему, со своим id, которого в ванили нет, поэтому
 * кувшин существует ровно там, где его поставили.
 *
 * CraftEngine — зависимость необязательная, а обращение к нему сидит в методе,
 * который вызывается каждый тик. Если класса нет на сервере, прямое обращение
 * бросало {@code NoClassDefFoundError} каждый тик на каждый кувшин, и сервер
 * вставал. Поэтому наличие класса определяется один раз при загрузке этого
 * класса, а каждый метод проверяет его первым делом: при {@code false} класс
 * CraftEngine вообще не загружается и исключения быть не может.
 */
final class CraftEngineJug {

    static final String EMPTY_ID = "f8resurs:ancient_jug";
    static final String FILLED_ID = "f8resurs:ancient_jug_filled";

    /** Есть ли класс CraftEngine на сервере. Считается один раз. */
    private static final boolean CLASS_PRESENT = probe();

    private CraftEngineJug() { }

    private static boolean probe() {
        try {
            Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Стоит ли CraftEngine. Без него кувшин поставить нельзя. */
    static boolean available() {
        return CLASS_PRESENT && Bukkit.getPluginManager().getPlugin("CraftEngine") != null;
    }

    /** id кувшина под содержимое. */
    static Key key(int count) {
        return Key.of(count > 0 ? FILLED_ID : EMPTY_ID);
    }

    /** id строкой — для лога, не тянет за собой класс CraftEngine. */
    static String keyName(int count) {
        return count > 0 ? FILLED_ID : EMPTY_ID;
    }

    /** Заблоки определён в конфиге CraftEngine. */
    static boolean registered(int count) {
        return available() && CraftEngineBlocks.byId(key(count)) != null;
    }

    /** Кастомный ли блок на этом месте (любой блок CraftEngine, не только кувшин). */
    static boolean isCustom(Block block) {
        return available() && block != null && CraftEngineBlocks.isCustomBlock(block);
    }

    /** Поставить кувшин; звук места не нужен — свой играет AncientJug. */
    static boolean place(Block block, int count) {
        return available() && CraftEngineBlocks.place(block.getLocation(), key(count), false);
    }

    /** Убрать кувшин из мира. Дроп не заказываем — его роняет AncientJug. */
    static boolean remove(Block block) {
        return available() && CraftEngineBlocks.remove(block);
    }
}
