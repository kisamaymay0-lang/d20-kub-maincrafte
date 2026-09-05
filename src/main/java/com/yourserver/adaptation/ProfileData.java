package com.yourserver.adaptation;

import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Всё изменяется на основном потоке. Правила проверяются и здесь, не только в меню. */
final class ProfileData {
    enum Vote { LIKE, DISLIKE }

    final UUID owner;
    private String name;
    private String description = "";
    private final Map<UUID, Vote> votes = new HashMap<>();
    private final Map<UUID, ProfileMedal> medals = new LinkedHashMap<>();
    private final Set<String> rewards = new HashSet<>();
    private final Map<UUID, Long> notified = new HashMap<>();
    private final Set<UUID> unannounced = new HashSet<>();
    private final UUID[] layout = new UUID[18];
    private int likes;
    private int dislikes;
    private long revision;
    private long medalRevision;

    ProfileData(UUID owner, String name) {
        this.owner = Objects.requireNonNull(owner);
        this.name = Objects.requireNonNullElse(name, owner.toString());
    }

    String name() { return name; }
    String description() { return description; }
    String displayedDescription() { return description.isBlank() ? ProfileText.NO_DESCRIPTION : description; }
    int likes() { return likes; }
    int dislikes() { return dislikes; }
    long revision() { return revision; }
    long medalRevision() { return medalRevision; }
    Set<String> rewardHistory() { return Set.copyOf(rewards); }
    Map<UUID, Long> notificationHistory() { return Map.copyOf(notified); }
    Vote voteBy(UUID voter) { return votes.get(voter); }
    Map<UUID, Vote> votes() { return Collections.unmodifiableMap(votes); }
    Map<UUID, ProfileMedal> medals() { return Collections.unmodifiableMap(medals); }
    UUID[] layout() { return layout.clone(); }
    UUID medalAt(int slot) { return layout[slot]; }

    boolean rename(String name) {
        if (this.name.equals(name)) return false;
        this.name = name;
        revision++;
        return true;
    }

    boolean describe(UUID actor, String text) {
        if (!owner.equals(actor)) return false;
        String cleaned = ProfileText.clean(text);
        if (ProfileText.length(cleaned) > ProfileText.DESCRIPTION_LIMIT) throw new IllegalArgumentException("Описание до 160 символов");
        if (description.equals(cleaned)) return false;
        description = cleaned;
        revision++;
        return true;
    }

    boolean vote(UUID voter, Vote vote) {
        Objects.requireNonNull(voter);
        Objects.requireNonNull(vote);
        if (owner.equals(voter)) return false;
        Vote old = votes.remove(voter);
        if (old == Vote.LIKE) likes--;
        if (old == Vote.DISLIKE) dislikes--;
        if (old != vote) {
            votes.put(voter, vote);
            if (vote == Vote.LIKE) likes++; else dislikes++;
        }
        revision++;
        return true;
    }

    boolean award(ProfileMedal medal) {
        if (medals.containsKey(medal.id())) return false;
        if (!medal.source().isEmpty() && !rewards.add(medal.source())) return false;
        medals.put(medal.id(), medal);
        if (!notified.containsKey(medal.id())) unannounced.add(medal.id());
        medalRevision++;
        revision++;
        return true;
    }

    boolean hasReward(String source) {
        return rewards.contains(source);
    }

    boolean revoke(UUID medal) {
        if (medals.remove(medal) == null) return false;
        unannounced.remove(medal);
        for (int i = 0; i < layout.length; i++) if (medal.equals(layout[i])) layout[i] = null;
        // История заслуг остаётся: изъятая автоматическая медаль не появляется снова.
        medalRevision++;
        revision++;
        return true;
    }

    void replaceMedals(Collection<ProfileMedal> replacement) {
        Map<UUID, ProfileMedal> next = new LinkedHashMap<>();
        Set<String> sources = new HashSet<>();
        for (ProfileMedal medal : replacement) {
            if (next.putIfAbsent(medal.id(), medal) != null
                    || (!medal.source().isEmpty() && !sources.add(medal.source()))) {
                throw new IllegalArgumentException("Медаль или заслуга указана дважды");
            }
        }
        boolean changed = !medals.equals(next);
        medals.clear(); medals.putAll(next); rewards.addAll(sources);
        rebuildPending();
        for (int i = 0; i < layout.length; i++) if (layout[i] != null && !medals.containsKey(layout[i])) { layout[i] = null; changed = true; }
        if (changed) { medalRevision++; revision++; }
    }

    void restoreHistory(Collection<String> claimed, Map<UUID, Long> delivered) {
        rewards.clear(); rewards.addAll(claimed);
        notified.clear(); notified.putAll(delivered);
        rebuildPending();
    }

    private void rebuildPending() {
        unannounced.clear();
        for (UUID id : medals.keySet()) if (!notified.containsKey(id)) unannounced.add(id);
    }

    boolean hasPendingNotifications() { return !unannounced.isEmpty(); }

    void restoreLayout(UUID[] slots) {
        if (slots.length != 18) throw new IllegalArgumentException("Неверные слоты профиля");
        System.arraycopy(slots, 0, layout, 0, 18);
    }

    boolean needsNotification(ProfileMedal medal) {
        return !notified.containsKey(medal.id());
    }

    void markNotified(ProfileMedal medal) {
        notified.put(medal.id(), medal.awardedAt()); unannounced.remove(medal.id()); revision++;
    }
    void silenceExistingMedals() { medals.values().forEach(this::markNotified); }

    ProfileMedal latestMedal() {
        ProfileMedal latest = null;
        for (ProfileMedal medal : medals.values()) if (latest == null || medal.awardedAt() >= latest.awardedAt()) latest = medal;
        return latest;
    }

    boolean place(UUID actor, UUID medal, int slot) {
        if (!owner.equals(actor) || slot < 0 || slot >= 18 || !medals.containsKey(medal)) return false;
        if (medal.equals(layout[slot])) return false;
        // Единственная копия, перенос, а не клонирование. Заменённая медаль остаётся в medals.
        for (int i = 0; i < layout.length; i++) if (medal.equals(layout[i])) layout[i] = null;
        layout[slot] = medal;
        revision++;
        return true;
    }

    boolean unplace(UUID actor, UUID medal) {
        if (!owner.equals(actor)) return false;
        for (int i = 0; i < layout.length; i++) {
            if (Objects.equals(medal, layout[i]) && medal != null) {
                layout[i] = null;
                revision++;
                return true;
            }
        }
        return false;
    }

    boolean isPlaced(UUID medal) { return medal != null && Arrays.asList(layout).contains(medal); }
}
