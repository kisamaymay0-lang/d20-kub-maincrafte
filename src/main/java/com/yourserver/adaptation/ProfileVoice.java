package com.yourserver.adaptation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/** Запись по явному согласию: 30 секунд, ограниченная память, никакого дискового I/O в обработчике UDP. */
final class ProfileVoice {
    private static final class Session {
        final UUID clip = UUID.randomUUID();
        final ProfileVoiceBuffer buffer;
        volatile boolean saving;
        Session(UUID owner) { buffer = new ProfileVoiceBuffer(owner, System.nanoTime()); }
    }

    private final JavaPlugin plugin;
    private final Path directory;
    private final BiPredicate<Player, ProfileVoiceNote> commit;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playRequests = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "f8-voice-files"); thread.setDaemon(true); return thread;
    });
    private final BukkitTask timer;
    private YamlConfiguration messages;
    private ProfileVoiceBridge bridge;
    private volatile boolean stopping;

    ProfileVoice(JavaPlugin plugin, BiPredicate<Player, ProfileVoiceNote> commit) {
        this.plugin = plugin; this.commit = commit;
        directory = plugin.getDataFolder().toPath().resolve("voice/records");
        Path config = plugin.getDataFolder().toPath().resolve("voice/config.yml");
        if (!Files.exists(config)) plugin.saveResource("voice/config.yml", false);
        messages = YamlConfiguration.loadConfiguration(config.toFile());
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlasmoVoice")) {
            try {
                bridge = (ProfileVoiceBridge) Class.forName("com.yourserver.adaptation.PlasmoProfileVoice").getConstructor().newInstance();
                bridge.initialize();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Plasmo Voice API недоступен; текстовые профили продолжают работать", ex);
                if (bridge != null) try { bridge.close(); } catch (RuntimeException | LinkageError ignored) { }
                bridge = null;
            }
        }
        timer = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    Component prompt() { return message("prompt"); }
    private Component message(String key) {
        String fallback = "Не удалось выполнить действие с голосовым описанием.";
        return MiniMessage.miniMessage().deserialize(messages.getString("messages." + key, fallback));
    }
    private void say(Player player, String key) { if (player != null && player.isOnline()) player.sendMessage(message(key)); }
    boolean active(UUID owner) { return sessions.containsKey(owner); }

    boolean start(Player player) {
        if (stopping) return false;
        if (sessions.containsKey(player.getUniqueId())) { say(player, "already-recording"); return true; }
        if (sessions.size() >= 4) { say(player, "busy"); return false; }
        try {
            String problem = bridge == null ? "unavailable" : bridge.problem(player.getUniqueId(), true);
            if (problem != null) { say(player, problem); return false; }
            bridge.stop(player.getUniqueId()); playRequests.remove(player.getUniqueId());
            Session session = new Session(player.getUniqueId());
            sessions.put(player.getUniqueId(), session);
            bridge.capture(player.getUniqueId(), session.buffer);
            say(player, "started");
            return true;
        } catch (RuntimeException | LinkageError ex) {
            cancel(player.getUniqueId(), false);
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось начать запись профиля", ex);
            say(player, "start-failed");
            return false;
        }
    }

    void chat(Player player, String message) {
        if (!active(player.getUniqueId())) return;
        String text = ProfileText.clean(message);
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) cancel(player.getUniqueId(), true);
        else say(player, "recording-hint");
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        long now = System.nanoTime();
        for (var entry : sessions.entrySet()) {
            UUID owner = entry.getKey(); Session session = entry.getValue();
            Player player = Bukkit.getPlayer(owner);
            if (player == null) { cancel(owner, false); continue; }
            if (session.saving) continue;
            String problem;
            try { problem = bridge == null ? "unavailable" : bridge.problem(owner, true); }
            catch (RuntimeException | LinkageError ex) { problem = "unavailable"; }
            if (problem != null) { cancel(owner, false); say(player, problem); continue; }
            if (session.buffer.failure() != null) {
                String reason = session.buffer.failure(); cancel(owner, false); say(player, reason); continue;
            }
            if (now - session.buffer.started < 30_000_000_000L) continue;
            if (session.buffer.empty()) { cancel(owner, false); say(player, "no-audio"); continue; }
            session.saving = true;
            ProfileVoiceClip clip;
            try { clip = session.buffer.finish(); }
            catch (RuntimeException ex) { cancel(owner, false); say(player, "save-failed"); continue; }
            // capture остаётся включённым до сообщения о завершении, но буфер уже закрыт.
            io.execute(() -> save(owner, session, clip));
        }
    }

    private void save(UUID owner, Session session, ProfileVoiceClip clip) {
        Path file = path(session.clip);
        try {
            write(file, clip.encode());
            if (stopping || sessions.get(owner) != session) { Files.deleteIfExists(file); return; }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (stopping || sessions.get(owner) != session) { delete(session.clip); return; }
                    Player player = Bukkit.getPlayer(owner);
                    boolean saved = false;
                    try { saved = player != null && commit.test(player, new ProfileVoiceNote(session.clip, owner)); }
                    catch (RuntimeException ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось закрепить голосовой профиль", ex); }
                    sessions.remove(owner, session); bridge.release(owner);
                    if (!saved) delete(session.clip);
                    say(player, saved ? "saved" : "save-failed");
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ex) { Files.deleteIfExists(file); }
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось сохранить голосовой профиль " + owner, ex);
            if (!stopping) try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (sessions.remove(owner, session)) {
                        if (bridge != null) bridge.release(owner);
                        say(Bukkit.getPlayer(owner), "save-failed");
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
        }
    }

    void play(Player player, ProfileVoiceNote note) {
        UUID listener = player.getUniqueId();
        if (active(listener)) { say(player, "recording-hint"); return; }
        if (note == null) { say(player, "missing"); return; }
        try {
            String problem = bridge == null ? "unavailable" : bridge.problem(listener, false);
            if (problem != null) { say(player, problem); return; }
            if (playRequests.remove(listener) != null) {
                bridge.stop(listener); say(player, "stopped"); return;
            }
            UUID request = UUID.randomUUID(); playRequests.put(listener, request);
            io.execute(() -> {
                try {
                    Path file = path(note.clip());
                    if (Files.size(file) > ProfileVoiceClip.MAX_FILE_BYTES) throw new IOException("Запись слишком большая");
                    ProfileVoiceClip clip = ProfileVoiceClip.decode(note.speaker(), Files.readAllBytes(file));
                    if (stopping) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (stopping || !request.equals(playRequests.get(listener)) || !player.isOnline()) return;
                        try {
                            bridge.play(listener, clip, success -> finished(listener, request, success));
                            say(player, "playing");
                        } catch (RuntimeException | LinkageError ex) { finished(listener, request, false); }
                    });
                } catch (Exception ex) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось прочитать голосовую запись " + note.clip(), ex);
                    finished(listener, request, false);
                }
            });
        } catch (RuntimeException | LinkageError ex) { playRequests.remove(listener); say(player, "play-failed"); }
    }

    private void finished(UUID listener, UUID request, boolean success) {
        if (stopping) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (playRequests.remove(listener, request)) say(Bukkit.getPlayer(listener), success ? "finished" : "play-failed");
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
    }

    void cancel(UUID owner, boolean notify) {
        Session session = sessions.remove(owner);
        if (session == null) return;
        session.buffer.cancel();
        if (bridge != null) bridge.release(owner);
        if (notify) say(Bukkit.getPlayer(owner), "cancelled");
    }

    void quit(UUID player) {
        cancel(player, false); playRequests.remove(player);
        if (bridge != null) bridge.stop(player);
    }
    void discard(ProfileVoiceNote note) { if (note != null) delete(note.clip()); }
    private void delete(UUID id) {
        if (io.isShutdown()) return;
        io.execute(() -> { try { Files.deleteIfExists(path(id)); } catch (IOException ex) { plugin.getLogger().warning("Не удалось удалить старую голосовую запись " + id); } });
    }
    private Path path(UUID id) { return directory.resolve(id + ".f8voice"); }

    private static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".recording-", ".tmp");
        try {
            Files.write(temporary, data);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file); }
        } finally { Files.deleteIfExists(temporary); }
    }

    void disable() {
        stopping = true; timer.cancel();
        for (UUID owner : sessions.keySet()) { say(Bukkit.getPlayer(owner), "cancelled"); cancel(owner, false); }
        playRequests.clear();
        if (bridge != null) try { bridge.close(); } catch (RuntimeException | LinkageError ex) { plugin.getLogger().warning("Ошибка отключения голосового API"); }
        io.shutdown();
        try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) plugin.getLogger().warning("Голосовые файлы ещё записываются; проверьте диск"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
