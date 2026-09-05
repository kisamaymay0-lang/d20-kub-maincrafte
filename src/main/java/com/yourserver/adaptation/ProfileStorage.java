package com.yourserver.adaptation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Один небольшой файл на UUID, ограниченный LRU-кэш офлайн-профилей, общий писатель 9.2. */
final class ProfileStorage {
    private static final class Cached {
        final ProfileData data;
        boolean dirty;
        CompletableFuture<Void> write;
        long retryAfter;
        Cached(ProfileData data, boolean dirty) { this.data = data; this.dirty = dirty; }
    }

    private final Path directory;
    private final AsyncTextWriter writer;
    private final Logger logger;
    private final Map<UUID, Cached> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<UUID> pinned = new HashSet<>();
    private final Set<UUID> unreadable = new HashSet<>();

    ProfileStorage(Path directory, AsyncTextWriter writer, Logger logger) {
        this.directory = directory;
        this.writer = writer;
        this.logger = logger;
    }

    ProfileData get(UUID owner, String name) {
        Cached current = cache.get(owner);
        if (current != null) return current.data;
        if (unreadable.contains(owner)) throw new IllegalStateException("Профиль недоступен");
        Path path = path(owner);
        boolean exists = Files.exists(path);
        try {
            ProfileData data = exists ? ProfileCodec.decode(owner, Files.readString(path)) : new ProfileData(owner, name);
            cache.put(owner, new Cached(data, !exists));
            return data;
        } catch (Exception ex) {
            unreadable.add(owner);
            // Повреждённый файл не заменяется пустым профилем.
            logger.log(Level.SEVERE, "Не удалось прочитать профиль " + owner + "; исходный файл оставлен без изменений", ex);
            throw new IllegalStateException("Профиль недоступен", ex);
        }
    }

    private Path path(UUID owner) { return directory.resolve(owner + ".yml"); }
    void pin(UUID owner) { pinned.add(owner); }
    void unpin(UUID owner) { pinned.remove(owner); }
    void changed(UUID owner) {
        Cached data = cache.get(owner);
        if (data != null) data.dirty = true;
    }

    void flush() {
        long now = System.nanoTime();
        for (var entry : cache.entrySet()) flush(entry.getKey(), entry.getValue(), now, false);
        if (cache.size() <= 512) return;
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext() && cache.size() > 512) {
            var entry = iterator.next();
            Cached data = entry.getValue();
            if (!pinned.contains(entry.getKey()) && !data.dirty
                    && (data.write == null || (data.write.isDone() && !data.write.isCompletedExceptionally()))) iterator.remove();
        }
    }

    private void flush(UUID owner, Cached data, long now, boolean force) {
        if (data.write != null && data.write.isCompletedExceptionally()) {
            data.dirty = true;
            data.write = null;
            data.retryAfter = now + TimeUnit.SECONDS.toNanos(5);
        }
        if (!data.dirty || (!force && data.retryAfter != 0 && now - data.retryAfter < 0)) return;
        String snapshot = ProfileCodec.encode(data.data); // Только вызывающий main thread, без Bukkit в фоне.
        data.write = writer.submit(path(owner), snapshot);
        data.dirty = false;
    }

    void flushBlocking(UUID owner) {
        Cached data = cache.get(owner);
        if (data == null) return;
        flush(owner, data, System.nanoTime(), true);
        if (data.write == null) return;
        try {
            data.write.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warning("Ожидание записи профиля прервано: " + owner);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Не удалось дождаться записи профиля " + owner, ex);
        }
    }

    void shutdown() {
        for (var entry : cache.entrySet()) flush(entry.getKey(), entry.getValue(), System.nanoTime(), true);
        pinned.clear();
        // Сам writer закрывает владелец плагина ПОСЛЕ всех подсистем.
    }
}
