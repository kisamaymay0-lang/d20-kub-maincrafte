package com.yourserver.adaptation;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.Event;

/**
 * Шифт + ПКМ бутылкой с водой — это полив, а не питьё.
 *
 * <h2>В чём была беда</h2>
 *
 * Ваниль запускает питьё сама: блок правого клика действие не съел (поливать он
 * не умеет), и предмет пошёл в рот — вода исчезала, а блок оставался прежним.
 * Отмена {@link PlayerInteractEvent} питью не мешает: оно решается позже, по
 * окончании анимации, событием {@link PlayerItemConsumeEvent}. Поэтому жест
 * запоминается здесь, а выпивание отменяется на полтора секунды позже.
 *
 * <h2>Что остаётся ванильным</h2>
 *
 * Пить воду можно по-прежнему — обычным кликом (в том числе по блоку), просто
 * без шифта: любой такой клик снимает отметку раньше, чем вода дойдёт до рта.
 * Отметка одна на игрока и живёт {@value WaterBottle#NO_DRINK_MILLIS} мс, так
 * что «залипнуть» она не может.
 *
 * <h2>Почему не отменяем само использование предмета</h2>
 *
 * Мы отменяем интеракцию и ставим {@code useItemInHand = DENY}, но разные
 * сборки сервера относятся к этому флагу по-разному: где-то питьё даже не
 * начинается. Тогда отметка просто не понадобится, а лишний
 * {@link PlayerItemConsumeEvent} не придёт вовсе — вреда от неё нет.
 */
final class WaterBottleUse implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void remember(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        Player player = event.getPlayer();
        // Воду носим только в той руке, которой кликнули: чужая рука отметку не снимает.
        if (!WaterBottle.isWaterBottle(event.getItem())) return;

        if (action == Action.RIGHT_CLICK_BLOCK && player.isSneaking()) {
            WaterBottle.preventDrinking(player);
            event.setUseItemInHand(Event.Result.DENY);
        } else {
            WaterBottle.allowDrinking(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void swallow(PlayerItemConsumeEvent event) {
        if (!WaterBottle.isWaterBottle(event.getItem())) return;
        if (!WaterBottle.drinkingPrevented(event.getPlayer())) return;
        event.setCancelled(true);
        // Рука уже обновлена поливом: пустая бутылка на месте не пропадает.
        event.getPlayer().updateInventory();
    }
}
