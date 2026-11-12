package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** id рюмок из config.yml приводятся к «namespace:path» так же, как их пишет RP Engine. */
final class ShotGlassTest {

    @Test
    void пробелыИРегистрНеМешают() {
        assertEquals(
                List.of("testpack:statue", "mypack:glass"),
                new ArrayList<>(ShotGlass.cleanIds(List.of(" TestPack:Statue ", "mypack:glass")))
        );
    }

    @Test
    void пустыеСтрокиИПовторыВыбрасываются() {
        assertEquals(
                List.of("testpack:statue"),
                new ArrayList<>(ShotGlass.cleanIds(Arrays.asList("testpack:statue", "", "  ", null,
                        "testpack:statue")))
        );
    }

    @Test
    void пустойСписокОстаётсяПустым() {
        assertTrue(ShotGlass.cleanIds(null).isEmpty());
        assertTrue(ShotGlass.cleanIds(List.of()).isEmpty());
    }

    @Test
    void посудаПоУмолчаниюЭтоСтаканБокалИРюмка() {
        assertTrue(ShotGlass.isConfigured("testpack:stakan"));
        assertTrue(ShotGlass.isConfigured("testpack:bokal"));
        assertTrue(ShotGlass.isConfigured("testpack:ryumka"));
        // Регистр и пробелы не важны.
        assertTrue(ShotGlass.isConfigured(" TestPack:Stakan "));
    }

    @Test
    void статуяБольшеНеПосуда() {
        assertFalse(ShotGlass.isConfigured("testpack:statue"));
        assertFalse(ShotGlass.isConfigured(""));
        assertFalse(ShotGlass.isConfigured(null));
    }

    @Test
    void старыйIdНеОсталсяВСпискеПоУмолчанию() {
        // ConfigSync не переписывает значения владельца, поэтому старый
        // testpack:statue у кого-то так и лежит в config.yml — он должен
        // отбрасываться, а не подмешиваться к посуде.
        for (String id : ShotGlass.DEFAULT_MODELS) {
            assertFalse(ShotGlass.LEGACY_MODELS.contains(id), id + " не должен быть старым id");
        }
        assertTrue(ShotGlass.LEGACY_MODELS.contains("testpack:statue"));
    }

    @Test
    void безСодержимогоНаливатьНечего() {
        assertEquals(-1, ShotGlass.drinkIndex(null));
        assertEquals(-1, ShotGlass.drinkIndex(List.of()));
    }
}
