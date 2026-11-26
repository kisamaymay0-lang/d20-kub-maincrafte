package com.yourserver.adaptation;

import org.bukkit.block.Block;

/**
 * Блок сладких ягод — кастомный блок CraftEngine {@code f8resurs:sweetie_bery_block}.
 */
final class CraftEngineSweetBerry {

    static final String ID = "f8resurs:sweetie_bery_block";

    private CraftEngineSweetBerry() { }

    /** Стоит ли CraftEngine и включён ли. */
    static boolean available() {
        return CraftEngineSupport.available();
    }

    /** Блок зарегистрирован и с ним можно работать. */
    static boolean ready() {
        return CraftEngineSupport.registered(ID);
    }

    /** Короткий диагноз для лога. Пусто — всё в порядке. */
    static String diagnosis() {
        return CraftEngineSupport.diagnosis(ID);
    }

    /** id кастомного блока на этом месте или null, если блок ванильный. */
    static String idAt(Block block) {
        return CraftEngineSupport.idAt(block);
    }

    /** id — наш блок сладких ягод. */
    static boolean isSweetBerryId(String id) {
        return ID.equals(id);
    }

    /** Наш ли это блок сладких ягод. */
    static boolean isSweetBerryBlock(Block block) {
        return isSweetBerryId(idAt(block));
    }

    /** Поставить блок сладких ягод. */
    static boolean place(Block block) {
        return CraftEngineSupport.place(block, ID);
    }

    /** Убрать блок сладких ягод из мира. */
    static boolean remove(Block block) {
        return CraftEngineSupport.remove(block);
    }
}
