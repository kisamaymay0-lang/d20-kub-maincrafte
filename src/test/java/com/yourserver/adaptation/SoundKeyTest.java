package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Звуки пака: ключи, которые просит плагин, и образец sounds.json. */
class SoundKeyTest {

    @Test
    void pluginAsksForTheTwoDocumentedSoundKeys() {
        assertArrayEquals(
                new String[]{"f8resurs:pet-talking1", "f8resurs:pet-talking2"},
                F8Command.TEST_SOUND_KEYS
        );
    }

    @Test
    void sampleSoundsJsonUsesPlainKeysAndBothEntries() throws IOException {
        Path file = Path.of("docs", "sounds.json");
        assertTrue(Files.isRegularFile(file), "образец должен лежать в docs/sounds.json");
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(json.startsWith("\uFEFF"), "BOM ломает чтение sounds.json");
        assertTrue(json.contains("\"pet-talking1\""), "ключ первого питомца");
        assertTrue(json.contains("\"pet-talking2\""), "ключ короны");
        assertFalse(json.contains("\"f8resurs:"), "в ключах и name не должно быть неймспейса");
        assertFalse(json.contains(".ogg"), "name указывается без расширения");
    }
}
