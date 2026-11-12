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

    /**
     * Двойная надпись только одна: «...», а через две секунды — вторая часть.
     * Остальные реплики одиночные и говорятся без «...».
     */
    @Test
    void onlyTheLeadingEllipsisIsFollowedByASecondLine() {
        assertEquals("...", CrownPet.chatLeadIn(), "первая часть двойной надписи — просто «...»");

        List<String> twoPart = List.of(CrownPet.chatTwoPartReplies());
        assertEquals(List.of("Зачем ты вообще существуешь?", "Нет.", "Отстань"), twoPart);

        List<String> single = List.of(CrownPet.chatSingleReplies());
        assertTrue(single.contains("Наверное?"), "«Наверное?» остаётся одиночной репликой");
        assertTrue(single.contains("Зачем ты вообще существуешь?"));
        assertTrue(single.contains("Нет."), "«Нет.» может звучать и без «...»");
        assertTrue(single.contains("Отстань"), "«Отстань» может звучать и без «...»");
        assertTrue(single.stream().noneMatch("..."::equals), "одиночные реплики не начинаются с «...»");
        assertTrue(twoPart.stream().noneMatch("Наверное?"::equals),
                "«Наверное?» не дописывается второй частью");
    }

    @Test
    void chatRepliesNeverContainTheOldGodPhrase() {
        List<String> all = new java.util.ArrayList<>(List.of(CrownPet.chatSingleReplies()));
        all.addAll(List.of(CrownPet.chatTwoPartReplies()));
        assertTrue(all.stream().noneMatch("Боже..."::equals), "«Боже...» больше не используется");
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

    /** Углы сравниваем по кратчайшей дуге: -180° и 180° — одно направление. */
    private static void assertYawEquals(double expected, double actual) {
        double delta = (actual - expected) % 360.0D;
        if (delta > 180.0D) delta -= 360.0D;
        if (delta < -180.0D) delta += 360.0D;
        assertTrue(Math.abs(delta) < 0.001D,
                "ожидался разворот " + expected + "°, получили " + actual + "°");
    }

    /** Когда на корону смотрят, она разворачивается лицом к игроку. */
    @Test
    void gazeTurnsTheCrownTowardsThePlayer() {
        // Игрок смотрит на север (yaw 0), корона перед ним: лицом к игроку — юг.
        assertYawEquals(180.0D, CrownPet.facingYaw(0.0D, -1.0D, 0.0F));
        // Игрок смотрит на восток (yaw -90), корона восточнее него.
        assertYawEquals(90.0D, CrownPet.facingYaw(-1.0D, 0.0D, -90.0F));
        // Корона сбоку: разворот идёт на реальное положение игрока, а не по взгляду.
        assertYawEquals(180.0D, CrownPet.facingYaw(0.0D, -1.0D, -90.0F));
        assertYawEquals(0.0D, CrownPet.facingYaw(0.0D, 1.0D, 90.0F));
        assertYawEquals(-90.0D, CrownPet.facingYaw(1.0D, 0.0D, 0.0F));
    }

    /**
     * Корона висит почти точно над головой: горизонтальное направление на
     * игрока вырождено, поэтому берётся разворот навстречу взгляду.
     */
    @Test
    void crownDirectlyOverheadFacesThePlayerInsteadOfSpinning() {
        assertYawEquals(215.0D, CrownPet.facingYaw(0.01D, 0.01D, 35.0F));
        assertYawEquals(0.0D, CrownPet.facingYaw(0.0D, 0.0D, -180.0F));
        // Взгляд вверх не должен разворачивать корону по вертикали — только yaw.
        assertYawEquals(270.0D, CrownPet.facingYaw(0.05D, -0.02D, 90.0F));
    }

    /** У каждой короны своя фаза, и разные оси не двигаются синхронно. */
    @Test
    void eachCrownSwingsWithItsOwnPhase() {
        assertNotEquals(CrownPet.wave(120L, 130L, 0.0D), CrownPet.wave(120L, 130L, 2.1D), 0.01D);
        assertNotEquals(CrownPet.wave(200L, 130L, 0.0D), CrownPet.wave(200L, 47L, 0.0D), 0.01D);
    }

}
