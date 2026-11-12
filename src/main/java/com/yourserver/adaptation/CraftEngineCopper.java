package com.yourserver.adaptation;

import org.bukkit.block.Block;

/**
 * Медный нотный блок — кастомный блок CraftEngine, теперь в четырёх ступенях
 * окисления.
 *
 * <h2>Ступени</h2>
 *
 * <p>Ступень — это отдельный блок CraftEngine, как у ванильной меди отдельный
 * материал: {@code 1s} — обычная медь, {@code 2s} — потемневшая,
 * {@code 3s} — состаренная, {@code 4s} — окисленная. Модели в паке называются
 * так же: {@code f8resurs:block/1s_copper_note_block … 4s_copper_note_block}.
 *
 * <p>До 10.20 блок вообще не был ступенчатым, поэтому старый id
 * {@value #LEGACY_ID} считается первой ступенью: уже поставленные блоки
 * продолжают работать и при первом же старении переезжают на {@code 2s}.
 *
 * <h2>Музыка от блока не зависит</h2>
 *
 * <p>Ноты играет сам плагин ({@code world.playSound(BLOCK_NOTE_BLOCK_*)}),
 * инструмент выбирается по предмету в слоте, а не по блоку. Ступень влияет
 * только на высоту нот — см. {@link CopperNoteStages}.
 *
 * <p>Устройство блоков и защита от {@code NoClassDefFoundError} — в
 * {@link CraftEngineSupport}.
 */
final class CraftEngineCopper {

    /** Ступени окисления по порядку: 1s (медь) → 4s (окисленный). */
    static final String[] STAGE_IDS = {
            "f8resurs:1s_copper_note_block",
            "f8resurs:2s_copper_note_block",
            "f8resurs:3s_copper_note_block",
            "f8resurs:4s_copper_note_block",
    };

    /** Как блок назывался до ступеней. Считается первой ступенью. */
    static final String LEGACY_ID = "f8resurs:copper_note_block";

    /** Первая ступень — то, что плагин ставит по умолчанию. */
    static final String ID = STAGE_IDS[0];

    static final int STAGES = STAGE_IDS.length;

    private CraftEngineCopper() { }

    /** Стоит ли CraftEngine и включён ли. */
    static boolean available() {
        return CraftEngineSupport.available();
    }

    /** Первая ступень зарегистрирована — блок можно ставить. */
    static boolean ready() {
        return CraftEngineSupport.registered(ID);
    }

    /** Все четыре ступени на месте: старение будет видно без предупреждений. */
    static boolean readyAll() {
        return CraftEngineSupport.registeredAll(STAGE_IDS);
    }

    /** id ступени по её номеру (1…4). */
    static String idFor(int stage) {
        return STAGE_IDS[CopperNoteStages.clamp(stage) - 1];
    }

    /** Короткий диагноз для лога. Пусто — всё в порядке. */
    static String diagnosis() {
        return CraftEngineSupport.diagnosis(ID);
    }

    /** Чего не хватает: пусто, когда зарегистрированы все четыре ступени. */
    static String stageDiagnosis() {
        if (!available()) return "";
        StringBuilder missing = new StringBuilder();
        for (String id : STAGE_IDS) {
            if (CraftEngineSupport.registered(id)) continue;
            if (missing.length() > 0) missing.append(", ");
            missing.append(id);
        }
        if (missing.length() == 0) return "";
        return "CraftEngine не зарегистрировал ступени медного нотного блока: " + missing
                + ". Опишите их в паке CraftEngine (образец и список id: "
                + CraftEngineSupport.CONFIG_DOC + ") и выполните /ce reload all.";
    }

    /** id кастомного блока на этом месте или null, если блок ванильный. */
    static String idAt(Block block) {
        return CraftEngineSupport.idAt(block);
    }

    /** id — любая ступень нашего медного блока (или старый id до ступеней). */
    static boolean isCopperId(String id) {
        return stageOf(id) > 0;
    }

    /** Ступень по id блока: 1…4, либо 0, если блок не наш. */
    static int stageOf(String id) {
        if (id == null) return 0;
        if (LEGACY_ID.equals(id)) return 1;
        for (int i = 0; i < STAGE_IDS.length; i++) {
            if (STAGE_IDS[i].equals(id)) return i + 1;
        }
        return 0;
    }

    /** Наш ли это медный нотный блок. */
    static boolean isCopper(Block block) {
        return isCopperId(idAt(block));
    }

    /** Ступень блока в мире: 1…4, либо 0, если блок не наш. */
    static int stageAt(Block block) {
        return stageOf(idAt(block));
    }

    /** Поставить медный нотный блок (первая ступень). */
    static boolean place(Block block) {
        return place(block, 1);
    }

    /**
     * Поставить нужную ступень. Старый блок на месте снимается: две ступени в
     * одном месте оставить нельзя — это два разных блока CraftEngine.
     */
    static boolean place(Block block, int stage) {
        if (!available()) return false;
        String id = idFor(stage);
        if (CraftEngineSupport.registered(id) && CraftEngineSupport.place(block, id)) {
            return true;
        }
        // CraftEngine мог отказаться ставить поверх своего же блока — тогда
        // сначала снимаем старую ступень.
        CraftEngineSupport.remove(block);
        return CraftEngineSupport.place(block, id);
    }

    /** Убрать медный нотный блок из мира. */
    static boolean remove(Block block) {
        return CraftEngineSupport.remove(block);
    }
}
