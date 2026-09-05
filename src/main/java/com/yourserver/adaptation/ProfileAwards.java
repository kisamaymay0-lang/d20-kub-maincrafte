package com.yourserver.adaptation;

import java.util.UUID;

/** Нулевой timestamp старого прогресса намеренно не даёт ретроактивную награду. */
final class ProfileAwards {
    private ProfileAwards() { }

    static boolean firstConstellation(ProfileData profile, long completedAfterUpdateAt) {
        return firstConstellation(profile, completedAfterUpdateAt, MedalSettings.defaults());
    }

    static boolean firstConstellation(ProfileData profile, long completedAfterUpdateAt, MedalSettings settings) {
        if (completedAfterUpdateAt <= 0 || profile.hasReward(ProfileMedal.FIRST_CONSTELLATION)) return false;
        return profile.award(new ProfileMedal(UUID.randomUUID(), ProfileMedal.Metal.COPPER, settings.astronomyTitle,
                settings.astronomyReasons, completedAfterUpdateAt, ProfileMedal.FIRST_CONSTELLATION));
    }
}
