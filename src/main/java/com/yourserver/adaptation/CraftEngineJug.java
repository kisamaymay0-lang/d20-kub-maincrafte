package com.yourserver.adaptation;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;

/**
 * Кувшин как настоящий кастомный блок CraftEngine.
 *
 * Все предыдущие носители проваливались по одной причине: ресурспак перерисовывает
 * блок целиком, а не конкретный экземпляр. Узорчатая ваза рисовалась ещё и
 * рендерером блочной сущности, нот-блок звенел и был сплошным кубом, котёл
 * пускал внутрь, стекло превращало в кувшин каждое окно сервера, а рамка
 * портала Края есть в каждой крепости — и везде вместо портала оказывались
 * кувшины.
 *
 * CraftEngine регистрирует блок по-настоящему, со своим id, которого в ванили
 * нет. Поэтому кувшин существует в мире ровно там, где его поставили, —
 * встретить его иначе нельзя ни в генерации, ни в крафте. Модель, звук,
 * прочность и дроп задаются конфигом CraftEngine, а не блок-стейтом пака.
 *
 * Содержимое кувшина по-прежнему живёт только в jugs.yml: блок — метка места.
 */
final class CraftEngineJug {

    /** Пустой кувшин. */
    static final Key EMPTY = Key.of("f8resurs", "ancient_jug");
    /** Налитый кувшин. */
    static final Key FILLED = Key.of("f8resurs", "ancient_jug_filled");

    private CraftEngineJug() { }

    /** Стоит ли CraftEngine. Без него кувшин поставить нельзя. */
    static boolean available() {
        return Bukkit.getPluginManager().getPlugin("CraftEngine") != null;
    }

    /** Кастомный ли блок на этом месте (любой блок CraftEngine, не только кувшин). */
    static boolean isCustom(Block block) {
        return block != null && CraftEngineBlocks.isCustomBlock(block);
    }

    /** id кувшина под содержимое. */
    static Key key(int count) {
        return count > 0 ? FILLED : EMPTY;
    }

    /** Заблоки определён в конфиге CraftEngine. */
    static boolean registered(int count) {
        return CraftEngineBlocks.byId(key(count)) != null;
    }

    /** Поставить кувшин; звук места не нужен — свой играет AncientJug. */
    static boolean place(Block block, int count) {
        return CraftEngineBlocks.place(block.getLocation(), key(count), false);
    }

    /** Убрать кувшин из мира. Дроп не заказываем — его роняет AncientJug. */
    static boolean remove(Block block) {
        return CraftEngineBlocks.remove(block);
    }
}
