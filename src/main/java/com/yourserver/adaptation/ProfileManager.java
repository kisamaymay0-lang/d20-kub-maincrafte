package com.yourserver.adaptation;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToLongFunction;

/** Профиль/коллекция/размещение. Доступ проверяется по UUID и серверному holder, не по названию предмета. */
public final class ProfileManager implements Listener, CommandExecutor, TabCompleter {
    private enum Screen { PROFILE, COLLECTION, PLACE }

    private static final class Menu implements InventoryHolder {
        final UUID viewer;
        final UUID owner;
        final Screen screen;
        final UUID chosen;
        final Map<Integer, UUID> medalsBySlot = new HashMap<>();
        int page;
        Inventory inventory;
        Menu(UUID viewer, UUID owner, Screen screen, int page, UUID chosen) {
            this.viewer = viewer; this.owner = owner; this.screen = screen; this.page = page; this.chosen = chosen;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class Editing {
        final long expiresAt = System.currentTimeMillis() + 120_000L;
        final AtomicBoolean processing = new AtomicBoolean();
    }

    private record OpenStamp(UUID target, int tick) { }

    private final JavaPlugin plugin;
    private final ProfileStorage storage;
    private final Path medalConfig;
    private MedalSettings medalSettings;
    private final ProfileItems items;
    private final ProfileCards cards;
    private final BukkitTask maintenance;
    private final Map<UUID, Menu> menus = new HashMap<>();
    private final Map<UUID, OpenStamp> opened = new HashMap<>();
    private final Set<UUID> queued = new HashSet<>();
    private final ConcurrentHashMap<UUID, Editing> editing = new ConcurrentHashMap<>();
    private ToLongFunction<UUID> constellationMilestone = ignored -> 0L;
    private volatile boolean stopping;

    public ProfileManager(JavaPlugin plugin, AsyncTextWriter writer) {
        this.plugin = plugin;
        medalConfig = plugin.getDataFolder().toPath().resolve("medals/config.yml");
        if (!Files.exists(medalConfig)) plugin.saveResource("medals/config.yml", false);
        medalSettings = MedalSettings.defaults();
        try { medalSettings = MedalSettings.load(medalConfig); }
        catch (Exception ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "Ошибка настройки медалей; временно используются стандартные значения", ex); }
        storage = new ProfileStorage(plugin.getDataFolder().toPath().resolve("profiles"),
                plugin.getDataFolder().toPath().resolve("medals/players"), writer, plugin.getLogger(), medal -> medalSettings.migrate(medal));
        ZoneId zone = ZoneId.systemDefault();
        String configuredZone = plugin.getConfig().getString("profiles.date-time-zone", "system");
        if (configuredZone != null && !configuredZone.equalsIgnoreCase("system")) {
            try { zone = ZoneId.of(configuredZone); }
            catch (RuntimeException ex) { plugin.getLogger().warning("Некорректный profiles.date-time-zone, используется часовой пояс сервера"); }
        }
        items = new ProfileItems(zone, medalSettings);
        cards = new ProfileCards(plugin, this::profile, items);
        maintenance = Bukkit.getScheduler().runTaskTimer(plugin, this::maintenance, 20L, 20L);
        for (Player player : Bukkit.getOnlinePlayers()) join(player);
    }

    void constellationMilestone(ToLongFunction<UUID> lookup) { constellationMilestone = lookup; }

    private ProfileData profile(Player player) { return profile(player.getUniqueId(), player.getName()); }

    private ProfileData profile(UUID owner, String name) {
        ProfileData data = storage.get(owner, name);
        Player online = Bukkit.getPlayer(owner);
        if (online != null && data.rename(online.getName())) storage.changed(owner);
        long earnedAt = constellationMilestone.applyAsLong(owner);
        if (earnedAt > 0 && !data.hasReward(ProfileMedal.FIRST_CONSTELLATION)) {
            storage.prepareMedalChange(owner);
            if (ProfileAwards.firstConstellation(data, earnedAt, medalSettings)) {
                storage.changed(owner);
                if (!storage.flushBlocking(owner)) throw new IllegalStateException("Медали не сохранены; примените /profile medal reload");
            }
        }
        if (online != null) announcePending(online, data);
        return data;
    }

    private void announcePending(Player player, ProfileData data) {
        if (!data.hasPendingNotifications()) return;
        List<ProfileMedal> pending = data.medals().values().stream().filter(data::needsNotification).toList();
        if (pending.isEmpty()) return;
        var oldHistory = data.notificationHistory();
        for (ProfileMedal medal : pending) data.markNotified(medal);
        storage.changed(data.owner);
        // Сначала фиксируется факт доставки: перезагрузка/чтение профиля не повторяют объявления.
        if (!storage.flushBlocking(data.owner)) {
            data.restoreHistory(data.rewardHistory(), oldHistory);
            return;
        }
        for (ProfileMedal medal : pending) {
            String rarity = medalSettings.style(medal.metal()).rarity();
            Component broadcast = medalSettings.message("public", data.name(), rarity, medal.title(), 1, "");
            Component personal = medalSettings.message("personal", data.name(), rarity, medal.title(), 1, "");
            MedalDelivery.send(broadcast, personal, Bukkit::broadcast, player::sendMessage,
                    () -> player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f));
        }
    }

    private void medalMessage(CommandSender sender, String key, String name, String title, int count, String error) {
        Component message = medalSettings.message(key, name, "", title, count, error == null ? "Ошибка файла" : error);
        if (!PlainTextComponentSerializer.plainText().serialize(message).isBlank()) sender.sendMessage(message);
    }

    /** Вызывается только после НОВОГО завершения; старые completed без маркера ничего не выдают. */
    void constellationCompleted(Player player) {
        try { profile(player); refresh(player.getUniqueId()); }
        catch (RuntimeException ex) {
            medalMessage(player, "pending-error", player.getName(), "", 0, ex.getMessage());
            // Журнал в playerdata.yml позволяет восстановить выдачу после исправления файла/диска.
        }
    }

    private void open(Player viewer, UUID owner, Screen screen, int page, UUID chosen) {
        if (stopping) return;
        if (screen != Screen.PROFILE && !viewer.getUniqueId().equals(owner)) return;
        editing.remove(viewer.getUniqueId());
        try {
            ProfileData data = profile(owner, owner.toString());
            if (screen == Screen.PLACE && !data.medals().containsKey(chosen)) return;
            Menu menu = new Menu(viewer.getUniqueId(), owner, screen, page, chosen);
            String title = switch (screen) {
                case PROFILE -> "Профиль • " + data.name();
                case COLLECTION -> "Выбрать медаль";
                case PLACE -> "Разместить медаль";
            };
            menu.inventory = Bukkit.createInventory(menu, 27, Component.text(title, NamedTextColor.DARK_GRAY));
            populate(menu, data);
            Menu previous = menus.put(viewer.getUniqueId(), menu);
            if (viewer.openInventory(menu.inventory) == null) {
                if (previous != null && viewer.getOpenInventory().getTopInventory() == previous.inventory) menus.put(viewer.getUniqueId(), previous);
                else menus.remove(viewer.getUniqueId(), menu);
            }
        } catch (RuntimeException ex) {
            viewer.sendMessage("§cНе удалось открыть профиль. Администратор найдёт причину в журнале сервера.");
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Ошибка открытия профиля " + owner, ex);
        }
    }

    private void populate(Menu menu, ProfileData data) {
        Inventory inventory = menu.inventory;
        inventory.clear();
        var filler = ProfileItems.filler();
        for (int i = 0; i < 27; i++) if (ProfileText.medalSlot(i) >= 0) inventory.setItem(i, filler);
        menu.medalsBySlot.clear();
        populateDetails(menu, data);
        if (menu.screen == Screen.COLLECTION) {
            List<ProfileMedal> medals = new ArrayList<>(data.medals().values());
            int pages = Math.max(1, (medals.size() + 17) / 18);
            menu.page = Math.clamp(menu.page, 0, pages - 1);
            for (int i = 0; i < 18 && menu.page * 18 + i < medals.size(); i++) {
                ProfileMedal medal = medals.get(menu.page * 18 + i);
                int slot = ProfileText.inventorySlot(i);
                menu.medalsBySlot.put(slot, medal.id());
                inventory.setItem(slot, items.medal(medal, data.isPlaced(medal.id())
                        ? List.of("Нажмите, чтобы переместить", "Shift + клик — снять с профиля") : List.of("Нажмите, чтобы разместить")));
            }
            if (menu.page > 0) inventory.setItem(9, items.page(false, menu.page, pages));
            if (menu.page + 1 < pages) inventory.setItem(17, items.page(true, menu.page, pages));
            return;
        }
        for (int i = 0; i < 18; i++) {
            ProfileMedal medal = data.medals().get(data.medalAt(i));
            int slot = ProfileText.inventorySlot(i);
            if (menu.screen == Screen.PLACE) inventory.setItem(slot, items.destination(medal));
            else if (medal != null) inventory.setItem(slot, items.medal(medal, List.of()));
        }
    }

    private void populateDetails(Menu menu, ProfileData data) {
        boolean owner = menu.viewer.equals(menu.owner);
        menu.inventory.setItem(13, items.head(data, menu.screen == Screen.PROFILE ? data.name() : "Отмена.", menu.screen == Screen.PROFILE && owner));
        if (menu.screen != Screen.COLLECTION) {
            menu.inventory.setItem(12, items.vote(ProfileData.Vote.LIKE, data.voteBy(menu.viewer) == ProfileData.Vote.LIKE, owner));
            menu.inventory.setItem(14, items.vote(ProfileData.Vote.DISLIKE, data.voteBy(menu.viewer) == ProfileData.Vote.DISLIKE, owner));
            menu.inventory.setItem(17, items.settings(data.medals().size(), owner));
        }
    }

    private void refreshDetails(UUID owner) {
        for (Menu menu : new ArrayList<>(menus.values())) {
            if (!menu.owner.equals(owner)) continue;
            Player viewer = Bukkit.getPlayer(menu.viewer);
            if (viewer != null && viewer.getOpenInventory().getTopInventory() == menu.inventory) {
                populateDetails(menu, profile(owner, owner.toString()));
            }
        }
    }

    private void refresh(UUID owner) {
        for (Menu menu : new ArrayList<>(menus.values())) {
            if (!menu.owner.equals(owner)) continue;
            Player viewer = Bukkit.getPlayer(menu.viewer);
            if (viewer == null || viewer.getOpenInventory().getTopInventory() != menu.inventory) continue;
            ProfileData data = profile(owner, owner.toString());
            if (menu.screen == Screen.PLACE && !data.medals().containsKey(menu.chosen)) {
                open(viewer, owner, Screen.COLLECTION, menu.page, null);
            } else populate(menu, data);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true); // Включая shift/цифры/двойной/creative: иконки не забираются из GUI.
        if (!(event.getWhoClicked() instanceof Player player) || !menu.viewer.equals(player.getUniqueId())) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 27 || !event.getClick().isMouseClick() || !queued.add(player.getUniqueId())) return;
        boolean shift = event.isShiftClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            queued.remove(player.getUniqueId());
            if (stopping || !player.isOnline() || player.getOpenInventory().getTopInventory() != menu.inventory) return;
            try { click(player, menu, slot, shift); }
            catch (RuntimeException ex) {
                player.sendMessage("§cНе удалось изменить профиль. Обратитесь к администратору.");
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Ошибка меню профиля", ex);
            }
        });
    }

    private void click(Player player, Menu menu, int slot, boolean shift) {
        ProfileData data = profile(menu.owner, menu.owner.toString());
        boolean own = player.getUniqueId().equals(data.owner);
        if (menu.screen == Screen.PROFILE) {
            if (slot == 12 || slot == 14) {
                if (!own && data.vote(player.getUniqueId(), slot == 12 ? ProfileData.Vote.LIKE : ProfileData.Vote.DISLIKE)) {
                    storage.changed(data.owner); refreshDetails(data.owner); clickSound(player);
                }
            } else if (own && slot == 13) {
                player.closeInventory();
                editing.put(player.getUniqueId(), new Editing());
                player.sendMessage("§6Новое описание напишите в чат §7(до 160 символов, 2 минуты). Сообщение не публикуется в общем чате.");
                player.sendMessage("§7«отмена» — отменить, «очистить» — вернуть «Нет описания.».");
            } else if (own && slot == 17) {
                clickSound(player); open(player, data.owner, Screen.COLLECTION, 0, null);
            }
            return;
        }
        if (!own) return;
        if (slot == 13) {
            clickSound(player);
            open(player, data.owner, menu.screen == Screen.PLACE ? Screen.COLLECTION : Screen.PROFILE, menu.page, null);
            return;
        }
        if (menu.screen == Screen.COLLECTION) {
            UUID medal = menu.medalsBySlot.get(slot);
            if (medal != null) {
                if (shift && data.unplace(player.getUniqueId(), medal)) {
                    storage.changed(data.owner); refresh(data.owner); clickSound(player);
                } else {
                    clickSound(player); open(player, data.owner, Screen.PLACE, menu.page, medal);
                }
            } else if (slot == 9 && menu.page > 0) open(player, data.owner, Screen.COLLECTION, menu.page - 1, null);
            else if (slot == 17 && (menu.page + 1) * 18 < data.medals().size()) open(player, data.owner, Screen.COLLECTION, menu.page + 1, null);
        } else {
            int destination = ProfileText.medalSlot(slot);
            if (destination >= 0 && data.medals().containsKey(menu.chosen)) {
                if (data.place(player.getUniqueId(), menu.chosen, destination)) storage.changed(data.owner);
                clickSound(player); open(player, data.owner, Screen.PROFILE, 0, null); refresh(data.owner);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu
                && event.getRawSlots().stream().anyMatch(slot -> slot < 27)) event.setCancelled(true);
    }

    @EventHandler
    public void inventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) menus.remove(event.getPlayer().getUniqueId(), menu);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) { inspect(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactAt(PlayerInteractAtEntityEvent event) { inspect(event); }

    private void inspect(PlayerInteractEntityEvent event) {
        Player viewer = event.getPlayer();
        if (!viewer.isSneaking() || !(event.getRightClicked() instanceof Player target) || !ProfileCards.canInspect(viewer, target)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        int tick = Bukkit.getCurrentTick();
        OpenStamp previous = opened.get(viewer.getUniqueId());
        if (previous != null && previous.target.equals(target.getUniqueId()) && Integer.toUnsignedLong(tick - previous.tick) < 4) return;
        opened.put(viewer.getUniqueId(), new OpenStamp(target.getUniqueId(), tick));
        open(viewer, target.getUniqueId(), Screen.PROFILE, 0, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void chat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Editing session = editing.get(id);
        if (session == null) return;
        event.setCancelled(true);
        if (!session.processing.compareAndSet(false, true)) return; // Пакеты второго сообщения тоже остаются приватными.
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (stopping) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> finishDescription(id, session, message));
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { editing.remove(id, session); }
    }

    private void finishDescription(UUID id, Editing session, String message) {
        if (stopping || editing.get(id) != session) return;
        Player player = Bukkit.getPlayer(id);
        if (player == null) { editing.remove(id, session); return; }
        if (System.currentTimeMillis() >= session.expiresAt) {
            editing.remove(id, session); player.sendMessage("§7Время изменения описания истекло."); return;
        }
        String clean = ProfileText.clean(message);
        if (clean.equalsIgnoreCase("отмена") || clean.equalsIgnoreCase("cancel")) {
            editing.remove(id, session); open(player, id, Screen.PROFILE, 0, null); return;
        }
        if (clean.isEmpty() || ProfileText.length(clean) > ProfileText.DESCRIPTION_LIMIT) {
            session.processing.set(false);
            player.sendMessage("§cНужно от 1 до 160 символов. Попробуйте ещё раз или напишите «отмена».");
            return;
        }
        editing.remove(id, session);
        try {
            ProfileData data = profile(player);
            if (data.describe(id, (clean.equals("-") || clean.equals("—") || clean.equalsIgnoreCase("очистить")) ? "" : clean)) storage.changed(id);
            refreshDetails(id); open(player, id, Screen.PROFILE, 0, null);
            player.sendMessage("§aОписание профиля сохранено.");
        } catch (RuntimeException ex) { player.sendMessage("§cОписание не сохранено: профиль недоступен."); }
    }

    private void maintenance() {
        storage.flush();
        long now = System.currentTimeMillis();
        editing.forEach((id, session) -> {
            if (now >= session.expiresAt && editing.remove(id, session)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) player.sendMessage("§7Время изменения описания истекло. Откройте /profile, чтобы начать заново.");
            }
        });
    }

    private void join(Player player) {
        storage.pin(player.getUniqueId());
        try { profile(player); }
        catch (RuntimeException ex) { player.sendMessage("§cПрофиль недоступен; обратитесь к администратору."); }
        cards.sneaking(player.getUniqueId(), player.isSneaking());
    }

    @EventHandler public void join(PlayerJoinEvent event) { join(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) { cards.sneaking(event.getPlayer().getUniqueId(), event.isSneaking()); }
    @EventHandler public void world(PlayerChangedWorldEvent event) {
        cards.quit(event.getPlayer().getUniqueId());
        cards.sneaking(event.getPlayer().getUniqueId(), event.getPlayer().isSneaking());
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        editing.remove(id); menus.remove(id); opened.remove(id); queued.remove(id);
        cards.quit(id); storage.unpin(id);
    }

    private static void clickSound(Player player) { player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f); }
    private static boolean admin(CommandSender sender) { return sender.hasPermission("profiles.admin") || sender.hasPermission("f8.admin"); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 && sender instanceof Player player) { open(player, player.getUniqueId(), Screen.PROFILE, 0, null); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel") && sender instanceof Player player) {
            editing.remove(player.getUniqueId()); player.sendMessage("§7Редактирование описания отменено."); return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("medal")) {
            if (!admin(sender)) { medalMessage(sender, "no-permission", "", "", 0, ""); return true; }
            try { medalCommand(sender, args); }
            catch (Exception ex) {
                medalMessage(sender, "error", "", "", 0, ex.getMessage());
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Не применена команда медалей", ex);
            }
            return true;
        }
        help(sender);
        return true;
    }

    private record Target(UUID id, String name) { }

    private Target medalTarget(String argument) {
        OfflinePlayer player = Bukkit.getPlayerExact(argument);
        if (player == null) player = Bukkit.getOfflinePlayerIfCached(argument);
        if (player != null) return new Target(player.getUniqueId(), player.getName());
        try {
            UUID id = UUID.fromString(argument);
            return new Target(id, id.toString());
        } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Игрок не найден в кэше. Укажите игрока в сети или UUID."); }
    }

    private void medalCommand(CommandSender sender, String[] args) throws Exception {
        if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
            MedalSettings next = MedalSettings.load(medalConfig);
            ProfileStorage.ReloadPlan plan = storage.readReloadPlan(); // Проверить всё до изменения активного состояния.
            storage.applyReloadPlan(plan);
            medalSettings = next;
            items.settings(next);
            cards.refresh();
            for (Player player : Bukkit.getOnlinePlayers()) profile(player);
            for (UUID owner : menus.values().stream().map(menu -> menu.owner).distinct().toList()) refresh(owner);
            medalMessage(sender, "reloaded", "", "", 0, "");
            return;
        }
        if (args.length < 3) { help(sender); return; }
        Target target = medalTarget(args[2]);
        ProfileData data = profile(target.id(), target.name());
        if (args[1].equalsIgnoreCase("list")) {
            medalMessage(sender, "list", data.name(), "", data.medals().size(), "");
            int index = 1;
            for (ProfileMedal medal : data.medals().values()) {
                sender.sendMessage(ProfileItems.text(index++ + ". ", NamedTextColor.GRAY)
                        .append(medalSettings.title(medal.title(), medal.metal()))
                        .append(ProfileItems.text("  " + medal.id(), NamedTextColor.DARK_GRAY)));
            }
            sender.sendMessage(ProfileItems.text(storage.medalPath(data.owner).toString(), NamedTextColor.DARK_GRAY));
            return;
        }
        if (args[1].equalsIgnoreCase("give") && args.length >= 5) {
            ProfileMedal.Metal metal = ProfileMedal.Metal.parse(args[3]);
            String[] fields = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).split("\\|", -1);
            if (fields.length < 2) throw new IllegalArgumentException("После названия нужна | и хотя бы одна заслуга");
            ProfileMedal medal = new ProfileMedal(UUID.randomUUID(), metal, fields[0], Arrays.asList(fields).subList(1, fields.length), System.currentTimeMillis(), "");
            storage.prepareMedalChange(data.owner);
            data.award(medal); storage.changed(data.owner);
            if (!storage.flushBlocking(data.owner)) throw new IllegalStateException("Медали не записаны; проверьте файл и примените reload");
            Player online = Bukkit.getPlayer(data.owner);
            if (online != null) announcePending(online, data);
            refresh(data.owner);
            // Если администратор выдал себе медаль, не добавляем второе личное сообщение.
            if (!(sender instanceof Player player) || !player.getUniqueId().equals(data.owner)) {
                medalMessage(sender, "given", data.name(), medal.title(), 1, "");
            }
            return;
        }
        if (args[1].equalsIgnoreCase("take") && args.length == 4) {
            storage.prepareMedalChange(data.owner);
            List<ProfileMedal> owned = new ArrayList<>(data.medals().values());
            List<UUID> remove = new ArrayList<>();
            if (args[3].equalsIgnoreCase("all")) remove.addAll(data.medals().keySet());
            else {
                try {
                    int number = Integer.parseInt(args[3]);
                    if (number >= 1 && number <= owned.size()) remove.add(owned.get(number - 1).id());
                } catch (NumberFormatException ignored) {
                    UUID id = UUID.fromString(args[3]);
                    if (data.medals().containsKey(id)) remove.add(id);
                }
            }
            if (remove.isEmpty()) { medalMessage(sender, "not-found", data.name(), "", 0, ""); return; }
            remove.forEach(data::revoke);
            storage.changed(data.owner);
            if (!storage.flushBlocking(data.owner)) throw new IllegalStateException("Изъятие не записано; проверьте файл и примените reload");
            refresh(data.owner);
            medalMessage(sender, "taken", data.name(), "", remove.size(), "");
            return;
        }
        help(sender);
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("§6/profile §7— свой профиль; Shift + ПКМ по игроку — чужой.");
        sender.sendMessage("§7/profile cancel — отменить ввод описания.");
        if (admin(sender)) {
            sender.sendMessage("§6/profile medal give <игрок или UUID> <copper|silver|gold> <название> | <заслуга 1> | <заслуга 2>");
            sender.sendMessage("§7/profile medal list <игрок> — список и UUID медалей");
            sender.sendMessage("§7/profile medal take <игрок> <номер|UUID|all> — забрать медаль");
            sender.sendMessage("§7/profile medal reload — применить файлы и сообщения без перезапуска");
            sender.sendMessage("§7Имя ищется только в локальном кэше сервера; если игрок не найден, укажите UUID.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = List.of();
        if (args.length == 1) options = admin(sender) ? List.of("cancel", "medal") : List.of("cancel");
        else if (admin(sender) && args[0].equalsIgnoreCase("medal")) {
            if (args.length == 2) options = List.of("give", "take", "list", "reload");
            else if (args.length == 3 && !args[1].equalsIgnoreCase("reload")) options = Bukkit.getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .map(Player::getName).toList();
            else if (args.length == 4 && args[1].equalsIgnoreCase("give")) options = List.of("copper", "silver", "gold");
            else if (args.length == 4 && args[1].equalsIgnoreCase("take")) options = List.of("all");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).toList();
    }

    public void disable() {
        stopping = true;
        maintenance.cancel(); cards.disable();
        for (UUID id : editing.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage("§7Редактирование описания отменено: плагин выключается.");
        }
        editing.clear();
        for (Menu menu : new ArrayList<>(menus.values())) {
            Player player = Bukkit.getPlayer(menu.viewer);
            if (player != null && player.getOpenInventory().getTopInventory() == menu.inventory) player.closeInventory();
        }
        menus.clear(); queued.clear(); opened.clear(); storage.shutdown();
    }
}
