package com.yourserver.adaptation;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.injector.PacketConstructor;
import com.comphenix.protocol.utility.MinecraftReflection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Косметика настоящим предметом в слоте шлема — но только на клиенте.
 *
 * Клиент рисует броню не по позиции сущности, а по данным экипировки: предмет
 * из слота HEAD надевается на модель игрока с трансформацией {@code display.head}
 * из модели. Поэтому посадка получается ровно та, что настроена в Blockbench,
 * и никакой «дёрганности» — отдельной сущности, которую надо догонять телепортом
 * каждый тик, здесь просто нет.
 *
 * Серверный слот шлема при этом остаётся пустым: клиенту уходит пакет
 * {@code ClientboundSetEquipmentPacket} (ENTITY_EQUIPMENT) со слотом HEAD,
 * а в инвентаре игрока предмет не появляется и не выпадает при смерти.
 * Настоящий шлем важнее косметики: пока он надет, пакет не отправляется,
 * и косметика вернётся сама, когда шлем снимут.
 *
 * Пакет — это состояние клиента, а не сервера, поэтому его приходится
 * повторять: клиент забывает про него при смене настоящей брони, при входе
 * нового игрока в зону видимости и при возрождении. Отсюда периодическая
 * рассылка раз в секунду.
 *
 * Нужен ProtocolLib (soft-depend). Способов показа у косметики два, и этот —
 * только один из них:
 * <ul>
 *   <li>ProtocolLib не стоит — {@link #create} возвращает null сразу;</li>
 *   <li>ProtocolLib стоит, но пакет не собирается — {@link #create} возвращает
 *       null уже после пробной сборки, а причина уходит в лог;</li>
 *   <li>пакет собирался, а потом рассылка сломалась на ходу — {@link #isBroken}
 *       становится true и {@link Cosmetics} тут же переводит всех обратно на
 *       сущность у головы ({@code onBroken}).</li>
 * </ul>
 * Косметика не пропадает ни в одном из трёх случаев — она всегда рисуется
 * хоть каким-то способом.
 *
 * Известная особенность: пакет приходит и самому владельцу, и клиент применяет
 * его к своей копии инвентаря, поэтому в окне инвентаря владелец может видеть
 * косметику в ячейке шлема. На сервере предмета там нет — при ближайшей
 * синхронизации инвентаря ячейка пустеет.
 */
final class CosmeticEquipment {

    /** Как часто напоминать клиенту про косметику: смена брони, вход игрока, возрождение. */
    private static final int RESEND_TICKS = 20;

    private final JavaPlugin plugin;
    private final ProtocolManager manager;
    private final Method asNmsCopy;
    private final Method pairOf;
    private final Object headSlot;
    private final Map<UUID, ItemStack> worn = new HashMap<>();
    /** Кто переводит косметику обратно на сущность, если рассылка встанет. */
    private final Runnable onBroken;
    private final BukkitTask task;
    /** Один сбой рассылки — и этот способ показа больше не используется. */
    private boolean broken;

    private CosmeticEquipment(JavaPlugin plugin, ProtocolManager manager,
                              Method asNmsCopy, Method pairOf, Object headSlot,
                              Runnable onBroken) {
        this.plugin = plugin;
        this.manager = manager;
        this.asNmsCopy = asNmsCopy;
        this.pairOf = pairOf;
        this.headSlot = headSlot;
        this.onBroken = onBroken;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::resend, RESEND_TICKS, RESEND_TICKS);
    }

    /**
     * Готовый отправитель или null: ProtocolLib не стоит, либо с ним не
     * получилось собрать пакет экипировки. Перед возвратом пакет собирается
     * вхолостую — так несовместимость видна сразу в логе запуска, а не в тот
     * момент, когда игрок наденет косметику и увидит пустую голову.
     */
    static CosmeticEquipment create(JavaPlugin plugin, Runnable onBroken) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().info("ProtocolLib не найден — косметика рисуется сущностью у головы. "
                    + "Чтобы она надевалась предметом в слот шлема (ровно с посадкой из Blockbench), "
                    + "поставьте ProtocolLib для вашей версии сервера.");
            return null;
        }
        try {
            ProtocolManager manager = ProtocolLibrary.getProtocolManager();
            // Пакет сервера: CraftItemStack лежит в его же пакете, версия не важна.
            String craft = Bukkit.getServer().getClass().getPackage().getName();
            Method asNmsCopy = Class.forName(craft + ".inventory.CraftItemStack")
                    .getMethod("asNMSCopy", ItemStack.class);
            // Слот экипировки и пара «слот + предмет» — внутренние типы сервера.
            Class<?> slots = MinecraftReflection.getMinecraftClass("world.entity.EquipmentSlot");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object head = Enum.valueOf((Class) slots, "HEAD");
            Method pairOf = Class.forName("com.mojang.datafixers.util.Pair")
                    .getMethod("of", Object.class, Object.class);
            CosmeticEquipment equipment =
                    new CosmeticEquipment(plugin, manager, asNmsCopy, pairOf, head, onBroken);
            equipment.packet(-1, null); // пробная сборка: несовместимость видна сразу
            plugin.getLogger().info("Косметика надевается предметом в слот шлема (ProtocolLib).");
            return equipment;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING,
                    "ProtocolLib на месте, но пакет экипировки не собрался — косметика пойдёт сущностью "
                            + "у головы. Скорее всего, версия ProtocolLib не подходит к версии сервера.", ex);
            return null;
        }
    }

    /** Рассылка встала — {@link Cosmetics} должен вернуть косметику на сущность. */
    boolean isBroken() {
        return broken;
    }

    /** Надеть косметику; рассылка идёт сразу, не дожидаясь следующего цикла. */
    void wear(Player player, ItemStack item) {
        worn.put(player.getUniqueId(), item.clone());
        send(player);
    }

    /** Снять косметику: клиенту уходит пустой слот шлема. */
    void clear(Player player) {
        if (worn.remove(player.getUniqueId()) == null) return;
        send(player);
    }

    boolean isEmpty() {
        return worn.isEmpty();
    }

    void disable() {
        task.cancel();
        for (UUID id : new ArrayList<>(worn.keySet())) {
            Player player = Bukkit.getPlayer(id);
            worn.remove(id);
            if (player != null && player.isOnline()) send(player);
        }
    }

    private void resend() {
        if (broken || worn.isEmpty()) return;
        for (UUID id : new ArrayList<>(worn.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                worn.remove(id);
                continue;
            }
            send(player);
        }
    }

    /**
     * Отправить wearer'у и всем, кто его видит. Пакет один на всех — различается
     * только получатель.
     */
    private void send(Player wearer) {
        if (broken) return;
        // Настоящий шлем важнее: пока он надет, косметику не показываем.
        if (wearer.getInventory().getHelmet() != null) return;
        ItemStack item = worn.get(wearer.getUniqueId());
        try {
            PacketContainer packet = packet(wearer.getEntityId(), item);
            for (Player viewer : wearer.getWorld().getPlayers()) {
                manager.sendServerPacket(viewer, packet);
            }
        } catch (Throwable ex) {
            fail(ex);
        }
    }

    /**
     * Рассылка не удалась. Способ показа выключаем и сразу отдаём косметику
     * обратно {@link Cosmetics} — иначе игроки остались бы с пустой головой:
     * пакет не уходит, а сущность никто не создаёт.
     */
    private void fail(Throwable ex) {
        if (broken) return;
        broken = true;
        task.cancel();
        plugin.getLogger().log(Level.WARNING,
                "Не удалось отправить косметику в слот шлема — возвращаю её на сущность у головы", ex);
        onBroken.run();
    }

    /**
     * {@code ClientboundSetEquipmentPacket(int entity, List<Pair<EquipmentSlot, ItemStack>>)}:
     * поле 0 — id сущности, поле 1 — список пар. Пустой предмет в паре — это
     * «в слоте ничего нет».
     *
     * Конструктора без аргументов у пакета нет, поэтому сначала пробуем обычный
     * {@code createPacket} (ProtocolLib умеет подставлять значения по умолчанию),
     * а если он не справился — собираем пакет через {@link PacketConstructor},
     * передав аргументы прямо в конструктор.
     */
    private PacketContainer packet(int entityId, ItemStack item) throws ReflectiveOperationException {
        Object stack = item == null ? null : asNmsCopy.invoke(null, item);
        List<Object> slots = new ArrayList<>(1);
        slots.add(pairOf.invoke(null, headSlot, stack));
        try {
            PacketContainer packet = manager.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
            packet.getIntegers().write(0, entityId);
            packet.getModifier().write(1, slots);
            return packet;
        } catch (Throwable noUsableConstructor) {
            return PacketConstructor.DEFAULT
                    .withPacket(PacketType.Play.Server.ENTITY_EQUIPMENT, new Object[]{entityId, slots})
                    .createPacket();
        }
    }
}
