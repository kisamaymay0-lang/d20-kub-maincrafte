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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** «Живость»: волна не выходит за -1..1 и меняется плавно, а не рывками. */
    @Test
    void idleWaveIsSmoothAndReachesItsFullRange() {
        long period = 130L;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double maxStep = 0.0D;
        double previous = CrownPet.wave(0L, period, 0.0D);
        for (long tick = 1L; tick <= period * 4L; tick++) {
            double value = CrownPet.wave(tick, period, 0.0D);
            min = Math.min(min, value);
            max = Math.max(max, value);
            maxStep = Math.max(maxStep, Math.abs(value - previous));
            previous = value;
        }
        assertEquals(-1.0D, min, 0.01D, "волна доходит до нижней точки");
        assertEquals(1.0D, max, 0.01D, "волна доходит до верхней точки");
        assertTrue(maxStep < 0.06D, "между тиками движение остаётся плавным");
        assertTrue(maxStep > 0.01D, "и заметным, иначе корона просто стоит");
    }

    /** Сумма двух волн: покачивание живое, но остаётся в пределах пары сантиметров. */
    @Test
    void idleLiftStaysWithinAFewCentimetres() {
        double biggest = 0.0D;
        double smallest = Double.MAX_VALUE;
        for (long tick = 0L; tick < 2_000L; tick++) {
            double lift = CrownPet.idleLift(tick, 1.1D);
            biggest = Math.max(biggest, Math.abs(lift));
            smallest = Math.min(smallest, Math.abs(lift));
        }
        assertTrue(biggest <= 0.067D + 1.0E-9D, "корона не улетает выше, чем на ~6 см");
        assertTrue(biggest > 0.03D, "но и не стоит на месте");
        assertTrue(smallest < 0.01D, "в движении есть и почти нулевые точки — оно не дёрганое");
    }

    /** Когда на корону смотрят, она разворачивается лицом к игроку. */
    @Test
    void gazeTurnsTheCrownTowardsThePlayer() {
        // Игрок смотрит на север (yaw 0), корона перед ним: лицом к игроку — юг.
        assertEquals(180.0D, CrownPet.facingYaw(0.0D, -1.0D, 0.0F), 0.001D);
        // Игрок смотрит на восток (yaw -90), корона восточнее него.
        assertEquals(90.0D, CrownPet.facingYaw(-1.0D, 0.0D, -90.0F), 0.001D);
        // Корона сбоку: разворот идёт на реальное положение игрока.
        assertEquals(0.0D, CrownPet.facingYaw(0.0D, 1.0D, 90.0F), 0.001D);
        assertEquals(-90.0D, CrownPet.facingYaw(1.0D, 0.0D, 0.0F), 0.001D);
    }

    /**
     * Корона висит почти точно над головой: горизонтальное направление на
     * игрока вырождено, поэтому берётся разворот навстречу взгляду.
     */
    @Test
    void crownDirectlyOverheadFacesThePlayerInsteadOfSpinning() {
        assertEquals(215.0D, CrownPet.facingYaw(0.01D, 0.01D, 35.0F), 0.001D);
        assertEquals(0.0D, CrownPet.facingYaw(0.0D, 0.0D, -180.0F), 0.001D);
        // Взгляд вверх не должен разворачивать корону по вертикали — только yaw.
        assertEquals(270.0D, CrownPet.facingYaw(0.05D, -0.02D, 90.0F), 0.001D);
    }

    /** У каждой короны своя фаза, и разные оси не двигаются синхронно. */
    @Test
    void eachCrownSwingsWithItsOwnPhase() {
        assertNotEquals(CrownPet.wave(120L, 130L, 0.0D), CrownPet.wave(120L, 130L, 2.1D), 0.01D);
        assertNotEquals(CrownPet.wave(200L, 130L, 0.0D), CrownPet.wave(200L, 47L, 0.0D), 0.01D);
    }

    @Test
    void shortRepliesStillFadeOutBeforeTheyDisappear() {
        int lifetime = 40;
        assertEquals(255, opacity(lifetime / 2, lifetime));
        assertTrue(opacity(lifetime - 1, lifetime) < 40, "короткий ответ успевает погаснуть");
    }
}
