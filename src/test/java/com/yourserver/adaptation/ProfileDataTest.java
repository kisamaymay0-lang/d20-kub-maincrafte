package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProfileDataTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();

    private ProfileMedal medal(ProfileMedal.Metal metal) {
        return new ProfileMedal(UUID.randomUUID(), metal, metal.title, List.of("Первая заслуга", "Вторая заслуга"), 1_788_600_000_000L, "");
    }

    @Test
    void defaultsAndOwnerOnlyDescription() {
        ProfileData data = new ProfileData(owner, "Player");
        assertEquals("Нет описания.", data.displayedDescription());
        assertFalse(data.describe(visitor, "Чужое изменение"));
        assertTrue(data.describe(owner, "  Моё  описание  "));
        assertEquals("Моё описание", data.displayedDescription());
        assertTrue(data.describe(owner, ""));
        assertEquals("Нет описания.", data.displayedDescription());
        assertThrows(IllegalArgumentException.class, () -> data.describe(owner, "x".repeat(161)));
    }

    @Test
    void oneVoteCanBeChangedRemovedButNeverCastForSelf() {
        ProfileData data = new ProfileData(owner, "Player");
        assertFalse(data.vote(owner, ProfileData.Vote.LIKE));
        assertTrue(data.vote(visitor, ProfileData.Vote.LIKE));
        assertEquals(1, data.likes());
        data.vote(visitor, ProfileData.Vote.DISLIKE);
        assertEquals(0, data.likes());
        assertEquals(1, data.dislikes());
        data.vote(visitor, ProfileData.Vote.DISLIKE);
        assertEquals(0, data.dislikes());
        assertNull(data.voteBy(visitor));
    }

    @Test
    void repeatedVotesNeverDriftFromTheAuthoritativePerVoterMap() {
        ProfileData data = new ProfileData(owner, "Player");
        UUID[] voters = {visitor, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            data.vote(voters[random.nextInt(voters.length)], random.nextBoolean() ? ProfileData.Vote.LIKE : ProfileData.Vote.DISLIKE);
            assertEquals(data.votes().values().stream().filter(v -> v == ProfileData.Vote.LIKE).count(), data.likes());
            assertEquals(data.votes().values().stream().filter(v -> v == ProfileData.Vote.DISLIKE).count(), data.dislikes());
        }
    }

    @Test
    void movingAndReplacingMedalsDoesNotDuplicateOrDestroyThem() {
        ProfileData data = new ProfileData(owner, "Player");
        ProfileMedal first = medal(ProfileMedal.Metal.COPPER);
        ProfileMedal second = medal(ProfileMedal.Metal.GOLD);
        data.award(first); data.award(second);
        assertTrue(data.place(owner, first.id(), 0));
        assertTrue(data.place(owner, first.id(), 17));
        assertNull(data.medalAt(0));
        assertEquals(first.id(), data.medalAt(17));
        assertTrue(data.place(owner, second.id(), 17));
        assertEquals(2, data.medals().size());
        assertFalse(data.isPlaced(first.id()));
        assertTrue(data.medals().containsKey(first.id()));
        assertTrue(data.unplace(owner, second.id()));
        assertNull(data.medalAt(17));
    }

    @Test
    void foreignAndInvalidPlacementsAreDeniedAndSnapshotsAreNotMutable() {
        ProfileData data = new ProfileData(owner, "Player");
        ProfileMedal medal = medal(ProfileMedal.Metal.SILVER);
        data.award(medal);
        assertFalse(data.place(visitor, medal.id(), 0));
        assertFalse(data.place(owner, medal.id(), 18));
        assertFalse(data.place(owner, UUID.randomUUID(), 0));
        data.place(owner, medal.id(), 1);
        assertFalse(data.unplace(visitor, medal.id()));
        data.layout()[1] = null;
        assertEquals(medal.id(), data.medalAt(1));
        assertThrows(UnsupportedOperationException.class, () -> data.medals().clear());
    }

    @Test
    void oldProgressDoesNotAwardAnythingButNewCompletionAwardsExactlyOneCopper() {
        ProfileData data = new ProfileData(owner, "Player");
        assertFalse(ProfileAwards.firstConstellation(data, 0));
        assertTrue(data.medals().isEmpty());
        long earnedAt = 1_788_600_000_000L;
        assertTrue(ProfileAwards.firstConstellation(data, earnedAt));
        assertFalse(ProfileAwards.firstConstellation(data, earnedAt + 1000));
        assertEquals(1, data.medals().size());
        ProfileMedal award = data.medals().values().iterator().next();
        assertEquals(ProfileMedal.Metal.COPPER, award.metal());
        assertEquals(earnedAt, award.awardedAt());
        assertFalse(data.isPlaced(award.id()));
    }

    @Test
    void manualMedalsOfAllMetalsDoNotConsumeAutomaticAchievement() {
        ProfileData data = new ProfileData(owner, "Player");
        for (ProfileMedal.Metal type : ProfileMedal.Metal.values()) assertTrue(data.award(medal(type)));
        assertTrue(ProfileAwards.firstConstellation(data, 1000));
        assertEquals(4, data.medals().size());
    }

    @Test
    void onlyTheUpperAndLowerRowsArePlacementTargets() {
        for (int i = 0; i < 27; i++) {
            int logical = ProfileText.medalSlot(i);
            if (i >= 9 && i < 18) assertEquals(-1, logical);
            else assertEquals(i, ProfileText.inventorySlot(logical));
        }
        assertEquals(-1, ProfileText.medalSlot(-1));
        assertEquals(-1, ProfileText.medalSlot(27));
        assertThrows(IllegalArgumentException.class, () -> ProfileText.inventorySlot(18));
    }

    @Test
    void plainTextAndWrappingPreserveUnicodeWithoutAcceptingFormattingOrControlCodes() {
        assertEquals("Привет мир!", ProfileText.clean("§cПривет\n\tмир!\u202e\u0000"));
        assertEquals(List.of("АБВ", "ГДЕ"), ProfileText.wrap("АБВ ГДЕ", 3));
        assertEquals(List.of("🚀🚀", "🚀"), ProfileText.wrap("🚀🚀🚀", 2));
        ProfileData data = new ProfileData(owner, "Player");
        assertTrue(data.describe(owner, "🚀".repeat(160)));
        assertEquals(160, ProfileText.length(data.description()));
    }
}
