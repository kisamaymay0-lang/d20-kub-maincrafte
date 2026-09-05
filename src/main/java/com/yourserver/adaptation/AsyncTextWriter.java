package com.yourserver.adaptation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Один последовательный писатель. В фоне только Path/String, никогда не Bukkit-объекты. */
final class AsyncTextWriter implements AutoCloseable {
    @FunctionalInterface
    interface Sink {
        void write(Path path, String text) throws IOException;
    }

    private static final class Pending {
        String text;
        boolean checked;
        String expected;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Pending(String text) { this.text = text; }
    }

    private final Logger logger;
    private final Sink sink;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "f8-data-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Path, Pending> pending = new LinkedHashMap<>();
    private boolean draining;
    private boolean closed;

    AsyncTextWriter(Logger logger) { this(logger, AsyncTextWriter::writeAtomically); }

    AsyncTextWriter(Logger logger, Sink sink) {
        this.logger = logger;
        this.sink = sink;
    }

    synchronized CompletableFuture<Void> submit(Path path, String text) {
        return submit(path, text, false, null);
    }

    synchronized CompletableFuture<Void> submitIfUnchanged(Path path, String text, String expected) {
        return submit(path, text, true, expected);
    }

    private synchronized CompletableFuture<Void> submit(Path path, String text, boolean checked, String expected) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Writer is closed"));
        }
        Path key = path.toAbsolutePath().normalize();
        Pending job = pending.computeIfAbsent(key, ignored -> {
            Pending created = new Pending(text);
            created.checked = checked;
            created.expected = expected;
            return created;
        });
        job.text = text; // Не копим очередь устаревших снимков одного файла.
        if (!draining) {
            draining = true;
            executor.execute(this::drain);
        }
        return job.done;
    }

    private void drain() {
        while (true) {
            Path path;
            Pending job;
            synchronized (this) {
                if (pending.isEmpty()) {
                    draining = false;
                    return;
                }
                var first = pending.entrySet().iterator().next();
                path = first.getKey();
                job = first.getValue();
                pending.remove(path);
            }
            try {
                if (job.checked) {
                    String actual = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
                    if (!Objects.equals(actual, job.expected)) {
                        throw new IOException("Файл изменён снаружи и не перезаписан: " + path.getFileName()
                                + ". Примените правку через /profile medal reload.");
                    }
                }
                sink.write(path, job.text);
                job.done.complete(null);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Не удалось сохранить " + path.getFileName(), ex);
                job.done.completeExceptionally(ex);
            }
        }
    }

    static void writeAtomically(Path path, String text) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".f8-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() {
        synchronized (this) { closed = true; }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.severe("Запись данных f8 занимает больше 10 секунд; проверьте диск. Писатель завершит очередь в фоне.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warning("Ожидание сохранения данных f8 прервано.");
        }
    }
}
