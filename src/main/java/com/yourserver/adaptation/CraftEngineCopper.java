package com.yourserver.adaptation;

import org.bukkit.block.Block;

/**
 * Медный нотный блок — кастомный блок CraftEngine.
 *
 * До 10.20 он был обычным NOTE_BLOCK с зарезервированной нотой 24: по ней и по
 * инструменту ресурспак понимал, что рисовать. Цена такого носителя та же, что
 * была у кувшина: занят ванильный блок, а признаком служит состояние, которое
 * можно сбить. Теперь блок свой, со своим id, и «наш или нет» спрашивается у
 * CraftEngine, а не у ноты.
 *
 * Музыка от этого не зависит: ноты играет сам плагин
 * ({@code world.playSound(BLOCK_NOTE_BLOCK_*)}), инструмент выбирается по
 * предмету в слоте, а не по блоку.
 *
 * Устройство блоков и защита от {@code NoClassDefFoundError} — в
 * {@link CraftEngineSupport}.
 */
final class CraftEngineCopper {

    static final String ID = "f8resurs:copper_note_block";

    private CraftEngineCopper() { }

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

    /** id — наш медный блок. */
    static boolean isCopperId(String id) {
        return ID.equals(id);
    }

    /** Наш ли это медный нотный блок. */
    static boolean isCopper(Block block) {
        return isCopperId(idAt(block));
    }

    /** Поставить медный нотный блок. */
    static boolean place(Block block) {
        return CraftEngineSupport.place(block, ID);
    }

    /** Убрать медный нотный блок из мира. */
    static boolean remove(Block block) {
        return CraftEngineSupport.remove(block);
    }
}
