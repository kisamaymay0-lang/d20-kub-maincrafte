package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Распознавание косметики — то самое место, из-за которого в 10.18 перестали
 * перекладываться и выбрасываться ВСЕ кастомные предметы плагина.
 *
 * Предмет косметики собирается из Bukkit-классов, поэтому сам ItemStack здесь
 * не создать, но решение «косметика ли это» вынесено в {@link Cosmetics}
 * статическими методами, которые вызываются из обработчиков, — проверяются
 * именно они.
 */
class CosmeticsGuardTest {

    /** Косметика, как её создаёт плагин: метка есть, модель из f8resurs. */
    private static final String MARK = "kosmetika1";
    private static final String NAMESPACE = "f8resurs";

    @Test
    void cosmeticIsRecognisedByItsMark() {
        assertTrue(Cosmetics.cosmetic(MARK));
        assertTrue(Cosmetics.sameCosmetic(MARK, NAMESPACE, "kosmetika1", "kosmetika1", "kosmetika1"));
        assertFalse(Cosmetics.sameCosmetic(MARK, NAMESPACE, "kosmetika1", "kosmetika2", "kosmetika2"),
                "Надетой считается только та косметика, что выбрана в профиле");
    }

    @Test
    void everyOtherCustomItemOfThePluginStaysOrdinary() {
        // У всех кастомных предметов плагина модель из пространства имён
        // f8resurs и метки косметики нет. Именно их охрана слота и ловила.
        String[] models = {
                "ancient_jug", "ancient_jug_filled",   // AncientJug
                "icy_rime", "rime", "depleted_rime",   // WinterItems
                "black_caviar", "red_caviar",          // CaviarListener
                "flask_water", "flask_poison",         // FlaskListener
                "star", "star_beam",                   // ConstellationManager
                "medal_gold", "medal_silver",          // ProfileItems
                "copper_note_block",                   // CopperBlockListener
                "pref1",                               // префиксы
        };
        for (String model : models) {
            assertFalse(Cosmetics.cosmetic(null),
                    "Предмет без метки — не косметика: " + model);
            assertFalse(Cosmetics.sameCosmetic(null, NAMESPACE, model, "kosmetika1", "kosmetika1"),
                    "Совпадение модели без метки ничего не значит: " + model);
        }
    }

    @Test
    void plainItemsAreNotCosmeticsEither() {
        assertFalse(Cosmetics.cosmetic(null));
        assertFalse(Cosmetics.sameCosmetic(null, null, null, "kosmetika1", "kosmetika1"),
                "Обычный предмет без модели и без метки");
        assertFalse(Cosmetics.sameCosmetic(null, "minecraft", "paper", "kosmetika1", "kosmetika1"),
                "Ванильный лист бумаги");
    }

    @Test
    void modelIsOnlyAFallbackForAnUnmarkedOwnCosmetic() {
        // Метки нет, но модель — наша косметика: такой предмет мог остаться от
        // старой сборки. Считаем своим, чтобы он не застрял в слоте шлема.
        assertTrue(Cosmetics.sameCosmetic(null, NAMESPACE, "kosmetika2", "kosmetika2", "kosmetika2"));
        assertFalse(Cosmetics.sameCosmetic(null, NAMESPACE, "kosmetika2", "kosmetika1", "kosmetika1"),
                "Запасной признак сверяет модель с конкретной косметикой");
    }

    @Test
    void markDecidesEvenWhenTheModelIsWrong() {
        // Метка важнее модели: если модель подменили, предмет всё равно
        // остаётся косметикой и охраняется как она.
        assertTrue(Cosmetics.sameCosmetic(MARK, "minecraft", "paper", "kosmetika1", "kosmetika1"));
    }
}
