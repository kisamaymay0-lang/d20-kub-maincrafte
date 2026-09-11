package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Предлагает игрокам скачать ресурспак F8 по обычной ссылке.
 *
 * Свой HTTP-сервер не поднимается: ссылка на zip задаётся в config.yml
 * (resource-pack.url) и должна отдавать сам файл, а не страницу просмотра.
 * SHA-1 считается по этому файлу при запуске сервера, поэтому пак проверен
 * и не скачивается заново на каждый вход.
 *
 * Если ссылка недоступна (например, релиз ещё не опубликован), пак просто не
 * предлагается: игроки подключаются, сервер работает как обычно, а попытки
 * получить файл повторяются в фоне.
 */
public class ResourcePackPusher implements Listener {

    /** Ссылка по умолчанию: zip из последнего релиза репозитория. */
    public static final String DEFAULT_URL =
            "https://github.com/kisamaymay0-lang/d20-kub-maincrafte/releases/latest/download/f8resurs-resourcepack.zip";

    private static final String DEFAULT_PROMPT = "Ресурспак F8: кувшины, изморозь и предметы";
    private static final long RETRY_TICKS = 20L * 60L * 5L;   // повтор раз в 5 минут
    private static final long JOIN_DELAY_TICKS = 20L;         // пауза после входа
    private static final int MAX_PACK_BYTES = 16 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final JavaPlugin plugin;
    private final String url;
    private final String prompt;
    private final UUID packId;
    private final AtomicBoolean fetchRunning = new AtomicBoolean(false);

    private volatile byte[] sha1;
    private volatile boolean disabled;
    private volatile int failures;
    private BukkitTask retryTask;

    public ResourcePackPusher(JavaPlugin plugin) {
        this.plugin = plugin;
        String configured = plugin.getConfig().getString("resource-pack.url", DEFAULT_URL);
        this.url = configured == null ? "" : configured.trim();
        String configuredPrompt = plugin.getConfig().getString("resource-pack.prompt", DEFAULT_PROMPT);
        this.prompt = configuredPrompt == null ? "" : configuredPrompt;
        this.packId = UUID.nameUUIDFromBytes(("f8resurs:" + url).getBytes(StandardCharsets.UTF_8));

        if (url.isEmpty()) {
            plugin.getLogger().info(
                    "Раздача ресурспака выключена (resource-pack.url пуст). "
                            + "Игроки ставят f8resurs-resourcepack.zip вручную."
            );
            return;
        }
        if (!url.startsWith("https://")) {
            plugin.getLogger().warning(
                    "resource-pack.url не начинается с https:// — клиенты Minecraft "
                            + "часто отказываются скачивать такой пак."
            );
        }
        fetchHash();
    }

    /** Загружает пак и считает SHA-1; при неудаче повторяет попытку позже. */
    private void fetchHash() {
        if (disabled || sha1 != null || !fetchRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::downloadIntoHash);
        } catch (Throwable error) {
            fetchRunning.set(false);
        }
    }

    private void downloadIntoHash() {
        byte[] digest = null;
        try {
            digest = downloadDigest();
        } catch (Throwable error) {
            reportFailure("Не удалось получить ресурспак по ссылке " + url, error);
        } finally {
            fetchRunning.set(false);
            if (digest != null) {
                sha1 = digest;
                failures = 0;
                plugin.getLogger().info(
                        "Ресурспак готов к раздаче: " + url + " (SHA-1 " + hex(digest) + ")"
                );
            } else {
                scheduleRetry();
            }
        }
    }

    private void scheduleRetry() {
        if (disabled || sha1 != null) {
            return;
        }
        try {
            retryTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                retryTask = null;
                fetchHash();
            }, RETRY_TICKS);
        } catch (Throwable ignored) {
            // Плагин выключается — повтор больше не нужен.
        }
    }

    private byte[] downloadDigest() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            reportFailure("Ссылка на ресурспак ответила кодом " + response.statusCode()
                    + " (проверьте, что адрес отдаёт сам zip-файл)", null);
            return null;
        }
        byte[] data = response.body();
        if (data == null || data.length == 0) {
            reportFailure("По ссылке на ресурспак пришёл пустой ответ", null);
            return null;
        }
        if (data.length > MAX_PACK_BYTES) {
            reportFailure("Файл по ссылке больше " + (MAX_PACK_BYTES / 1024 / 1024)
                    + " МБ — это не ресурспак F8, раздача пропущена", null);
            return null;
        }
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    /** Первая ошибка пишется целиком, дальше — редко, чтобы не засорять лог. */
    private void reportFailure(String message, Throwable error) {
        failures++;
        if (failures != 1 && failures % 6 != 0) {
            return;
        }
        if (error == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().warning(message + ": " + error);
        }
        plugin.getLogger().warning(
                "Игроки смогут поставить пак вручную: f8resurs-resourcepack.zip "
                        + "из релиза → .minecraft/resourcepacks."
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (disabled || url.isEmpty()) {
            return;
        }
        byte[] digest = sha1;
        if (digest == null) {
            return;   // пак ещё не получен — вход не задерживаем
        }
        Player player = event.getPlayer();
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    player.addResourcePack(packId, url, digest, prompt, false);
                } catch (Throwable error) {
                    plugin.getLogger().warning(
                            "Не удалось предложить ресурспак игроку "
                                    + player.getName() + ": " + error
                    );
                }
            }, JOIN_DELAY_TICKS);
        } catch (Throwable ignored) {
            // Плагин выключается — предлагать пак больше некому.
        }
    }

    private static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    public void disable() {
        disabled = true;
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
    }
}
