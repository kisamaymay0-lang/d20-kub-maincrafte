package com.yourserver.adaptation;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Снимок YAML строится на главном потоке, запись выполняется общим писателем. */
final class BatchedYamlFile {
    private final JavaPlugin plugin;
    private final AsyncTextWriter writer;
    private final Path path;
    private final Supplier<String> snapshot;
    private BukkitTask task;
    private boolean dirty;
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    BatchedYamlFile(JavaPlugin plugin, AsyncTextWriter writer, Path path, Supplier<String> snapshot) {
        this.plugin = plugin;
        this.writer = writer;
        this.path = path;
        this.snapshot = snapshot;
    }

    void markDirty() {
        dirty = true;
        if (task == null && plugin.isEnabled()) {
            task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                task = null;
                flush();
            }, 20L);
        }
    }

    void flush() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!dirty && !lastWrite.isCompletedExceptionally()) {
            return;
        }
        // Ни Supplier, ни FileConfiguration/ItemStack не уходят в фон.
        try {
            String text = snapshot.get();
            dirty = false;
            lastWrite = writer.submit(path, text);
        } catch (RuntimeException ex) {
            dirty = true;
            plugin.getLogger().log(Level.SEVERE, "Не удалось подготовить YAML " + path.getFileName(), ex);
            lastWrite = CompletableFuture.failedFuture(ex);
        }
    }

    void flushBlocking() {
        flush();
        try {
            lastWrite.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Сохранение " + path.getFileName() + " прервано.");
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось дождаться сохранения " + path.getFileName(), ex);
        }
    }
}
