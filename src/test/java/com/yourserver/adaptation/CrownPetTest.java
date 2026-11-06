package com.yourserver.adaptation;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Второй питомец: модель из config.yml, разбор ключей модели и плавное
 * появление/затухание реплик. Сама сущность проверяется только на сервере.
 */
class CrownPetTest {

    private static String shipped(String name) throws IOException {
        try (InputStream stream = CrownPetTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name + " должен лежать в комплекте");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) > 0) out.append(buffer, 0, read);
                return out.toString();
            }
        }
    }

    @Test
    void shippedConfigKeepsBothPetModelsAndTheCrownModel() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(shipped("config.yml"));

        List<String> models = config.getStringList("pets.test-models");
        assertEquals(List.of("f8resurs:test-pets", "f8resurs:test-pets2"), models,
                "первый питомец меняет модель каждую секунду по этому списку");
        assertEquals("f8resurs:pet-2", config.getString("pets.crown-model"),
                "у питомца-короны должна быть модель pet-2");
    }

    @Test
    void modelKeysAreParsedAndBlankValuesAreIgnored() {
        assertEquals(new NamespacedKey("f8resurs", "pet-2"), CrownPet.parseModel("f8resurs:pet-2"));
        assertEquals(new NamespacedKey("f8resurs", "test-pets2"),
                PetManager.parseModel(" f8resurs:test-pets2 "));
        assertNull(CrownPet.parseModel("   "));
        assertNull(CrownPet.parseModel(null));
    }

    /** Клиент читает непрозрачность как беззнаковый байт, поэтому сравниваем так же. */
    private static int opacity(int age, int lifetime) {
        return Byte.toUnsignedInt(CrownPet.speechOpacity(age, lifetime));
    }

    @Test
    void speechFadesInAndOutInsteadOfPopping() {
        int lifetime = 80;
        assertEquals(4, opacity(0, lifetime), "первый тик почти прозрачный");
        assertTrue(opacity(10, lifetime) > opacity(0, lifetime), "текст плавно появляется");
        assertTrue(opacity(15, lifetime) > opacity(10, lifetime));
        assertEquals(255, opacity(lifetime / 2, lifetime), "в середине текст полностью виден");
        assertTrue(opacity(lifetime - 1, lifetime) < 255, "перед исчезновением текст тускнеет");
        assertTrue(opacity(lifetime - 1, lifetime) >= 4, "минимум 4, иначе клиент считает текст непрозрачным");
    }

    @Test
    void shortRepliesStillFadeOutBeforeTheyDisappear() {
        int lifetime = 40;
        assertEquals(255, opacity(lifetime / 2, lifetime));
        assertTrue(opacity(lifetime - 1, lifetime) < 40, "короткий ответ успевает погаснуть");
    }
}
