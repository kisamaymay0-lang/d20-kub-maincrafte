package com.yourserver.adaptation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Оценки/описание/витрина отдельно от редактируемых файлов выданных медалей. */
final class ProfileStorage {
    record MedalSnapshot(String text, List<ProfileMedal> medals) { }
    record ReloadPlan(Map<UUID, MedalSnapshot> players) { }

    private static final class Cached {
        final ProfileData data;
        boolean dirty;
        CompletableFuture<Void> write;
        long retryAfter;
        String medalText;
        long savedMedalRevision;
        boolean medalConflict;
        Set<String> safeRewards;
        Map<UUID, Long> safeNotices;
        List<ProfileMedal> safeMedals;
        UUID[] safeLayout;
        Cached(ProfileData data) { this.data = data; }
    }

    private final Path directory;
    private final Path medalDirectory;
    private final AsyncTextWriter writer;
    private final Logger logger;
    private final UnaryOperator<ProfileMedal> legacyMedal;
    private final Map<UUID, Cached> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<UUID> pinned = new HashSet<>();
    private final Set<UUID> unreadable = new HashSet<>();

    ProfileStorage(Path directory, AsyncTextWriter writer, Logger logger) {
        this(directory, directory.resolve("medals"), writer, logger, UnaryOperator.identity());
    }

    ProfileStorage(Path directory, Path medals, AsyncTextWriter writer, Logger logger, UnaryOperator<ProfileMedal> legacyMedal) {
        this.directory = directory; this.medalDirectory = medals;
        this.writer = writer; this.logger = logger; this.legacyMedal = legacyMedal;
    }

    ProfileData get(UUID owner, String name) {
        Cached current = cache.get(owner);
        if (current != null) return current.data;
        if (unreadable.contains(owner)) throw new IllegalStateException("Профиль недоступен");
        Path path = path(owner);
        try {
            String original = Files.exists(path) ? Files.readString(path) : null;
            ProfileCodec.Decoded decoded = original == null ? new ProfileCodec.Decoded(new ProfileData(owner, name), false)
                    : ProfileCodec.decodeState(owner, original);
            ProfileData data = decoded.data();
            Cached state = new Cached(data);
            if (decoded.legacy()) {
                Path backup = directory.resolve("legacy-backup").resolve(owner + ".yml");
                if (!Files.exists(backup)) writer.submitIfUnchanged(backup, original, null).get(10, TimeUnit.SECONDS);
                data.silenceExistingMedals(); // Переезд данных 9.3 — не новая выдача.
            }
            MedalSnapshot disk = readMedals(owner);
            if (disk.text() != null) {
                data.replaceMedals(disk.medals());
                state.medalText = disk.text();
            } else if (decoded.legacy() || original == null) {
                if (decoded.legacy()) data.replaceMedals(data.medals().values().stream().map(legacyMedal).toList());
                String text = ProfileCodec.encodeMedals(data);
                // Сначала медали и резервная копия, только потом профиль v2 без вложенных медалей.
                writer.submitIfUnchanged(medalPath(owner), text, null).get(10, TimeUnit.SECONDS);
                state.medalText = text;
            } else {
                data.replaceMedals(List.of()); // Удалённый отдельный файл означает изъятие, а не восстановление из кэша.
            }
            state.savedMedalRevision = data.medalRevision();
            state.safeRewards = data.rewardHistory();
            state.safeNotices = data.notificationHistory();
            state.safeMedals = List.copyOf(data.medals().values());
            state.safeLayout = data.layout();
            state.dirty = true; // Закрепить внешние заслуги/очистившиеся слоты в истории профиля.
            cache.put(owner, state);
            return data;
        } catch (Exception ex) {
            unreadable.add(owner);
            logger.log(Level.SEVERE, "Не удалось прочитать/перенести профиль " + owner + "; исходные данные сохранены", ex);
            throw new IllegalStateException("Профиль недоступен", ex);
        }
    }

    private Path path(UUID owner) { return directory.resolve(owner + ".yml"); }
    Path medalPath(UUID owner) { return medalDirectory.resolve(owner + ".yml"); }
    void pin(UUID owner) { pinned.add(owner); }
    void unpin(UUID owner) { pinned.remove(owner); }
    void changed(UUID owner) { Cached data = cache.get(owner); if (data != null) data.dirty = true; }

    private MedalSnapshot readMedals(UUID owner) throws Exception {
        Path path = medalPath(owner);
        if (!Files.exists(path)) return new MedalSnapshot(null, List.of());
        String text = Files.readString(path);
        return new MedalSnapshot(text, ProfileCodec.decodeMedals(owner, text));
    }

    /** Только перед редкой выдачей/изъятием, никогда не в цикле карточек. */
    void prepareMedalChange(UUID owner) {
        Cached state = cache.get(owner);
        if (state == null) return;
        if (state.medalConflict) throw new IllegalStateException("Сначала исправьте файл и выполните /profile medal reload");
        try {
            MedalSnapshot disk = readMedals(owner);
            if (!java.util.Objects.equals(disk.text(), state.medalText)) adopt(state, disk);
            state.safeLayout = state.data.layout();
            state.safeRewards = state.data.rewardHistory();
            state.safeNotices = state.data.notificationHistory();
            state.safeMedals = List.copyOf(state.data.medals().values());
        } catch (Exception ex) { throw new IllegalStateException("Проверьте файл медалей и выполните /profile medal reload", ex); }
    }

    ReloadPlan readReloadPlan() {
        Map<UUID, MedalSnapshot> next = new HashMap<>();
        try {
            if (Files.exists(medalDirectory)) {
                try (var files = Files.list(medalDirectory)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                        String name = file.getFileName().toString();
                        UUID owner = UUID.fromString(name.substring(0, name.length() - 4));
                        next.put(owner, readMedals(owner));
                    }
                }
            }
            for (UUID owner : cache.keySet()) next.putIfAbsent(owner, new MedalSnapshot(null, List.of()));
            return new ReloadPlan(Map.copyOf(next));
        } catch (Exception ex) { throw new IllegalStateException("Файл медалей содержит ошибку; действующие данные не изменены", ex); }
    }

    void applyReloadPlan(ReloadPlan plan) {
        for (var entry : cache.entrySet()) {
            MedalSnapshot next = plan.players().get(entry.getKey());
            if (next != null) adopt(entry.getValue(), next);
        }
        unreadable.clear();
    }

    private void adopt(Cached state, MedalSnapshot next) {
        if (state.medalConflict) state.data.restoreHistory(state.safeRewards, state.safeNotices);
        state.data.replaceMedals(next.medals());
        state.medalText = next.text();
        state.savedMedalRevision = state.data.medalRevision();
        state.safeRewards = state.data.rewardHistory();
        state.safeNotices = state.data.notificationHistory();
        state.safeMedals = List.copyOf(state.data.medals().values());
        state.safeLayout = state.data.layout();
        state.medalConflict = false;
        state.dirty = true; // Сохранить историю и очистившиеся места витрины, НЕ переписать отредактированные медали.
    }

    private boolean saveMedals(UUID owner, Cached state) {
        if (state.medalConflict) return false;
        if (state.savedMedalRevision == state.data.medalRevision()) return true;
        try {
            String snapshot = ProfileCodec.encodeMedals(state.data);
            writer.submitIfUnchanged(medalPath(owner), snapshot, state.medalText).get(10, TimeUnit.SECONDS);
            state.medalText = snapshot;
            state.savedMedalRevision = state.data.medalRevision();
            state.safeRewards = state.data.rewardHistory();
            state.safeMedals = List.copyOf(state.data.medals().values());
            state.safeLayout = state.data.layout();
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            state.data.restoreHistory(state.safeRewards, state.safeNotices);
            state.data.replaceMedals(state.safeMedals);
            state.data.restoreLayout(state.safeLayout);
            state.medalConflict = true; // Не повторять старую запись поверх правки администратора.
            logger.log(Level.SEVERE, "Медали не записаны. Проверьте диск/файл и примените /profile medal reload: " + owner, ex);
            return false;
        }
    }

    void flush() {
        long now = System.nanoTime();
        for (var entry : cache.entrySet()) flush(entry.getKey(), entry.getValue(), now, false);
        if (cache.size() <= 512) return;
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext() && cache.size() > 512) {
            var entry = iterator.next(); Cached data = entry.getValue();
            if (!pinned.contains(entry.getKey()) && !data.dirty && !data.medalConflict
                    && (data.write == null || (data.write.isDone() && !data.write.isCompletedExceptionally()))) iterator.remove();
        }
    }

    private void flush(UUID owner, Cached data, long now, boolean force) {
        if (!saveMedals(owner, data)) return;
        if (data.write != null && data.write.isCompletedExceptionally()) {
            data.dirty = true; data.write = null; data.retryAfter = now + TimeUnit.SECONDS.toNanos(5);
        }
        if (!data.dirty || (!force && data.retryAfter != 0 && now - data.retryAfter < 0)) return;
        data.write = writer.submit(path(owner), ProfileCodec.encodeProfile(data.data));
        data.dirty = false;
    }

    boolean flushBlocking(UUID owner) {
        Cached data = cache.get(owner);
        if (data == null || !saveMedals(owner, data)) return false;
        flush(owner, data, System.nanoTime(), true);
        if (data.write == null) return true;
        try {
            data.write.get(10, TimeUnit.SECONDS);
            data.safeRewards = data.data.rewardHistory(); data.safeNotices = data.data.notificationHistory();
            data.safeLayout = data.data.layout();
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Не удалось дождаться записи профиля " + owner, ex);
            return false;
        }
    }

    void shutdown() {
        for (var entry : cache.entrySet()) flush(entry.getKey(), entry.getValue(), System.nanoTime(), true);
        pinned.clear();
    }
}
