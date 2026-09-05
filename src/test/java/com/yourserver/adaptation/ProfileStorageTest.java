package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStorageTest {
    @TempDir Path directory;

    private static Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    @Test
    void roundTripPreservesDescriptionVotesMedalsDatesAndExactEmptySlots() throws Exception {
        UUID id = UUID.randomUUID();
        ProfileData profile = new ProfileData(id, "Player");
        profile.describe(id, "Моё описание с кавычками: \"да\" и символами | : & <>");
        profile.vote(UUID.randomUUID(), ProfileData.Vote.LIKE);
        profile.vote(UUID.randomUUID(), ProfileData.Vote.DISLIKE);
        ProfileAwards.firstConstellation(profile, 1_788_600_000_000L);
        ProfileMedal medal = profile.medals().values().iterator().next();
        profile.place(id, medal.id(), 17);
        ProfileData restored = ProfileCodec.decode(id, ProfileCodec.encode(profile));
        assertEquals(profile.description(), restored.description());
        assertEquals(profile.votes(), restored.votes());
        assertEquals(profile.medals(), restored.medals());
        assertArrayEquals(profile.layout(), restored.layout());
        assertEquals(1, restored.likes());
        assertEquals(1, restored.dislikes());
        assertFalse(ProfileAwards.firstConstellation(restored, 1_788_700_000_000L));
    }

    @Test
    void differentPlayerCannotReadAProfileFileWithAnotherOwner() {
        ProfileData profile = new ProfileData(UUID.randomUUID(), "Player");
        assertThrows(IllegalArgumentException.class, () -> ProfileCodec.decode(UUID.randomUUID(), ProfileCodec.encode(profile)));
    }

    @Test
    void shutdownFlushesDataAndRestartKeepsMedalsAndVotes() {
        UUID owner = UUID.randomUUID();
        UUID voter = UUID.randomUUID();
        Logger logger = quietLogger();
        try (AsyncTextWriter writer = new AsyncTextWriter(logger)) {
            ProfileStorage storage = new ProfileStorage(directory, writer, logger);
            ProfileData profile = storage.get(owner, "FirstName");
            profile.rename("NewName");
            profile.vote(voter, ProfileData.Vote.LIKE);
            profile.award(new ProfileMedal(UUID.randomUUID(), ProfileMedal.Metal.GOLD, "Путешественник", List.of("Первая заслуга", "Вторая заслуга"), 1000, ""));
            storage.changed(owner);
            storage.shutdown();
        }
        try (AsyncTextWriter writer = new AsyncTextWriter(logger)) {
            ProfileStorage storage = new ProfileStorage(directory, writer, logger);
            ProfileData restored = storage.get(owner, "IgnoredFallback");
            assertEquals("NewName", restored.name());
            assertEquals(1, restored.likes());
            assertEquals(ProfileData.Vote.LIKE, restored.voteBy(voter));
            assertEquals(1, restored.medals().size());
        }
    }

    @Test
    void corruptProfileIsNotSilentlyOverwrittenWithAnEmptyOne() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = directory.resolve(id + ".yml");
        String corrupt = "version: [this is not valid YAML";
        Files.writeString(file, corrupt);
        try (AsyncTextWriter writer = new AsyncTextWriter(quietLogger())) {
            ProfileStorage storage = new ProfileStorage(directory, writer, quietLogger());
            assertThrows(IllegalStateException.class, () -> storage.get(id, "Player"));
            storage.shutdown();
        }
        assertEquals(corrupt, Files.readString(file));
    }

    @Test
    void duplicatePlacementInTheFileIsRejectedInsteadOfCloningAMedal() throws Exception {
        UUID id = UUID.randomUUID();
        ProfileData profile = new ProfileData(id, "Player");
        ProfileMedal medal = new ProfileMedal(UUID.randomUUID(), ProfileMedal.Metal.COPPER, "Медная медаль", List.of("Заслуга"), 1000, "");
        profile.award(medal);
        profile.place(id, medal.id(), 0);
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString(ProfileCodec.encode(profile));
        yaml.set("display", List.of(medal.id().toString(), medal.id().toString()));
        assertThrows(IllegalArgumentException.class, () -> ProfileCodec.decode(id, yaml.saveToString()));
    }
}
