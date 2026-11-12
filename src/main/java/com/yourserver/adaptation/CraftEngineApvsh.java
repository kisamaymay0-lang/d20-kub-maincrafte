package com.yourserver.adaptation;

import org.bukkit.block.Block;

/**
 * АПВШ (аппарат для создания и наполнения сигарет и другого) — кастомный блок CraftEngine.
 *
 * Текстура и модель блока — copper_note_block.json (f8resurs:block/copper_note_block).
 *
 * Устройство блоков и защита от NoClassDefFoundError — в {@link CraftEngineSupport}.
 */
final class CraftEngineApvsh {

    static final String ID = "f8resurs:apvsh";

    private CraftEngineApvsh() { }

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

    /** id — наш блок АПВШ. */
    static boolean isApvshId(String id) {
        return ID.equals(id);
    }

    /** Наш ли это блок АПВШ. */
    static boolean isApvsh(Block block) {
        return isApvshId(idAt(block));
    }

    /** Поставить блок АПВШ. */
    static boolean place(Block block) {
        return CraftEngineSupport.place(block, ID);
    }

    /** Убрать блок АПВШ из мира. */
    static boolean remove(Block block) {
        return CraftEngineSupport.remove(block);
    }
}
