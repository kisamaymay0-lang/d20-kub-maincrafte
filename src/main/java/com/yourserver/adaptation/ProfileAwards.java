package com.yourserver.adaptation;

import java.util.UUID;

/** Выдача по новому завершению, а не пожизненный запрет после изъятия. */
final class ProfileAwards {
    private static final String EATEN_RED = "sandwich_eaten_red";
    private static final String EATEN_BLACK = "sandwich_eaten_black";
    private static final String EATEN_ICE = "sandwich_eaten_ice";
    private ProfileAwards() { }

    static String eatenMarker(String kind) {
        return "sandwich_eaten_" + kind;
    }

    /** Съедены по одному бутерброду каждого вида: с красной, чёрной и ледяной икрой. */
    static boolean allSandwichKindsEaten(ProfileData profile) {
        return profile.hasReward(EATEN_RED) && profile.hasReward(EATEN_BLACK) && profile.hasReward(EATEN_ICE);
    }

    static boolean firstConstellation(ProfileData profile, long completedAfterUpdateAt) {
        return firstConstellation(profile, completedAfterUpdateAt, MedalSettings.defaults());
    }

    static boolean firstConstellation(ProfileData profile, long completedAfterUpdateAt, MedalSettings settings) {
        if (completedAfterUpdateAt <= 0 || completedAfterUpdateAt <= profile.astronomyProgress()) return false;
        boolean legacyHistory = profile.astronomyProgress() == 0 && profile.hasReward(ProfileMedal.FIRST_CONSTELLATION);
        profile.astronomyProgress(completedAfterUpdateAt);
        // Старую историю 9.3–9.5 принимаем как уже использованный результат:
        // снятая медаль не возвращается от одного открытия профиля.
        if (legacyHistory || profile.ownsReward(ProfileMedal.FIRST_CONSTELLATION)) return false;
        return profile.award(new ProfileMedal(UUID.randomUUID(), ProfileMedal.Metal.COPPER, settings.astronomyTitle,
                settings.astronomyReasons, completedAfterUpdateAt, ProfileMedal.FIRST_CONSTELLATION));
    }
}
