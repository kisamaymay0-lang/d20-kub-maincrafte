package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.JukeboxPlayable;
import org.bukkit.JukeboxSong;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;

/** Читает настоящую песню предмета, а не неполный список Material/длительностей. */
final class DiscTracks {

    record Track(NamespacedKey key, Sound sound, long durationTicks) {
    }

    private DiscTracks() {
    }

    private static JukeboxSong song(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return null;
        }
        // getData включает ванильные компоненты по умолчанию, в отличие
        // от проверки только явно записанного ItemMeta. Учитывает и замену
        // песни датапаком, и явное удаление jukebox_playable у предмета.
        JukeboxPlayable playable = item.getData(DataComponentTypes.JUKEBOX_PLAYABLE);
        return playable == null ? null : playable.jukeboxSong();
    }

    static NamespacedKey songKey(ItemStack item) {
        JukeboxSong song = song(item);
        return song == null ? null : song.getKey();
    }

    static Track resolve(ItemStack item) {
        JukeboxSong song = song(item);
        if (song == null) {
            return null;
        }
        long ticks = durationTicks(song.getLengthInSeconds());
        return ticks > 0 ? new Track(song.getKey(), song.getSound(), ticks) : null;
    }

    static long durationTicks(float seconds) {
        if (!Float.isFinite(seconds) || seconds <= 0f) {
            return 0L;
        }
        // Округляем вверх: дробная длительность не должна обрезать конец песни.
        return Math.max(1L, (long) Math.ceil(seconds * 20.0D));
    }
}
