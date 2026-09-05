package com.yourserver.adaptation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncTextWriterTest {
    @TempDir Path directory;

    private static Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    @Test
    void savesUtf8AtomicallyAndFlushesBothFilesBeforeClosing() throws Exception {
        Path blocks = directory.resolve("nested/blocks.yml");
        Path players = directory.resolve("playerdata.yml");
        try (AsyncTextWriter writer = new AsyncTextWriter(quietLogger())) {
            writer.submit(blocks, "блоки: медь\n");
            writer.submit(players, "звёзды: прогресс\n");
        }
        assertEquals("блоки: медь\n", Files.readString(blocks));
        assertEquals("звёзды: прогресс\n", Files.readString(players));
        try (var files = Files.list(blocks.getParent())) {
            assertEquals(List.of(blocks), files.toList()); // Не оставляем временные файлы.
        }
    }

    @Test
    void coalescesAQueueOfObsoleteSnapshotsAndNeverOverwritesNewDataWithOld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> writes = new ArrayList<>();
        Path file = directory.resolve("blocks.yml");
        AsyncTextWriter writer = new AsyncTextWriter(quietLogger(), (path, text) -> {
            if (text.equals("first")) {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test barrier timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
            }
            writes.add(text);
            AsyncTextWriter.writeAtomically(path, text);
        });
        try {
            writer.submit(file, "first");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            CompletableFuture<Void> pending = writer.submit(file, "0");
            for (int i = 1; i < 100; i++) assertSame(pending, writer.submit(file, String.valueOf(i)));
            release.countDown();
            pending.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            writer.close();
        }
        assertEquals(List.of("first", "99"), writes);
        assertEquals("99", Files.readString(file));
    }

    @Test
    void failureIsReportedWithoutStoppingOtherWrites() throws Exception {
        try (AsyncTextWriter writer = new AsyncTextWriter(quietLogger(), (path, text) -> {
            if (text.equals("fail")) throw new IOException("Test failure");
            AsyncTextWriter.writeAtomically(path, text);
        })) {
            var failed = writer.submit(directory.resolve("bad.yml"), "fail");
            var saved = writer.submit(directory.resolve("good.yml"), "ok");
            assertThrows(ExecutionException.class, () -> failed.get(3, TimeUnit.SECONDS));
            saved.get(3, TimeUnit.SECONDS);
            assertEquals("ok", Files.readString(directory.resolve("good.yml")));
        }
    }

    @Test
    void closedWriterDoesNotStartMoreTasks() {
        AsyncTextWriter writer = new AsyncTextWriter(quietLogger());
        writer.close();
        assertTrue(writer.submit(directory.resolve("late.yml"), "no").isCompletedExceptionally());
    }
}
