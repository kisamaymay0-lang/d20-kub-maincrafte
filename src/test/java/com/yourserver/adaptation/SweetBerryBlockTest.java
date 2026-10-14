package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты механики блока сладких ягод (Sweet Berry Block) и связанных предметов.
 */
final class SweetBerryBlockTest {

    private static final Path ROOT = repoRoot();

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("resourcepack"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Не найден корень репозитория");
    }

    @Test
    void craftEngineSweetBerryBlockIdIsCorrect() {
        assertEquals("f8resurs:sweetie_bery_block", CraftEngineSweetBerry.ID);
        assertTrue(CraftEngineSweetBerry.isSweetBerryId("f8resurs:sweetie_bery_block"));
        assertFalse(CraftEngineSweetBerry.isSweetBerryId("f8resurs:copper_note_block"));
        assertFalse(CraftEngineSweetBerry.isSweetBerryId(null));
    }

    @Test
    void sweetBerryRequirementsAreCorrect() {
        assertEquals(12, SweetBerryBlock.REQUIRED_BERRIES, "Для создания блока требуется 12 ягод");
        assertEquals(8, SweetBerryBlock.REQUIRED_STAMPS, "Для разрушения от топтания требуется 8 топов");
        assertEquals(4, SweetBerryBlock.CRUSHED_BERRIES_DROP, "С одного блока выпадает 4 помятых или продавленных ягоды");
        assertEquals(10L, SweetBerryBlock.CRUSH_DELAY_TICKS, "Задержка превращения 0.5с (10 тиков)");
    }

    @Test
    void stampDataAccumulatesWeakAndStrongStampsSeparately() {
        SweetBerryBlock.StampData data = new SweetBerryBlock.StampData(0, 0);
        assertEquals(0, data.weakStamps);
        assertEquals(0, data.strongStamps);

        data.weakStamps += 3;
        assertEquals(3, data.weakStamps);
        assertEquals(0, data.strongStamps);

        data.strongStamps += 5;
        assertEquals(3, data.weakStamps);
        assertEquals(5, data.strongStamps);
    }

    @Test
    void resourcePackModelsAndItemsExist() {
        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/models/block/sweetie_bery_block.json")),
                "Модель блока sweetie_bery_block должна существовать");
        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/models/item/sweetie_bery_block.json")),
                "Модель предмета sweetie_bery_block должна существовать");
        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/items/sweetie_bery_block.json")),
                "Определение предмета sweetie_bery_block должно существовать");

        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/models/item/low_sweetie_berry.json")),
                "Модель предмета low_sweetie_berry должна существовать");
        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/items/low_sweetie_berry.json")),
                "Определение предмета low_sweetie_berry должно существовать");

        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/models/item/hard_sweetie_berry.json")),
                "Модель предмета hard_sweetie_berry должна существовать");
        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/items/hard_sweetie_berry.json")),
                "Определение предмета hard_sweetie_berry должно существовать");

        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/models/block/apvsh.json")),
                "Модель блока apvsh должна существовать");
        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/models/item/apvsh.json")),
                "Модель предмета apvsh должна существовать");
        assertTrue(Files.isRegularFile(ROOT.resolve("resourcepack/assets/f8resurs/items/apvsh.json")),
                "Определение предмета apvsh должно существовать");
    }
}
