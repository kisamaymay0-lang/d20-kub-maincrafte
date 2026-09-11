package com.yourserver.adaptation;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private enum Screen { PROFILE, COLLECTION, PLACE, PREFIX, PREFIX_CASE }

    /** Сколько префиксов помещается в кейс и как они «бьются».
     *  Моменты «поломок» (тики от открытия, 20 тиков = 1 с): первая через 2 с,
     *  затем 4 с шагом 1 с и оставшиеся 3 с шагом 0.8 с (включая последнюю).
     *  Карточек максимум 9 — значит «поломок» максимум 8 (победитель уцелевший).
     *  После победы — пауза 4 с, чтобы рассмотреть выигранный префикс, затем закрытие. */
    private static final int CASE_MAX = 9;
    private static final int[] CASE_BREAK_TICKS = { 40, 60, 80, 100, 120, 136, 152, 168 };
    private static final int CASE_TICK = 2;             // тик анимации (попадает во все моменты поломок)
    private static final int CASE_VICTORY_TICKS = 80;   // пауза 4 секунды на рассмотрение префикса
    private static final int CASE_SLOT_START = 9;       // фиксированные места: слот 9, 10, … 17

    private static final class CaseRun {
        final UUID owner;
        /** Префиксы в порядке мест: индекс i всегда занимает слот CASE_SLOT_START + i и не съезжает. */
        final List<String> slots = new ArrayList<>();
        final boolean[] broken = new boolean[CASE_MAX];
        int alive;       // сколько префиксов ещё целы
        int elapsed;     // тики с открытия кейса (шаг 20 тиков)
        boolean victory;
        int victoryTick;
        BukkitTask task;
        CaseRun(UUID owner, int alive) { this.owner = owner; this.alive = alive; }
        String survivor() {
            for (int i = 0; i < slots.size(); i++) if (!broken[i]) return slots.get(i);
            return null;
        }
    }

    private static final class Menu implements InventoryHolder {
        final UUID viewer;
        final UUID owner;
        final Screen screen;
        final UUID chosen;
        final Map<Integer, UUID> medalsBySlot = new HashMap<>();
        final Map<Integer, String> prefixesBySlot = new HashMap<>();
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


    private final JavaPlugin plugin;
    private final ProfileStorage storage;
    private final Path medalConfig;
    private final Path prefixConfig;
    private volatile PrefixCatalog prefixes;
    private MedalSettings medalSettings;
    private final ProfileItems items;
    private final ProfileCards cards;
    private final ProfileVoice voice;
    private final ProfileSubjects subjects;
    private final BukkitTask maintenance;
    private final Map<UUID, Menu> menus = new HashMap<>();
    private final Map<UUID, Integer> cardClicks = new HashMap<>();
    private final Map<UUID, Integer> rightClicks = new HashMap<>();
    private final Set<UUID> queued = new HashSet<>();
    private final ConcurrentHashMap<UUID, Editing> editing = new ConcurrentHashMap<>();
    private final Map<UUID, CaseRun> caseRuns = new HashMap<>();
    private final Map<UUID, String> pendingPrefixGrants = new HashMap<>();
    /** Надетый префикс для быстрого чтения из асинхронных событий (чат) без обращения к хранилищу. */
    private final Map<UUID, String> equippedPrefixes = new ConcurrentHashMap<>();
    private ToLongFunction<UUID> constellationMilestone = ignored -> 0L;
    /** Замена ванильного ника над головой на картинку префикса + белый ник. */
    private ProfileTags tags;
    private volatile boolean stopping;

    public ProfileManager(JavaPlugin plugin, AsyncTextWriter writer) {
        this.plugin = plugin;
        medalConfig = plugin.getDataFolder().toPath().resolve("medals/config.yml");
        if (!Files.exists(medalConfig)) plugin.saveResource("medals/config.yml", false);
        medalSettings = MedalSettings.defaults();
        try { medalSettings = MedalSettings.load(medalConfig); }
        catch (Exception ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "Ошибка настройки медалей; временно используются стандартные значения", ex); }
        prefixConfig = plugin.getDataFolder().toPath().resolve("prefixes.yml");
        if (!Files.exists(prefixConfig)) {
            plugin.saveResource("prefixes.yml", false);
            plugin.getLogger().info("Создан файл префиксов: " + prefixConfig + " (правьте его и применяйте через /profile prefix reload)");
        }
        PrefixCatalog loadedPrefixes = PrefixCatalog.defaults();
        try { loadedPrefixes = PrefixCatalog.load(prefixConfig); }
        catch (Exception ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "Ошибка prefixes.yml; используются стандартные префиксы", ex); }
        prefixes = loadedPrefixes;
        plugin.getLogger().info("Префиксы профиля: " + prefixes.size() + " шт., файл " + prefixConfig);
        storage = new ProfileStorage(plugin.getDataFolder().toPath().resolve("profiles"),
                plugin.getDataFolder().toPath().resolve("medals/players"), writer, plugin.getLogger(), medal -> medalSettings.migrate(medal));
        ZoneId zone = ZoneId.systemDefault();
        String configuredZone = plugin.getConfig().getString("profiles.date-time-zone", "system");
        if (configuredZone != null && !configuredZone.equalsIgnoreCase("system")) {
            try { zone = ZoneId.of(configuredZone); }
            catch (RuntimeException ex) { plugin.getLogger().warning("Некорректный profiles.date-time-zone, используется часовой пояс сервера"); }
        }
        voice = new ProfileVoice(plugin, this::saveVoice);
        items = new ProfileItems(zone, medalSettings);
        subjects = new ProfileSubjects(plugin, this::profile);
        cards = new ProfileCards(plugin, subject -> profile(subject.profile(), subject.name()), items, subjects, voice, prefixes);
        // Тег префикса над головой — чисто визуальное. По умолчанию выключен
        // (профили показываются в карточке/чате, над головой в игре ничего не висит).
        boolean overheadTags = plugin.getConfig().getBoolean("profiles.overhead-tags", false);
        tags = overheadTags ? new ProfileTags(plugin, player -> prefixes.get(equippedPrefixes.get(player.getUniqueId()))) : null;
        maintenance = Bukkit.getScheduler().runTaskTimer(plugin, this::maintenance, 20L, 20L);
        for (Player player : Bukkit.getOnlinePlayers()) join(player);
    }

    void constellationMilestone(ToLongFunction<UUID> lookup) {
        constellationMilestone = lookup;
        // Зафиксировать старый результат до первого нового игрового события после обновления.
        for (Player player : Bukkit.getOnlinePlayers()) join(player);
    }

    private ProfileData profile(Player player) { return profile(player.getUniqueId(), player.getName()); }

    private ProfileData profile(UUID owner, String name) {
        if (subjects != null && subjects.preview(owner)) return subjects.profile(owner);
        ProfileData data = storage.get(owner, name);
        Player online = Bukkit.getPlayer(owner);
        if (online != null && data.rename(online.getName())) storage.changed(owner);
        long earnedAt = constellationMilestone.applyAsLong(owner);
        if (earnedAt > data.astronomyProgress()) {
            storage.prepareMedalChange(owner);
            long previous = data.astronomyProgress();
            boolean legacyHistory = previous == 0 && data.hasReward(ProfileMedal.FIRST_CONSTELLATION);
            boolean awarded = ProfileAwards.firstConstellation(data, earnedAt, medalSettings);
            if (data.astronomyProgress() != previous) storage.changed(owner);
            if ((awarded || legacyHistory) && !storage.flushBlocking(owner)) {
                throw new IllegalStateException("Медали не сохранены; примените /profile medal reload");
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
            Component broadcast = medalSettings.message("public", data.name(), rarity, medal.title(), 1, "", items.publicMedal(medal).asHoverEvent());
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

    /**
     * Съеден бутерброд: отмечаем вид навсегда и выдаём медали, заполненные в medals/config.yml.
     * Запрет дубля — только пока медаль ЕСТЬ у игрока: изъятую (take/очистку) можно
     * получить заново следующим подходящим съеденным бутербродом.
     */
    void sandwichEaten(Player player, String kind) {
        if (!("red".equals(kind) || "black".equals(kind) || "ice".equals(kind))) return;
        try {
            ProfileData data = profile(player);
            UUID owner = player.getUniqueId();
            storage.prepareMedalChange(owner);
            boolean changed = data.markClaimed(ProfileAwards.eatenMarker(kind));
            boolean all = medalSettings.sandwichAllKinds.filled()
                    && !data.ownsReward(ProfileMedal.SANDWICH_ALL_KINDS)
                    && ProfileAwards.allSandwichKindsEaten(data)
                    && data.award(medal(medalSettings.sandwichAllKinds, ProfileMedal.SANDWICH_ALL_KINDS));
            boolean ice = "ice".equals(kind) && medalSettings.sandwichIceCaviar.filled()
                    && !data.ownsReward(ProfileMedal.SANDWICH_ICE_CAVIAR)
                    && data.award(medal(medalSettings.sandwichIceCaviar, ProfileMedal.SANDWICH_ICE_CAVIAR));
            if (changed || all || ice) storage.changed(owner);
            if (!changed && !all && !ice) return;
            if (!storage.flushBlocking(owner)) throw new IllegalStateException("Медали не записаны; проверьте файл и примените /profile medal reload");
            announcePending(player, data);
            refresh(owner);
        } catch (RuntimeException ex) {
            medalMessage(player, "pending-error", player.getName(), "", 0, ex.getMessage());
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Не выдана медаль за бутерброд игроку " + player.getName(), ex);
        }
    }

    private static ProfileMedal medal(MedalSettings.SandwichSpec spec, String source) {
        return new ProfileMedal(UUID.randomUUID(), spec.metal(), spec.title(), spec.reasons(), System.currentTimeMillis(), source);
    }

    // ===================== Префиксы и кейсы =====================

    private void savePrefix(ProfileData data) {
        storage.changed(data.owner);
        if (!storage.flushBlocking(data.owner)) throw new IllegalStateException("Не удалось сохранить профиль");
    }

    /** «Префикс перед ником везде»: картинка префикса перед белым ником в табе и поверх головы.
     *  displayName красим только ник — чтобы события, берущие displayName сами, не задваивали иконку. */
    private void applyPrefixName(Player player, ProfileData data) {
        PrefixCatalog.Prefix prefix = prefixes.get(data.equippedPrefix());
        if (prefix == null) equippedPrefixes.remove(player.getUniqueId());
        else equippedPrefixes.put(player.getUniqueId(), prefix.id());
        Component line = ProfileIcons.prefixedName(prefix, player.getName());
        player.displayName(line);
        player.playerListName(line);
        if (tags != null) tags.apply(player, prefix);
    }

    private void equipPrefix(Player player, ProfileData data, String id) {
        String previous = data.equippedPrefix();
        if (previous != null && previous.equals(id)) return;
        if (!data.equipPrefix(id)) return;
        try { savePrefix(data); }
        catch (RuntimeException ex) { data.equipPrefix(previous); throw ex; }
        clickSound(player);
        applyPrefixName(player, data);
        refresh(data.owner);
    }

    private void unequipPrefix(Player player, ProfileData data) {
        String previous = data.equippedPrefix();
        if (previous == null) return;
        if (!data.equipPrefix(null)) return;
        try { savePrefix(data); }
        catch (RuntimeException ex) { data.equipPrefix(previous); throw ex; }
        clickSound(player);
        applyPrefixName(player, data);
        refresh(data.owner);
    }

    /** Списывает кейс и запускает вскрытие до одного префикса (кейс не возвращается даже при выходе).
     *  Участвуют только не полученные префиксы, максимум 9; каждое место фиксировано. */
    private void openPrefixCase(Player player, ProfileData data) {
        if (caseRuns.containsKey(data.owner)) {
            player.sendMessage(ProfileItems.text("Вскрытие кейса уже идёт.", NamedTextColor.RED));
            return;
        }
        List<PrefixCatalog.Prefix> pool = prefixes.list().stream()
                .filter(prefix -> !data.ownsPrefix(prefix.id())).toList();
        if (pool.isEmpty()) {
            player.sendMessage(ProfileItems.text("У вас уже есть все префиксы.", NamedTextColor.RED));
            return;
        }
        if (!data.takePrefixCase()) {
            player.sendMessage(ProfileItems.text("У вас нет кейсов префиксов.", NamedTextColor.RED));
            return;
        }
        try { savePrefix(data); }
        catch (RuntimeException ex) {
            data.addPrefixCase();
            player.sendMessage(ProfileItems.text("Не удалось открыть кейс: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }
        List<PrefixCatalog.Prefix> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new java.util.Random());
        int count = Math.min(CASE_MAX, shuffled.size());
        CaseRun run = new CaseRun(data.owner, count);
        for (int i = 0; i < count; i++) run.slots.add(shuffled.get(i).id());
        caseRuns.put(data.owner, run);
        clickSound(player);
        open(player, data.owner, Screen.PREFIX_CASE, 0, null);
        if (count == 1) {
            // Не выбитых префиксов меньше двух: показываем единственного и забираем через 4 секунды.
            run.victory = true;
            run.victoryTick = 0;
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.1f);
            fireworks(player, run.slots.get(0));
        }
        run.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> caseStep(run), CASE_TICK, CASE_TICK);
    }

    private void caseStep(CaseRun run) {
        try {
            caseTick(run);
        } catch (RuntimeException ex) {
            // Даже если анимация упала (например, не загрузился мир), кейс уже списан —
            // завершаем вскрытие и выдаём оставшийся префикс.
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Ошибка анимации кейса префиксов " + run.owner, ex);
            if (caseRuns.get(run.owner) == run) finalizeCase(run);
        }
    }

    private void caseTick(CaseRun run) {
        run.elapsed += CASE_TICK;
        // Ритм «поломок»: первая через 2 с, 4 с шагом 1 с, 2 с шагом 0.8 с, последняя с шагом 0.5 с.
        int broken = countBroken(run);
        if (run.alive > 1 && broken < CASE_BREAK_TICKS.length && run.elapsed >= CASE_BREAK_TICKS[broken]) {
            breakRandomPrefix(run);
            Player player = Bukkit.getPlayer(run.owner);
            if (player != null) {
                player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.9f, 0.7f);
                renderOpenCase(player);
            }
            if (run.alive == 1) { // Последний уцелевший: 4 секунды показать его и закрыть окно.
                run.victory = true;
                run.victoryTick = run.elapsed;
                if (player != null) {
                    player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.1f);
                    fireworks(player, run.survivor());
                }
            }
        }
        if (run.alive == 1 && run.victory) {
            Player player = Bukkit.getPlayer(run.owner);
            int since = run.elapsed - run.victoryTick;
            if (since == 20 && player != null) player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.2f);
            else if (since == 40 && player != null) player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.4f);
            else if (since >= CASE_VICTORY_TICKS) { finalizeCase(run); return; }
            return;
        }
        // Страховка от вечного тика (крайний момент поломки + финал).
        if (run.elapsed >= CASE_BREAK_TICKS[CASE_BREAK_TICKS.length - 1] + CASE_VICTORY_TICKS) finalizeCase(run);
    }

    private int countBroken(CaseRun run) {
        int broken = 0;
        for (int i = 0; i < run.slots.size(); i++) if (run.broken[i]) broken++;
        return broken;
    }

    private void breakRandomPrefix(CaseRun run) {
        java.util.List<Integer> intact = new ArrayList<>();
        for (int i = 0; i < run.slots.size(); i++) if (!run.broken[i]) intact.add(i);
        int target = intact.get(new java.util.Random().nextInt(intact.size()));
        run.broken[target] = true;
        run.alive--;
    }

    /** Салют цветом выигранного префикса над игроком. */
    private void fireworks(Player player, String prefixId) {
        PrefixCatalog.Prefix prefix = prefixes.get(prefixId);
        if (prefix == null) return;
        try {
            int rgb = prefix.color().value();
            var color = org.bukkit.Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            for (int i = 0; i < 2; i++) {
                Location location = player.getLocation().add(0, 1.2, 0);
                Firework firework = player.getWorld().spawn(location, Firework.class);
                FireworkMeta meta = firework.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST)
                        .withColor(color).withFlicker().build());
                meta.setPower(1);
                firework.setFireworkMeta(meta);
                Bukkit.getScheduler().runTaskLater(plugin, firework::detonate, 6L + 8L * i);
            }
        } catch (RuntimeException ignored) { }
    }

    private void finalizeCase(CaseRun run) {
        if (run.task != null) run.task.cancel();
        caseRuns.remove(run.owner);
        Player player = Bukkit.getPlayer(run.owner);
        String winner = run.survivor();
        try {
            ProfileData data = profile(run.owner, player == null ? run.owner.toString() : player.getName());
            if (winner == null || !data.addPrefix(winner)) {
                // Победитель уже был получен (например, выдан администратором во время вскрытия):
                // кейс не пропадает, добираем любой ещё не полученный префикс.
                winner = prefixes.list().stream().map(PrefixCatalog.Prefix::id)
                        .filter(candidate -> !data.ownsPrefix(candidate)).findFirst().orElse(null);
                if (winner == null || !data.addPrefix(winner)) {
                    plugin.getLogger().warning("Кейс префиксов " + run.owner + ": все префиксы уже получены");
                    return;
                }
            }
            savePrefix(data);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Префикс из кейса не сохранён: " + run.owner, ex);
            return;
        }
        if (player == null || !player.isOnline()) {
            pendingPrefixGrants.put(run.owner, winner); // Сообщим при входе.
            return;
        }
        announcePrefix(player, winner);
        if (player.getOpenInventory().getTopInventory() != null
                && player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu
                && menu.owner.equals(run.owner) && menu.screen == Screen.PREFIX_CASE) {
            open(player, run.owner, Screen.PROFILE, 0, null);
        }
    }

    private void announcePrefix(Player player, String winner) {
        PrefixCatalog.Prefix prefix = prefixes.get(winner);
        String name = prefix == null ? winner : prefix.name();
        player.sendMessage(ProfileItems.text("Вы получили префикс «" + name + "»!", NamedTextColor.GREEN)
                .append(ProfileItems.text(" Поменяйте его в /profile", NamedTextColor.WHITE)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    private void renderOpenCase(Player player) {
        Menu menu = menus.get(player.getUniqueId());
        if (menu == null || menu.screen != Screen.PREFIX_CASE
                || player.getOpenInventory().getTopInventory() != menu.inventory) return;
        populate(menu, profile(menu.owner, menu.owner.toString()));
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
                case PREFIX -> "Выбрать префикс";
                case PREFIX_CASE -> "Вскрытие кейса префиксов";
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
        menu.prefixesBySlot.clear();
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
        if (menu.screen == Screen.PREFIX) {
            List<PrefixCatalog.Prefix> all = prefixes.list();
            int pages = Math.max(1, (all.size() + 17) / 18);
            menu.page = Math.clamp(menu.page, 0, pages - 1);
            for (int i = 0; i < 18 && menu.page * 18 + i < all.size(); i++) {
                PrefixCatalog.Prefix prefix = all.get(menu.page * 18 + i);
                int slot = ProfileText.inventorySlot(i);
                menu.prefixesBySlot.put(slot, prefix.id());
                inventory.setItem(slot, items.prefixEntry(prefix, data.ownsPrefix(prefix.id()),
                        prefix.id().equals(data.equippedPrefix())));
            }
            if (menu.page > 0) inventory.setItem(9, items.page(false, menu.page, pages));
            if (menu.page + 1 < pages) inventory.setItem(17, items.page(true, menu.page, pages));
            inventory.setItem(13, items.prefixCase(data.prefixCases()));
            return;
        }
        if (menu.screen == Screen.PREFIX_CASE) {
            renderCaseMenu(menu);
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
        boolean profileScreen = menu.screen == Screen.PROFILE;
        if (menu.screen == Screen.PROFILE || menu.screen == Screen.PLACE) {
            // Иконка префикса перед ником на голове профиля; текст, какой префикс надет, нигде не пишем.
            PrefixCatalog.Prefix equipped = profileScreen ? prefixes.get(data.equippedPrefix()) : null;
            menu.inventory.setItem(13, items.head(data, profileScreen ? data.name() : "Отмена.", profileScreen && owner, equipped));
            menu.inventory.setItem(12, items.vote(ProfileData.Vote.LIKE, data.voteBy(menu.viewer) == ProfileData.Vote.LIKE, owner));
            menu.inventory.setItem(14, items.vote(ProfileData.Vote.DISLIKE, data.voteBy(menu.viewer) == ProfileData.Vote.DISLIKE, owner));
            menu.inventory.setItem(17, items.settings(data.medals().size(), owner));
        }
        if (profileScreen) {
            // Кнопка: иконка надетого префикса как образец (без подписи «Текущий: …»), иначе случайный образец.
            PrefixCatalog.Prefix equipped = prefixes.get(data.equippedPrefix());
            menu.inventory.setItem(9, items.prefixButton(equipped != null ? equipped : randomPrefix(), owner));
        }
    }

    private PrefixCatalog.Prefix randomPrefix() {
        List<PrefixCatalog.Prefix> all = prefixes.list();
        return all.isEmpty() ? null : all.get(Math.abs(new java.util.Random().nextInt()) % all.size());
    }

    private void renderCaseMenu(Menu menu) {
        CaseRun run = caseRuns.get(menu.owner);
        if (run == null) return;
        for (int i = 0; i < run.slots.size(); i++) {
            int slot = CASE_SLOT_START + i; // Место не меняется, даже когда сосед «разбился».
            if (run.broken[i]) {
                menu.inventory.setItem(slot, null);
                continue;
            }
            PrefixCatalog.Prefix prefix = prefixes.get(run.slots.get(i));
            if (prefix != null) menu.inventory.setItem(slot, items.prefixReveal(prefix, i + 1));
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
                if (!own && subjects.vote(data, player.getUniqueId(), slot == 12 ? ProfileData.Vote.LIKE : ProfileData.Vote.DISLIKE)) {
                    storage.changed(data.owner); refreshDetails(data.owner); clickSound(player);
                }
            } else if (own && slot == 13) {
                if (voice.active(player.getUniqueId())) { voice.chat(player, ""); return; }
                player.closeInventory();
                editing.put(player.getUniqueId(), new Editing());
                player.sendMessage(voice.prompt());
            } else if (own && slot == 17) {
                clickSound(player); open(player, data.owner, Screen.COLLECTION, 0, null);
            } else if (slot == 9) {
                clickSound(player);
                if (own) open(player, data.owner, Screen.PREFIX, 0, null);
                else player.sendMessage(ProfileItems.text("Настраивать можно только свой профиль.", NamedTextColor.RED));
            }
            return;
        }
        if (!own) return;
        if (menu.screen == Screen.PREFIX) {
            if (slot == 9 && menu.page > 0) { clickSound(player); open(player, data.owner, Screen.PREFIX, menu.page - 1, null); return; }
            if (slot == 17 && (menu.page + 1) * 18 < prefixes.size()) { clickSound(player); open(player, data.owner, Screen.PREFIX, menu.page + 1, null); return; }
            if (slot == 13) { clickSound(player); openPrefixCase(player, data); return; }
            String id = menu.prefixesBySlot.get(slot);
            if (id == null) return;
            PrefixCatalog.Prefix prefix = prefixes.get(id);
            if (prefix == null) return;
            if (!data.ownsPrefix(id)) {
                player.sendMessage(ProfileItems.text("У вас нету этого префикса!", NamedTextColor.RED));
                return;
            }
            if (shift && id.equals(data.equippedPrefix())) {
                unequipPrefix(player, data);
            } else if (!id.equals(data.equippedPrefix())) {
                equipPrefix(player, data, id);
            } else {
                clickSound(player);
            }
            return;
        }
        if (menu.screen == Screen.PREFIX_CASE) return; // Вскрытие идёт само; клики по меню не мешают.
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

    // ПКМ больше не открывает чужой профиль. Отмечаем его только для фильтрации
    // сопутствующей анимации руки, которую Paper иногда преобразует в LEFT_CLICK_AIR.
    @EventHandler(priority = EventPriority.LOWEST)
    public void interact(PlayerInteractEntityEvent event) { rememberRightClick(event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void interactAt(PlayerInteractAtEntityEvent event) { rememberRightClick(event.getPlayer()); }

    private void rememberRightClick(Player player) {
        if (cards.hasCard(player.getUniqueId())) rightClicks.put(player.getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void clickCard(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            rememberRightClick(event.getPlayer()); return;
        }
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Integer right = rightClicks.get(event.getPlayer().getUniqueId());
        if (right != null && Integer.toUnsignedLong(Bukkit.getCurrentTick() - right) <= 1) return;
        if (useCard(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void cardAttack(PrePlayerAttackEntityEvent event) {
        // Перехват ДО урона: ЛКМ по карточке не бьёт игрока и не запускает его боевые чары.
        if (useCard(event.getPlayer()) || subjects.cloneEntity(event.getAttacked().getUniqueId())) event.setCancelled(true);
    }

    private boolean useCard(Player player) {
        if (stopping) return false;
        ProfileCards.Click hit = cards.click(player);
        if (hit == null) return false;
        int tick = Bukkit.getCurrentTick();
        Integer previous = cardClicks.get(player.getUniqueId());
        if (previous != null && Integer.toUnsignedLong(tick - previous) < 4) return true;
        cardClicks.put(player.getUniqueId(), tick);
        if (hit.action() == ProfilePanelGeometry.Action.NONE) return true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (stopping || !player.isOnline()) return;
            ProfileSubjects.Subject target = subjects.resolve(player, hit.entity());
            if (target == null || !target.profile().equals(hit.owner())) return;
            if (hit.action() == ProfilePanelGeometry.Action.OPEN) {
                clickSound(player);
                open(player, hit.owner(), Screen.PROFILE, 0, null);
            } else {
                try {
                    ProfileData data = profile(target.profile(), target.name());
                    if (hit.action() == ProfilePanelGeometry.Action.PLAY_VOICE) { voice.play(player, data.voice()); return; }
                    ProfileData.Vote vote = hit.action() == ProfilePanelGeometry.Action.LIKE ? ProfileData.Vote.LIKE : ProfileData.Vote.DISLIKE;
                    if (subjects.vote(data, player.getUniqueId(), vote)) { storage.changed(data.owner); refreshDetails(data.owner); clickSound(player); }
                } catch (RuntimeException ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось обработать нажатие карточки", ex); }
            }
        });
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void chat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (voice.active(id)) {
            event.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText().serialize(event.message());
            if (!stopping) try {
                Bukkit.getScheduler().runTask(plugin, () -> { Player player = Bukkit.getPlayer(id); if (player != null) voice.chat(player, input); });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
            return;
        }
        Editing session = editing.get(id);
        if (session == null) {
            // Обычное сообщение: надетый префикс рисуем перед ником гарантированно,
            // не полагаясь на то, какой отображаемое имя выберет рендерер сервера.
            PrefixCatalog.Prefix prefix = prefixes.get(equippedPrefixes.get(id));
            if (prefix != null) {
                Player speaker = event.getPlayer();
                // Картинка префикса перед белым ником (без текстового «[Название]»).
                Component prefixed = ProfileIcons.prefixedName(prefix, speaker.getName());
                event.renderer(ChatRenderer.viewerUnaware((sourcePlayer, sourceDisplayName, message) ->
                        Component.translatable("chat.type.text", prefixed, message)));
            }
            return;
        }
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
        if (clean.equalsIgnoreCase("запись")) {
            if (voice.start(player)) editing.remove(id, session);
            else session.processing.set(false);
            return;
        }
        if (clean.isEmpty() || ProfileText.length(clean) > ProfileText.DESCRIPTION_LIMIT) {
            session.processing.set(false);
            player.sendMessage("§cНужно от 1 до 160 символов. Попробуйте ещё раз или напишите «отмена».");
            return;
        }
        editing.remove(id, session);
        try {
            ProfileData data = profile(player);
            String before = data.description();
            ProfileVoiceNote previousVoice = data.voice();
            if (data.describe(id, (clean.equals("-") || clean.equals("—") || clean.equalsIgnoreCase("очистить")) ? "" : clean)) {
                storage.changed(id);
                if (!storage.flushBlocking(id)) {
                    data.restoreDescription(before, previousVoice); storage.changed(id);
                    throw new IllegalStateException("Не удалось сохранить описание");
                }
                voice.discard(previousVoice);
            }
            refreshDetails(id); open(player, id, Screen.PROFILE, 0, null);
            player.sendMessage("§aОписание профиля сохранено.");
        } catch (RuntimeException ex) { player.sendMessage("§cОписание не сохранено: профиль недоступен."); }
    }

    private boolean saveVoice(Player player, ProfileVoiceNote note) {
        ProfileData data = profile(player);
        String before = data.description();
        ProfileVoiceNote previous = data.voice();
        if (!data.voice(player.getUniqueId(), note)) return false;
        storage.changed(data.owner);
        if (!storage.flushBlocking(data.owner)) {
            data.restoreDescription(before, previous); storage.changed(data.owner); return false;
        }
        voice.discard(previous);
        refreshDetails(data.owner);
        return true;
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
        try {
            ProfileData data = profile(player);
            applyPrefixName(player, data);
            String pending = pendingPrefixGrants.remove(player.getUniqueId());
            if (pending != null) announcePrefix(player, pending);
        }
        catch (RuntimeException ex) { player.sendMessage("§cПрофиль недоступен; обратитесь к администратору."); }
        cards.sneaking(player.getUniqueId(), player.isSneaking());
    }

    @EventHandler public void join(PlayerJoinEvent event) { join(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) { cards.sneaking(event.getPlayer().getUniqueId(), event.isSneaking()); }
    @EventHandler public void world(PlayerChangedWorldEvent event) {
        removeClone(event.getPlayer());
        cards.quit(event.getPlayer().getUniqueId());
        cards.sneaking(event.getPlayer().getUniqueId(), event.getPlayer().isSneaking());
        // tags сам переносит тег в новый мир следующим тиком.
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        editing.remove(id); menus.remove(id); cardClicks.remove(id); rightClicks.remove(id); queued.remove(id);
        equippedPrefixes.remove(id);
        removeClone(event.getPlayer());
        if (tags != null) tags.quit(event.getPlayer());
        cards.quit(id); voice.quit(id); storage.unpin(id);
    }

    private void removeClone(Player owner) {
        UUID profile = subjects.remove(owner.getUniqueId());
        if (profile == null) return;
        for (Menu menu : new ArrayList<>(menus.values())) {
            if (profile.equals(menu.owner)) {
                Player viewer = Bukkit.getPlayer(menu.viewer);
                if (viewer != null && viewer.getOpenInventory().getTopInventory() == menu.inventory) viewer.closeInventory();
            }
        }
    }

    private static void clickSound(Player player) { player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f); }
    private static boolean admin(CommandSender sender) { return sender.hasPermission("profiles.admin") || sender.hasPermission("f8.admin"); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 && sender instanceof Player player) { open(player, player.getUniqueId(), Screen.PROFILE, 0, null); return true; }
        if (args.length == 2 && args[0].equalsIgnoreCase("voice") && args[1].equalsIgnoreCase("reload")) {
            if (!admin(sender)) { sender.sendMessage("§cНет прав."); return true; }
            try { voice.reloadMessages(); sender.sendMessage("§aСообщения голосовых профилей перезагружены."); }
            catch (Exception ex) { sender.sendMessage("§cНе удалось прочитать voice/config.yml."); }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("clone")) {
            if (!(sender instanceof Player player) || !admin(sender)) { sender.sendMessage("§cКоманда доступна администратору в игре."); return true; }
            try {
                removeClone(player);
                if (args.length > 1 && args[1].equalsIgnoreCase("remove")) player.sendMessage("§7Тестовый клон удалён.");
                else {
                    subjects.spawn(player);
                    player.sendMessage("§aТестовый клон создан. §7Shift и взгляд — карточка. Оценки на клоне не меняют настоящий профиль.");
                }
            } catch (RuntimeException ex) { player.sendMessage("§cНе удалось создать клон: " + ex.getMessage()); }
            return true;
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
        if (args.length > 0 && args[0].equalsIgnoreCase("case")) {
            if (!admin(sender)) { sender.sendMessage("§cНет прав."); return true; }
            try { caseCommand(sender, args); }
            catch (Exception ex) {
                sender.sendMessage("§cНе удалось выдать кейс префиксов: " + ex.getMessage());
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Не применена команда кейсов", ex);
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("prefix")) {
            if (!admin(sender)) { sender.sendMessage("§cНет прав."); return true; }
            try { prefixCommand(sender, args); }
            catch (Exception ex) {
                sender.sendMessage("§cНе удалось применить команду префиксов: " + ex.getMessage());
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Не применена команда префиксов", ex);
            }
            return true;
        }
        help(sender);
        return true;
    }

    /** /profile prefix give|take <игрок или UUID> <номер|all>, /profile prefix list <игрок>, /profile prefix reload. */
    private void prefixCommand(CommandSender sender, String[] args) throws Exception {
        if (args.length == 1) { help(sender); return; }
        if (args.length >= 2 && args[1].equalsIgnoreCase("test")) {
            // Проверка ресурспака: глифы E101–E10F и образец «иконка префикса + белый ник».
            String name = (sender instanceof Player self) ? self.getName() : "Steve";
            if (args.length >= 3) name = medalTarget(args[2]).name();
            PrefixCatalog.Prefix sample = prefixes.list().isEmpty() ? null : prefixes.list().get(0);
            Player target = Bukkit.getPlayerExact(name);
            if (target != null && equippedPrefixes.get(target.getUniqueId()) != null) {
                PrefixCatalog.Prefix equipped = prefixes.get(equippedPrefixes.get(target.getUniqueId()));
                if (equipped != null) sample = equipped;
            }
            sender.sendMessage(ProfileItems.text("Глифы E101-E10F (тут должны быть картинки, не квадраты):", NamedTextColor.GRAY)
                    .append(Component.space()).append(ProfileIcons.glyphSample()));
            sender.sendMessage(ProfileItems.text("Иконка префикса + белый ник «" + name + "»:", NamedTextColor.GRAY)
                    .append(Component.space()).append(ProfileIcons.prefixedName(sample, name)));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
            PrefixCatalog next = PrefixCatalog.load(prefixConfig); // Сначала проверяем файл: состояние не меняется при ошибке.
            prefixes = next;
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    ProfileData data = profile(player);
                    // Надетый префикс исчез из файла: снимаем его, чтобы ник не «завис» без префикса.
                    String equipped = data.equippedPrefix();
                    if (equipped != null && prefixes.get(equipped) == null && data.equipPrefix(null)) savePrefix(data);
                    applyPrefixName(player, data);
                } catch (RuntimeException ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "Не применён префикс после reload: " + player.getName(), ex); }
            }
            for (UUID owner : menus.values().stream().map(menu -> menu.owner).distinct().toList()) refresh(owner);
            sender.sendMessage(ProfileItems.text("Префиксы перезагружены: " + prefixes.size() + " (№1–" + prefixes.size() + "). Файл: " + prefixConfig, NamedTextColor.GREEN));
            return;
        }
        if (args.length < 3) throw new IllegalArgumentException("Использование: /profile prefix give|take|list <игрок или UUID> [номер|all]");
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        if (!action.equals("give") && !action.equals("take") && !action.equals("list")) {
            throw new IllegalArgumentException("Действие: give, take или list");
        }
        Target target = medalTarget(args[2]);
        ProfileData data = profile(target.id(), target.name());
        if (action.equals("list")) {
            sender.sendMessage(ProfileItems.text("Префиксы «" + data.name() + "»:", NamedTextColor.GOLD));
            for (PrefixCatalog.Prefix prefix : prefixes.list()) {
                boolean owned = data.ownsPrefix(prefix.id());
                boolean equipped = prefix.id().equals(data.equippedPrefix());
                String mark = !owned ? " — нет" : equipped ? " — надет" : "";
                sender.sendMessage(ProfileItems.text("№" + prefix.number() + " ", NamedTextColor.DARK_GRAY)
                        .append(ProfileItems.text(prefix.name(), owned ? prefix.color() : NamedTextColor.DARK_GRAY))
                        .append(ProfileItems.text(mark, equipped ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
            }
            return;
        }
        if (args.length != 4) throw new IllegalArgumentException("Нужен номер префикса из файла или all: /profile prefix " + action + " <игрок> <номер|all>");
        if (args[3].equalsIgnoreCase("all")) {
            giveTakeAll(sender, data, action);
            return;
        }
        int number;
        try { number = Integer.parseInt(args[3]); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Номер префикса — целое число или all (см. " + prefixConfig + ")"); }
        PrefixCatalog.Prefix prefix = prefixes.byNumber(number);
        if (prefix == null) throw new IllegalArgumentException("Префикс №" + number + " не найден: доступны номера №1–" + prefixes.size());
        if (action.equals("give")) {
            if (!data.addPrefix(prefix.id())) {
                sender.sendMessage(ProfileItems.text("У игрока «" + data.name() + "» уже есть префикс №" + number + " (", NamedTextColor.GRAY)
                        .append(ProfileItems.text(prefix.name(), prefix.color())).append(ProfileItems.text(")", NamedTextColor.GRAY)));
                return;
            }
        } else { // take
            if (!data.revokePrefix(prefix.id())) {
                sender.sendMessage(ProfileItems.text("У игрока «" + data.name() + "» нет префикса №" + number + " (", NamedTextColor.GRAY)
                        .append(ProfileItems.text(prefix.name(), prefix.color())).append(ProfileItems.text(")", NamedTextColor.GRAY)));
                return;
            }
        }
        savePrefix(data);
        Player online = Bukkit.getPlayer(data.owner);
        if (online != null) {
            applyPrefixName(online, data); // Снять/надеть надетый префикс, если затронут именно он.
            if (action.equals("give")) {
                online.sendMessage(ProfileItems.text("Вам выдан префикс №" + number + " ", NamedTextColor.GREEN)
                        .append(ProfileItems.text(prefix.name(), prefix.color()))
                        .append(ProfileItems.text(". Выберите его в /profile.", NamedTextColor.WHITE)));
            } else {
                online.sendMessage(ProfileItems.text("У вас забрали префикс №" + number + " ", NamedTextColor.RED)
                        .append(ProfileItems.text(prefix.name(), prefix.color())));
            }
        }
        refresh(data.owner);
        if (!(sender instanceof Player player) || !player.getUniqueId().equals(data.owner)) {
            sender.sendMessage(ProfileItems.text("Префикс №" + number + " (", NamedTextColor.GRAY)
                    .append(ProfileItems.text(prefix.name(), prefix.color()))
                    .append(ProfileItems.text(action.equals("give") ? ") выдан: " : ") изъят у: ", NamedTextColor.GRAY))
                    .append(ProfileItems.text(data.name(), NamedTextColor.GREEN)));
        }
    }

    /** Выдать или забрать сразу все префиксы (/profile prefix give|take <игрок> all). */
    private void giveTakeAll(CommandSender sender, ProfileData data, String action) throws Exception {
        String name = data.name();
        if (action.equals("give")) {
            int added = 0;
            for (PrefixCatalog.Prefix each : prefixes.list()) if (data.addPrefix(each.id())) added++;
            if (added == 0) {
                sender.sendMessage(ProfileItems.text("У игрока «" + name + "» уже есть все префиксы.", NamedTextColor.GRAY));
                return;
            }
            savePrefix(data);
            Player online = Bukkit.getPlayer(data.owner);
            if (online != null) {
                online.sendMessage(ProfileItems.text("Вам выданы все префиксы (" + added + "). Выберите в /profile.", NamedTextColor.GREEN)
                        .append(ProfileItems.text(" Снимите лишние через Shift + клик.", NamedTextColor.WHITE)));
            }
            refresh(data.owner);
            if (!(sender instanceof Player player) || !player.getUniqueId().equals(data.owner)) {
                sender.sendMessage(ProfileItems.text("Выданы все префиксы (" + added + "): ", NamedTextColor.GRAY)
                        .append(ProfileItems.text(name, NamedTextColor.GREEN)));
            }
            return;
        }
        if (!data.clearPrefixes()) {
            sender.sendMessage(ProfileItems.text("У игрока «" + name + "» нет префиксов.", NamedTextColor.GRAY));
            return;
        }
        savePrefix(data);
        Player online = Bukkit.getPlayer(data.owner);
        if (online != null) {
            applyPrefixName(online, data); // Надетый префикс снят — убираем его и с игрока.
            online.sendMessage(ProfileItems.text("У вас забрали все префиксы.", NamedTextColor.RED));
        }
        refresh(data.owner);
        if (!(sender instanceof Player player) || !player.getUniqueId().equals(data.owner)) {
            sender.sendMessage(ProfileItems.text("У «" + name + "» изъяты все префиксы.", NamedTextColor.GRAY));
        }
    }

    /** /profile case prefix give <ник> — выдать игроку кейс префиксов. */
    private void caseCommand(CommandSender sender, String[] args) throws Exception {
        if (args.length != 4 || !args[1].equalsIgnoreCase("prefix") || !args[2].equalsIgnoreCase("give")) {
            throw new IllegalArgumentException("Использование: /profile case prefix give <ник>");
        }
        Target target = medalTarget(args[3]);
        ProfileData data = profile(target.id(), target.name());
        data.addPrefixCase();
        savePrefix(data);
        Player online = Bukkit.getPlayer(data.owner);
        if (online != null && online.isOnline()) {
            online.sendMessage(ProfileItems.text("Вы получили кейс префиксов!", NamedTextColor.GREEN)
                    .append(ProfileItems.text(" Откройте его в /profile!", NamedTextColor.WHITE)));
            online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
        if (!(sender instanceof Player player) || !player.getUniqueId().equals(data.owner)) {
            sender.sendMessage("§7Кейс префиксов выдан: " + data.name());
        }
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

    private void help(CommandSender sender) {
        sender.sendMessage("§6/profile §7— свой профиль; чужой — ЛКМ по «Открыть профиль» в карточке.");
        if (admin(sender)) {
            sender.sendMessage("§7/profile voice reload — перечитать сообщения записи");
            sender.sendMessage("§7/profile clone — тестовый клон; /profile clone remove — убрать");
            sender.sendMessage("§6/profile medal give <игрок или UUID> <copper|silver|gold> <название> | <заслуга 1> | <заслуга 2>");
            sender.sendMessage("§7/profile medal list <игрок> — список и UUID медалей");
            sender.sendMessage("§7/profile medal take <игрок> <номер|UUID|all> — забрать медаль");
            sender.sendMessage("§7/profile medal reload — применить файлы и сообщения без перезапуска");
            sender.sendMessage("§6/profile case prefix give <игрок> — выдать кейс префиксов");
            sender.sendMessage("§6/profile prefix give|take <игрок> <номер|all> — выдать/забрать префикс по номеру или все сразу");
            sender.sendMessage("§7/profile prefix test [игрок] — проверить глифы ресурспака (картинки, а не квадраты)");
            sender.sendMessage("§6/profile prefix list <игрок> — какие префиксы есть у игрока");
            sender.sendMessage("§6/profile prefix reload — перечитать prefixes.yml");
            sender.sendMessage("§7Префиксы: /profile → «Настроить префикс». Файл: §e" + prefixConfig);
            sender.sendMessage("§7Номер префикса — это порядок записей в prefixes.yml (№1 — первая запись).");
            sender.sendMessage("§7Имя ищется только в локальном кэше сервера; если игрок не найден, укажите UUID.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = List.of();
        if (args.length == 1) options = admin(sender) ? List.of("medal", "case", "prefix", "clone", "voice") : List.of();
        else if (admin(sender) && args.length == 2 && args[0].equalsIgnoreCase("voice")) options = List.of("reload");
        else if (admin(sender) && args.length == 2 && args[0].equalsIgnoreCase("clone")) options = List.of("remove");
        else if (admin(sender) && args[0].equalsIgnoreCase("case")) {
            if (args.length == 2) options = List.of("prefix");
            else if (args.length == 3 && args[1].equalsIgnoreCase("prefix")) options = List.of("give");
            else if (args.length == 4 && args[1].equalsIgnoreCase("prefix") && args[2].equalsIgnoreCase("give"))
                options = Bukkit.getOnlinePlayers().stream()
                        .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                        .map(Player::getName).toList();
        } else if (admin(sender) && args[0].equalsIgnoreCase("medal")) {
            if (args.length == 2) options = List.of("give", "take", "list", "reload");
            else if (args.length == 3 && !args[1].equalsIgnoreCase("reload")) options = Bukkit.getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .map(Player::getName).toList();
            else if (args.length == 4 && args[1].equalsIgnoreCase("give")) options = List.of("copper", "silver", "gold");
            else if (args.length == 4 && args[1].equalsIgnoreCase("take")) options = List.of("all");
        } else if (admin(sender) && args[0].equalsIgnoreCase("prefix")) {
            if (args.length == 2) options = List.of("give", "take", "list", "reload", "test");
            else if (args.length == 3 && args[1].equalsIgnoreCase("test")) options = Bukkit.getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .map(Player::getName).toList();
            else if (args.length == 3 && !args[1].equalsIgnoreCase("reload")) options = Bukkit.getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .map(Player::getName).toList();
            else if (args.length == 4 && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("take"))) {
                List<String> numbers = new ArrayList<>();
                for (int n = 1; n <= prefixes.size(); n++) numbers.add(Integer.toString(n));
                numbers.add("all");
                options = numbers;
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).toList();
    }

    public void disable() {
        stopping = true;
        maintenance.cancel(); cards.disable(); subjects.disable(); voice.disable();
        for (UUID id : editing.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage("§7Редактирование описания отменено: плагин выключается.");
        }
        editing.clear();
        for (Menu menu : new ArrayList<>(menus.values())) {
            Player player = Bukkit.getPlayer(menu.viewer);
            if (player != null && player.getOpenInventory().getTopInventory() == menu.inventory) player.closeInventory();
        }
        // Незавершённые вскрытия кейсов при выключении: отдаём уцелевший префикс без анимации.
        for (CaseRun run : new ArrayList<>(caseRuns.values())) {
            if (run.task != null) run.task.cancel();
            String winner = run.survivor();
            if (winner != null) {
                try {
                    ProfileData data = profile(run.owner, run.owner.toString());
                    if (data.addPrefix(winner)) storage.changed(run.owner);
                } catch (RuntimeException ignored) { }
            }
        }
        caseRuns.clear(); pendingPrefixGrants.clear();
        menus.clear(); queued.clear(); cardClicks.clear(); rightClicks.clear(); storage.shutdown();
        if (tags != null) tags.disable();
    }
}
