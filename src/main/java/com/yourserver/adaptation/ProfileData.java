package com.yourserver.adaptation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
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
    private ProfileVoiceNote voice;
    private UUID skinOwner;
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
    private long astronomyProgress;
    private final Set<String> ownedPrefixes = new HashSet<>();
    private String equippedPrefix;
    private int prefixCases;
    private final Set<String> ownedCosmetics = new HashSet<>();
    private String equippedCosmetic;
    private int cosmeticCases;
    /**
     * Кейсы паков префиксов: id пака -> список сроков, по одному на кейс
     * (мс от эпохи; {@link Long#MAX_VALUE} — бессрочный). Список отсортирован:
     * первым забирается тот кейс, который сгорит раньше.
     */
    private final Map<String, List<Long>> packCases = new HashMap<>();
    /** То же самое для паков косметики: id пака -> сроки его кейсов. */
    private final Map<String, List<Long>> cosmeticPackCases = new HashMap<>();

    ProfileData(UUID owner, String name) {
        this.owner = Objects.requireNonNull(owner);
        this.skinOwner = owner;
        this.name = Objects.requireNonNullElse(name, owner.toString());
    }

    String name() { return name; }
    String description() { return description; }
    ProfileVoiceNote voice() { return voice; }
    UUID skinOwner() { return skinOwner; }
    String displayedDescription() { return voice != null ? "Голосовое описание · 10 сек." : description.isBlank() ? ProfileText.NO_DESCRIPTION : description; }
    boolean voice(UUID actor, ProfileVoiceNote note) {
        if (!owner.equals(actor) || (note != null && !owner.equals(note.speaker()))) return false;
        voice = note; description = ""; revision++; return true;
    }
    void restoreDescription(String description, ProfileVoiceNote voice) {
        this.description = description; this.voice = voice; revision++;
    }

    ProfileData previewCopy(UUID id) {
        ProfileData copy = new ProfileData(id, name);
        copy.skinOwner = skinOwner;
        copy.description = description; copy.voice = voice;
        copy.votes.putAll(votes); copy.likes = likes; copy.dislikes = dislikes;
        copy.medals.putAll(medals); copy.rewards.addAll(rewards);
        copy.ownedPrefixes.addAll(ownedPrefixes);
        copy.equippedPrefix = equippedPrefix;
        copy.prefixCases = prefixCases;
        copy.ownedCosmetics.addAll(ownedCosmetics);
        copy.equippedCosmetic = equippedCosmetic;
        copy.cosmeticCases = cosmeticCases;
        for (Map.Entry<String, List<Long>> entry : packCases.entrySet()) {
            copy.packCases.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        for (Map.Entry<String, List<Long>> entry : cosmeticPackCases.entrySet()) {
            copy.cosmeticPackCases.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        System.arraycopy(layout, 0, copy.layout, 0, layout.length);
        copy.astronomyProgress = astronomyProgress;
        return copy;
    }
    void newerThan(long previous) { revision = Math.max(revision, previous + 1); }
    int likes() { return likes; }
    int dislikes() { return dislikes; }
    long revision() { return revision; }
    long medalRevision() { return medalRevision; }
    long astronomyProgress() { return astronomyProgress; }
    void astronomyProgress(long value) {
        if (astronomyProgress != value) { astronomyProgress = Math.max(0, value); revision++; }
    }
    boolean ownsReward(String source) {
        return medals.values().stream().anyMatch(medal -> medal.source().equals(source));
    }
    Set<String> rewardHistory() { return Set.copyOf(rewards); }
    boolean ownsPrefix(String id) { return id != null && ownedPrefixes.contains(id); }
    Set<String> ownedPrefixes() { return Set.copyOf(ownedPrefixes); }
    String equippedPrefix() { return equippedPrefix; }
    int prefixCases() { return prefixCases; }
    boolean addPrefix(String id) {
        if (id == null || id.isEmpty() || !ownedPrefixes.add(id)) return false;
        revision++;
        return true;
    }
    /** Забрать префикс; надетый префикс при этом снимается. */
    boolean revokePrefix(String id) {
        if (id == null || !ownedPrefixes.remove(id)) return false;
        if (java.util.Objects.equals(equippedPrefix, id)) equippedPrefix = null;
        revision++;
        return true;
    }
    boolean clearPrefixes() {
        boolean changed = !ownedPrefixes.isEmpty();
        ownedPrefixes.clear();
        if (equippedPrefix != null) { equippedPrefix = null; changed = true; }
        if (changed) revision++;
        return changed;
    }
    boolean equipPrefix(String id) {
        if (id != null && !ownedPrefixes.contains(id)) return false;
        if (java.util.Objects.equals(equippedPrefix, id)) return false;
        equippedPrefix = id;
        revision++;
        return true;
    }
    boolean addPrefixCase() {
        prefixCases++;
        revision++;
        return true;
    }
    boolean takePrefixCase() {
        if (prefixCases <= 0) return false;
        prefixCases--;
        revision++;
        return true;
    }
    /** Только чтение из файла: без пометки изменения, как restoreHistory. */
    void restorePrefixes(Set<String> owned, String equipped, int cases) {
        ownedPrefixes.clear();
        if (owned != null) {
            for (String id : owned) if (id != null && !id.isEmpty()) ownedPrefixes.add(id);
        }
        equippedPrefix = (equipped != null && ownedPrefixes.contains(equipped)) ? equipped : null;
        prefixCases = Math.max(0, cases);
    }

    // ===== КОСМЕТИКА =====
    // Устройство ровно как у префиксов: своя коллекция, одна надетая и свои кейсы.
    // Отличие только в показе: косметика визуально надета на голову игрока.

    boolean ownsCosmetic(String id) { return id != null && ownedCosmetics.contains(id); }
    Set<String> ownedCosmetics() { return Set.copyOf(ownedCosmetics); }
    String equippedCosmetic() { return equippedCosmetic; }
    int cosmeticCases() { return cosmeticCases; }
    boolean addCosmetic(String id) {
        if (id == null || id.isEmpty() || !ownedCosmetics.add(id)) return false;
        revision++;
        return true;
    }
    /** Забрать косметику; надетая косметика при этом снимается. */
    boolean revokeCosmetic(String id) {
        if (id == null || !ownedCosmetics.remove(id)) return false;
        if (java.util.Objects.equals(equippedCosmetic, id)) equippedCosmetic = null;
        revision++;
        return true;
    }
    boolean clearCosmetics() {
        boolean changed = !ownedCosmetics.isEmpty();
        ownedCosmetics.clear();
        if (equippedCosmetic != null) { equippedCosmetic = null; changed = true; }
        if (changed) revision++;
        return changed;
    }
    boolean equipCosmetic(String id) {
        if (id != null && !ownedCosmetics.contains(id)) return false;
        if (java.util.Objects.equals(equippedCosmetic, id)) return false;
        equippedCosmetic = id;
        revision++;
        return true;
    }
    boolean addCosmeticCase() {
        cosmeticCases++;
        revision++;
        return true;
    }
    boolean takeCosmeticCase() {
        if (cosmeticCases <= 0) return false;
        cosmeticCases--;
        revision++;
        return true;
    }
    /** Только чтение из файла: без пометки изменения, как restorePrefixes. */
    void restoreCosmetics(Set<String> owned, String equipped, int cases) {
        ownedCosmetics.clear();
        if (owned != null) {
            for (String id : owned) if (id != null && !id.isEmpty()) ownedCosmetics.add(id);
        }
        equippedCosmetic = (equipped != null && ownedCosmetics.contains(equipped)) ? equipped : null;
        cosmeticCases = Math.max(0, cases);
    }
    // ===== КЕЙСЫ ПАКОВ =====
    // У пака своя стопка кейсов и у каждого кейса свой срок: «время пака в
    // часах» считается с момента выдачи, а не с момента вскрытия. Считается
    // одинаково для паков префиксов и паков косметики — общие методы ниже.

    /** Сколько живых кейсов этого пака префиксов у игрока; сгоревшие выбрасываются. */
    int packCases(String packId) {
        return countCases(packCases, packId);
    }

    /** Ближайший срок сгорания кейса пака префиксов; {@link Long#MAX_VALUE} — бессрочный или кейсов нет. */
    long nearestPackExpiry(String packId) {
        return nearestCase(packCases, packId);
    }

    /** Забрать один кейс пака префиксов — тот, что сгорит раньше. */
    boolean takePackCase(String packId) {
        return takeCase(packCases, packId);
    }

    /** Выдать кейс пака префиксов со сроком {@code expiresAt} ({@link Long#MAX_VALUE} — бессрочный). */
    void addPackCase(String packId, long expiresAt) {
        addCase(packCases, packId, expiresAt);
    }

    /** Убрать сгоревшие кейсы паков префиксов. */
    boolean purgeExpiredPacks(long now) {
        return purgeCases(packCases, now);
    }

    Map<String, List<Long>> packCasesSnapshot() {
        return snapshotCases(packCases);
    }

    /** Только чтение из файла: без пометки изменения, как restorePrefixes. */
    void restorePackCases(Map<String, List<Long>> snapshot) {
        restoreCases(packCases, snapshot);
    }

    /** Сколько живых кейсов этого пака косметики у игрока. */
    int cosmeticPackCases(String packId) {
        return countCases(cosmeticPackCases, packId);
    }

    /** Ближайший срок сгорания кейса пака косметики. */
    long nearestCosmeticPackExpiry(String packId) {
        return nearestCase(cosmeticPackCases, packId);
    }

    /** Забрать один кейс пака косметики — тот, что сгорит раньше. */
    boolean takeCosmeticPackCase(String packId) {
        return takeCase(cosmeticPackCases, packId);
    }

    /** Выдать кейс пака косметики со сроком {@code expiresAt}. */
    void addCosmeticPackCase(String packId, long expiresAt) {
        addCase(cosmeticPackCases, packId, expiresAt);
    }

    /** Убрать сгоревшие кейсы паков косметики. */
    boolean purgeExpiredCosmeticPacks(long now) {
        return purgeCases(cosmeticPackCases, now);
    }

    Map<String, List<Long>> cosmeticPackCasesSnapshot() {
        return snapshotCases(cosmeticPackCases);
    }

    void restoreCosmeticPackCases(Map<String, List<Long>> snapshot) {
        restoreCases(cosmeticPackCases, snapshot);
    }

    private int countCases(Map<String, List<Long>> cases, String packId) {
        if (packId == null) return 0;
        purgeCases(cases, System.currentTimeMillis());
        List<Long> list = cases.get(packId);
        return list == null ? 0 : list.size();
    }

    private long nearestCase(Map<String, List<Long>> cases, String packId) {
        if (packId == null) return Long.MAX_VALUE;
        purgeCases(cases, System.currentTimeMillis());
        List<Long> list = cases.get(packId);
        if (list == null || list.isEmpty()) return Long.MAX_VALUE;
        return list.get(0);
    }

    private boolean takeCase(Map<String, List<Long>> cases, String packId) {
        if (packId == null) return false;
        purgeCases(cases, System.currentTimeMillis());
        List<Long> list = cases.get(packId);
        if (list == null || list.isEmpty()) return false;
        list.remove(0);
        if (list.isEmpty()) cases.remove(packId);
        revision++;
        return true;
    }

    private void addCase(Map<String, List<Long>> cases, String packId, long expiresAt) {
        if (packId == null || packId.isEmpty()) return;
        List<Long> list = cases.computeIfAbsent(packId, key -> new ArrayList<>());
        list.add(expiresAt);
        list.sort(null);
        revision++;
    }

    /** Уборка сгоревших кейсов: это не правка игрока, поэтому ревизию профиля не двигаем. */
    private boolean purgeCases(Map<String, List<Long>> cases, long now) {
        boolean changed = false;
        for (Iterator<Map.Entry<String, List<Long>>> it = cases.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<Long>> entry = it.next();
            List<Long> list = entry.getValue();
            if (list.removeIf(deadline -> deadline != Long.MAX_VALUE && deadline <= now)) changed = true;
            if (list.isEmpty()) { it.remove(); changed = true; }
        }
        return changed;
    }

    private Map<String, List<Long>> snapshotCases(Map<String, List<Long>> cases) {
        Map<String, List<Long>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : cases.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return copy;
    }

    private void restoreCases(Map<String, List<Long>> cases, Map<String, List<Long>> snapshot) {
        cases.clear();
        if (snapshot == null) return;
        for (Map.Entry<String, List<Long>> entry : snapshot.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            List<Long> deadlines = new ArrayList<>();
            for (Long deadline : entry.getValue()) if (deadline != null) deadlines.add(deadline);
            if (deadlines.isEmpty()) continue;
            deadlines.sort(null);
            cases.put(entry.getKey(), deadlines);
        }
    }

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
        if (voice == null && description.equals(cleaned)) return false;
        description = cleaned;
        voice = null;
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
        if (!medal.source().isEmpty()) {
            if (ownsReward(medal.source())) return false;
            rewards.add(medal.source());
        }
        medals.put(medal.id(), medal);
        if (!notified.containsKey(medal.id())) unannounced.add(medal.id());
        medalRevision++;
        revision++;
        return true;
    }

    /**
     * Переписать текст уже выданной медали (id и дата остаются): названия и заслуги
     * берутся из файла настроек, поэтому их правки видны без выдачи медали заново.
     */
    boolean refresh(ProfileMedal medal) {
        ProfileMedal existing = medals.get(medal.id());
        if (existing == null || existing.equals(medal)) return false;
        medals.put(medal.id(), medal);
        medalRevision++;
        revision++;
        return true;
    }

    boolean hasReward(String source) {
        return rewards.contains(source);
    }

    /** Постоянный маркер прогресса (например, съеденный вид бутерброда) без создания медали. */
    boolean markClaimed(String source) {
        if (source.isEmpty() || rewards.contains(source)) return false;
        rewards.add(source);
        revision++;
        return true;
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
