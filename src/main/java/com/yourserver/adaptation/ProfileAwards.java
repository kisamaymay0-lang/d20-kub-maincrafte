package com.yourserver.adaptation;

import java.util.List;
import java.util.UUID;

/** Нулевой timestamp старого прогресса намеренно не даёт ретроактивную награду. */
final class ProfileAwards {
    private ProfileAwards() { }

    static boolean firstConstellation(ProfileData profile, long completedAfterUpdateAt) {
        if (completedAfterUpdateAt <= 0 || profile.hasReward(ProfileMedal.FIRST_CONSTELLATION)) return false;
        return profile.award(new ProfileMedal(UUID.randomUUID(), ProfileMedal.Metal.COPPER, "Медная медаль",
                List.of("Собрано 1 созвездие."), completedAfterUpdateAt, ProfileMedal.FIRST_CONSTELLATION));
    }
}
