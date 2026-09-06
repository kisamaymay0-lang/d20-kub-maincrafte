package com.yourserver.adaptation;

import java.util.UUID;

/** Выдача по новому завершению, а не пожизненный запрет после изъятия. */
final class ProfileAwards {
    private ProfileAwards() { }

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
