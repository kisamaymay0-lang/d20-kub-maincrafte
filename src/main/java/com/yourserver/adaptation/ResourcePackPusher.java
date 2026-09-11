package com.yourserver.adaptation;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * При входе предлагает клиенту актуальный ресурспак (публичная ссылка на
 * архив в репозитории). Серверный пак применяется клиентом поверх локальных
 * паков, поэтому старый блокстейт note_block из личного пака игрока не
 * перекрывает модели кувшина и наполненный кувшин не выглядит нотным блоком.
 */
public class ResourcePackPusher implements Listener {

    private static final String PACK_URL =
            "https://raw.githubusercontent.com/kisamaymay0-lang/d20-kub-maincrafte/" +
            "arena/01a086a2-d20-kub-maincrafte/f8resurs-resourcepack.zip";

    private static final UUID PACK_ID =
            UUID.nameUUIDFromBytes(
                    "f8resurs-resourcepack".getBytes(StandardCharsets.UTF_8)
            );

    private final JavaPlugin plugin;
    private final byte[] sha1;

    public ResourcePackPusher(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sha1 = computeSha1();
        if (sha1 == null) {
            plugin.getLogger().warning(
                    "Ресурспак не встроен в jar — клиентам он раздаваться не будет."
            );
        }
    }

    private byte[] computeSha1() {
        try (InputStream in = plugin.getResource("f8resurs-resourcepack.zip")) {
            if (in == null) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "Не удалось посчитать SHA-1 ресурспака: " + e
            );
            return null;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (sha1 == null) {
            return;
        }

        Player player = event.getPlayer();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    player.addResourcePack(
                            PACK_ID,
                            PACK_URL,
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
}
