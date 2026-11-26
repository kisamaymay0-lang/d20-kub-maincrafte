package com.yourserver.adaptation;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.EntityEffect;

/**
 * Анимация получения медали: <b>настоящая анимация тотема</b> — ту же показывает
 * клиент, когда срабатывает тотем бессмертия. Ничего своего не рисуем: клиент берёт
 * картинку тотема из ресурспака, а там вместо тотема лежит медаль
 * ({@code assets/minecraft/items/totem_of_undying.json} → {@code f8resurs:item/medal_gold}).
 *
 * <p>Эффект отправляется только получателю медали, поэтому остальные игроки ничего
 * не видят. Клиент проигрывает и звук; дополнительный звук тотема — на случай
 * серверов, где событие сущности не доходит до звука.
 */
final class MedalAnimation {

    private final Plugin plugin;

    MedalAnimation(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Показать анимацию тотема получателю медали. */
    void play(Player player) {
        if (player == null || plugin == null || !plugin.isEnabled()) return;
        try {
            player.sendEntityEffect(EntityEffect.TOTEM_RESURRECT, player);
        } catch (RuntimeException ex) {
            // Сервер не знает такого события — медаль всё равно выдана, просто без анимации.
            return;
        }
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
    }

    /** Досрочно остановить анимацию нечего: её целиком рисует клиент. */
    void stop(Player player) {
        // намеренно пусто
    }

    /** Выключение плагина: после плагина не остаётся сущностей. */
    void disable() {
        // намеренно пусто
    }
}
