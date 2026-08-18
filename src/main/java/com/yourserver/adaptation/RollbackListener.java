package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class RollbackListener implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, Queue<Location>> positionHistory = new HashMap<>();
    private final Map<UUID, BukkitTask> saveTasks = new HashMap<>();
    private final String ROLLBACK_LORE = "§dОткат I";
    private final int HISTORY_SECONDS = 5;
    private final int SAVE_INTERVAL_TICKS = 10; // 0.5 секунды (для точности)

    public RollbackListener(JavaPlugin plugin) {
        this.plugin = plugin;
        startPositionSaver();
    }

    // ===== ЗАПУСК ТАЙМЕРА СОХРАНЕНИЯ ПОЗИЦИЙ =====
    private void startPositionSaver() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    savePlayerPosition(player);
                }
            }
        }.runTaskTimer(plugin, 0L, SAVE_INTERVAL_TICKS);
    }

    // ===== СОХРАНЕНИЕ ПОЗИЦИИ ИГРОКА =====
    private void savePlayerPosition(Player player) {
        UUID uuid = player.getUniqueId();
        Queue<Location> history = positionHistory.computeIfAbsent(uuid, k -> new LinkedList<>());

        // Сохраняем текущую позицию (клонируем, чтобы избежать изменений)
        Location currentLoc = player.getLocation().clone();
        history.add(currentLoc);

        // Оставляем только последние N позиций (где N = HISTORY_SECONDS * (20 / SAVE_INTERVAL_TICKS))
        int maxSize = HISTORY_SECONDS * (20 / SAVE_INTERVAL_TICKS);
        while (history.size() > maxSize) {
            history.poll();
        }
    }

    // ===== ПРОВЕРКА НАЛИЧИЯ ЛОРА "ОТКАТ I" =====
    private boolean hasRollbackLore(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;

        for (String line : meta.getLore()) {
            String cleanLine = ChatColor.stripColor(line).trim();
            if (cleanLine.equals("Откат I")) {
                return true;
            }
        }
        return false;
    }

    // ===== ПОИСК ТОТЕМА С ЧАРОМ В ИНВЕНТАРЕ =====
    private ItemStack findRollbackTotem(Player player) {
        // Проверяем основную руку
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (hasRollbackLore(mainHand) && mainHand.getType() == Material.TOTEM_OF_UNDYING) {
            return mainHand;
        }

        // Проверяем офф-руку
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (hasRollbackLore(offHand) && offHand.getType() == Material.TOTEM_OF_UNDYING) {
            return offHand;
        }

        // Проверяем весь инвентарь (на случай, если тотем не в руке)
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && hasRollbackLore(item) && item.getType() == Material.TOTEM_OF_UNDYING) {
                return item;
            }
        }

        return null;
    }

    // ===== ВЫДАЧА ТОТЕМА С ЧАРОМ (команда) =====
    public boolean giveRollbackTotem(Player target) {
        if (target == null) return false;

        ItemStack totem = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = totem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Тотем Отката");
            meta.setLore(Collections.singletonList(ROLLBACK_LORE));
            meta.setEnchantmentGlintOverride(true);
            totem.setItemMeta(meta);
        }

        // Добавляем тотем в инвентарь
        if (target.getInventory().firstEmpty() == -1) {
            target.getWorld().dropItem(target.getLocation(), totem);
            target.sendMessage(ChatColor.RED + "Ваш инвентарь полон! Тотем упал на землю.");
        } else {
            target.getInventory().addItem(totem);
        }

        return true;
    }

    // ===== ОСНОВНОЕ СОБЫТИЕ: СРАБАТЫВАНИЕ ТОТЕМА =====
    @EventHandler
    public void onTotemUse(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        // Проверяем, есть ли у игрока тотем с чаром
        ItemStack rollbackTotem = findRollbackTotem(player);
        if (rollbackTotem == null) return;

        // Получаем историю позиций
        Queue<Location> history = positionHistory.get(uuid);
        if (history == null || history.isEmpty()) {
            // Если истории нет — используем текущую позицию (как запасной вариант)
            player.sendMessage(ChatColor.RED + "Недостаточно данных для отката! Использована текущая позиция.");
            return;
        }

        // Забираем самую старую позицию (5 секунд назад)
        Location rollbackPos = history.poll();

        // Проверяем, не упал ли игрок в void или лаву
        if (rollbackPos == null || rollbackPos.getY() < 0) {
            // Если позиция невалидная — используем спавн мира
            World world = player.getWorld();
            rollbackPos = world.getSpawnLocation();
            player.sendMessage(ChatColor.RED + "Позиция для отката была небезопасной! Телепортация на спавн.");
        }

        // Создаём эффекты ДО телепортации
        Location currentPos = player.getLocation();
        player.getWorld().spawnParticle(Particle.PORTAL, currentPos, 30, 0.5, 0.5, 0.5, 0.1);
        player.getWorld().playSound(currentPos, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);

        // Телепортируем игрока с проверкой на безопасность
        final Location finalPos = rollbackPos.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Телепортируем
            player.teleport(finalPos, PlayerTeleportEvent.TeleportCause.PLUGIN);

            // Эффекты ПОСЛЕ телепортации
            player.getWorld().spawnParticle(Particle.PORTAL, finalPos, 50, 0.5, 0.5, 0.5, 0.2);
            player.getWorld().playSound(finalPos, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);

            // Эффекты на игроке (дебаффы)
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, 60, 0));

            // Сообщение
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Тотем Отката вернул вас в прошлое!");
        });

        // Удаляем тотем из инвентаря (если он не одноразовый — можно закомментировать)
        // rollbackTotem.setAmount(rollbackTotem.getAmount() - 1);
    }

    // ===== ОЧИСТКА ПРИ ВЫХОДЕ =====
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        positionHistory.remove(uuid);
        if (saveTasks.containsKey(uuid)) {
            saveTasks.get(uuid).cancel();
            saveTasks.remove(uuid);
        }
    }

    // ===== ОЧИСТКА ПРИ ОСТАНОВКЕ ПЛАГИНА =====
    public void disable() {
        positionHistory.clear();
        saveTasks.values().forEach(BukkitTask::cancel);
        saveTasks.clear();
    }
}
