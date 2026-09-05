package com.yourserver.adaptation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MedalMigrationTest {
    @TempDir Path directory;

    private Logger logger() { Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF); return logger; }
    private Path profiles() { return directory.resolve("profiles"); }
    private Path medals() { return directory.resolve("medals/players"); }
    private ProfileStorage storage(AsyncTextWriter writer) {
        return new ProfileStorage(profiles(), medals(), writer, logger(), MedalSettings.defaults()::migrate);
    }
    private ProfileMedal medal(ProfileMedal.Metal metal, String title, String source) {
        return new ProfileMedal(UUID.randomUUID(), metal, title, List.of("Заслуга"), 1_788_600_000_000L, source);
    }

    @Test
    void legacyDataMovesWithoutLosingDatesLayoutVotesOrRepeatingAnnouncements() throws Exception {
        UUID owner = UUID.randomUUID();
        ProfileData legacy = new ProfileData(owner, "Player");
        legacy.describe(owner, "Описание");
        legacy.vote(UUID.randomUUID(), ProfileData.Vote.LIKE);
        ProfileMedal old = medal(ProfileMedal.Metal.COPPER, "Медная медаль", ProfileMedal.FIRST_CONSTELLATION);
        legacy.award(old); legacy.place(owner, old.id(), 17);
        String original = ProfileCodec.encode(legacy);
        Files.createDirectories(profiles());
        Files.writeString(profiles().resolve(owner + ".yml"), original);
        try (AsyncTextWriter writer = new AsyncTextWriter(logger())) {
            ProfileStorage storage = storage(writer);
            ProfileData migrated = storage.get(owner, "Ignored");
            assertEquals("Описание", migrated.description());
            assertEquals(1, migrated.likes());
            assertEquals(old.id(), migrated.medalAt(17));
            assertEquals(old.awardedAt(), migrated.medals().get(old.id()).awardedAt());
            assertEquals("Астрономия!", migrated.medals().get(old.id()).title());
            assertFalse(migrated.hasPendingNotifications());
            assertTrue(migrated.hasReward(ProfileMedal.FIRST_CONSTELLATION));
            storage.shutdown();
        }
        assertEquals(original, Files.readString(profiles().resolve("legacy-backup").resolve(owner + ".yml")));
        YamlConfiguration profile = new YamlConfiguration();
        profile.loadFromString(Files.readString(profiles().resolve(owner + ".yml")));
        assertEquals(2, profile.getInt("version"));
        assertFalse(profile.contains("medals"));
        assertEquals(1, ProfileCodec.decodeMedals(owner, Files.readString(medals().resolve(owner + ".yml"))).size());
    }

    @Test
    void revokingAnAutomaticMedalDoesNotRecreateItAfterRestart() {
        UUID owner = UUID.randomUUID();
        try (AsyncTextWriter writer = new AsyncTextWriter(logger())) {
            ProfileStorage storage = storage(writer);
            ProfileData data = storage.get(owner, "Player");
            assertTrue(ProfileAwards.firstConstellation(data, 1000));
            data.medals().values().forEach(data::markNotified);
            storage.changed(owner); assertTrue(storage.flushBlocking(owner));
            UUID id = data.latestMedal().id();
            assertTrue(data.revoke(id));
            storage.changed(owner); assertTrue(storage.flushBlocking(owner));
        }
        try (AsyncTextWriter writer = new AsyncTextWriter(logger())) {
            ProfileData restored = storage(writer).get(owner, "Player");
            assertTrue(restored.medals().isEmpty());
            assertTrue(restored.hasReward(ProfileMedal.FIRST_CONSTELLATION));
            assertFalse(ProfileAwards.firstConstellation(restored, 2000));
        }
    }

    @Test
    void liveFileEditAddsRemovesAndClearsSlotsWithoutProfileAutosaveOverwritingTheFile() throws Exception {
        UUID owner = UUID.randomUUID();
        try (AsyncTextWriter writer = new AsyncTextWriter(logger())) {
            ProfileStorage storage = storage(writer);
            ProfileData data = storage.get(owner, "Player");
            ProfileMedal old = medal(ProfileMedal.Metal.COPPER, "Старая", "");
            data.award(old); data.place(owner, old.id(), 0); data.markNotified(old);
            storage.changed(owner); assertTrue(storage.flushBlocking(owner));
            ProfileData edited = new ProfileData(owner, "Player");
            ProfileMedal added = medal(ProfileMedal.Metal.SILVER, "Новая", "");
            edited.award(added);
            String exactEdit = ProfileCodec.encodeMedals(edited) + "\n# Ручная правка\n";
            Files.writeString(storage.medalPath(owner), exactEdit);
            storage.applyReloadPlan(storage.readReloadPlan());
            assertFalse(data.medals().containsKey(old.id()));
            assertTrue(data.medals().containsKey(added.id()));
            assertNull(data.medalAt(0));
            assertTrue(data.needsNotification(added));
            data.vote(UUID.randomUUID(), ProfileData.Vote.LIKE);
            storage.changed(owner); assertTrue(storage.flushBlocking(owner));
            assertEquals(exactEdit, Files.readString(storage.medalPath(owner)));
        }
    }

    @Test
    void corruptReloadIsAllOrNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        try (AsyncTextWriter writer = new AsyncTextWriter(logger())) {
            ProfileStorage storage = storage(writer);
            ProfileData data = storage.get(owner, "Player");
            ProfileMedal original = medal(ProfileMedal.Metal.GOLD, "Заслуженная", "");
            data.award(original); storage.changed(owner); assertTrue(storage.flushBlocking(owner));
            Files.writeString(medals().resolve(UUID.randomUUID() + ".yml"), "version: [broken");
            assertThrows(IllegalStateException.class, storage::readReloadPlan);
            assertEquals(original, data.medals().get(original.id()));
        }
    }

    @Test
    void externalEditBetweenMutationAndWriteWinsAndCanBeReloaded() throws Exception {
        UUID owner = UUID.randomUUID();
        try (AsyncTextWriter writer = new AsyncTextWriter(logger())) {
            ProfileStorage storage = storage(writer);
            ProfileData data = storage.get(owner, "Player");
            storage.prepareMedalChange(owner);
            ProfileAwards.firstConstellation(data, 1000);
            storage.changed(owner);
            ProfileData editor = new ProfileData(owner, "Player");
            ProfileMedal manual = medal(ProfileMedal.Metal.GOLD, "Ручная", "");
            editor.award(manual);
            String manualFile = ProfileCodec.encodeMedals(editor);
            Files.writeString(storage.medalPath(owner), manualFile);
            assertFalse(storage.flushBlocking(owner));
            assertEquals(manualFile, Files.readString(storage.medalPath(owner)));
            storage.applyReloadPlan(storage.readReloadPlan());
            assertEquals(List.of(manual), List.copyOf(data.medals().values()));
            assertFalse(data.hasReward(ProfileMedal.FIRST_CONSTELLATION));
        }
    }

    @Test
    void changingOnlyTitleOrDateDoesNotRepeatTheAwardMessage() {
        ProfileData data = new ProfileData(UUID.randomUUID(), "Player");
        ProfileMedal old = medal(ProfileMedal.Metal.COPPER, "Астрономия!", "");
        data.award(old); data.markNotified(old);
        ProfileMedal edited = new ProfileMedal(old.id(), old.metal(), "Другое название", old.reasons(), old.awardedAt() + 1000, old.source());
        data.replaceMedals(List.of(edited));
        assertFalse(data.hasPendingNotifications());
        assertFalse(data.needsNotification(edited));
    }
}
