package com.yourserver.adaptation;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Раздаёт актуальный ресурспак прямо с игрового сервера: встроенный в jar
 * архив отдаётся по HTTP на порту resource-pack-port, а при входе клиент
 * получает addResourcePack со ссылкой на тот адрес, которым он подключился.
 *
 * Серверный пак применяется клиентом поверх локальных паков, поэтому старый
 * блокстейт note_block из личного пака игрока не перекрывает модели кувшина
 * и наполненный кувшин не выглядит нотным блоком.
 */
public class ResourcePackPusher implements Listener {

    private static final String PACK_PATH = "/f8resurs-resourcepack.zip";

    private static final UUID PACK_ID =
            UUID.nameUUIDFromBytes(
                    "f8resurs-resourcepack".getBytes(StandardCharsets.UTF_8)
            );

    private final JavaPlugin plugin;
    private final byte[] packBytes;
    private final byte[] sha1;
    private final int port;
    private HttpServer server;

    public ResourcePackPusher(JavaPlugin plugin) {
        this.plugin = plugin;
        this.packBytes = readBundledPack();
        this.sha1 = computeSha1(packBytes);
        this.port = plugin.getConfig().getInt("resource-pack-port", 25566);

        if (packBytes == null || sha1 == null) {
            plugin.getLogger().warning(
                    "Ресурспак не встроен в jar — клиентам он раздаваться не будет."
            );
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext(PACK_PATH, exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, packBytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(packBytes);
                }
            });
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            plugin.getLogger().info(
                    "Ресурспак раздаётся по HTTP на порту " + port +
                    ", путь " + PACK_PATH
            );
        } catch (Exception e) {
            server = null;
            plugin.getLogger().warning(
                    "Не удалось поднять HTTP-сервер ресурспака на порту " +
                    port + ": " + e
            );
        }
    }

    private byte[] readBundledPack() {
        try (InputStream in = plugin.getResource("f8resurs-resourcepack.zip")) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "Не удалось прочитать встроенный ресурспак: " + e
            );
            return null;
        }
    }

    private byte[] computeSha1(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось посчитать SHA-1: " + e);
            return null;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (server == null || sha1 == null) {
            return;
        }

        Player player = event.getPlayer();

        if (player.getAddress() == null ||
                player.getAddress().getAddress() == null) {
            return;
        }

        // Тот адрес, которым клиент подключился к серверу, — им же он
        // сможет скачать пак (локалка/LAN/белый адрес).
        String host = player.getAddress().getAddress().getHostAddress();
        String url = "http://" + host + ":" + port + PACK_PATH;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    player.addResourcePack(
                            PACK_ID,
                            url,
                            sha1,
                            "Ресурспак F8: кувшины, изморозь и предметы",
                            false
                    );
                } catch (Throwable t) {
                    plugin.getLogger().warning(
                            "Не удалось предложить ресурспак игроку " +
                            player.getName() + ": " + t
                    );
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    public void disable() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
