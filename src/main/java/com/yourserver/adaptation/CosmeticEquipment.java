package com.yourserver.adaptation;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.utility.MinecraftReflection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * Нужен ProtocolLib (soft-depend). Без него — или если пакет не собрался —
 * {@link #create} возвращает null, и косметика по-прежнему рисуется
 * сущностью-отображением у головы ({@link Cosmetics}).
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
    private final BukkitTask task;
    /** Один сбой — и больше не пробуем: косметика остаётся на сущности. */
    private boolean broken;

    private CosmeticEquipment(JavaPlugin plugin, ProtocolManager manager,
                              Method asNmsCopy, Method pairOf, Object headSlot) {
        this.plugin = plugin;
        this.manager = manager;
        this.asNmsCopy = asNmsCopy;
        this.pairOf = pairOf;
        this.headSlot = headSlot;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::resend, RESEND_TICKS, RESEND_TICKS);
    }

    /**
     * Готовый отправитель или null: ProtocolLib не стоит, либо с ним не
     * получилось собрать пакет. Во втором случае причина уходит в лог — по ней
     * видно, что именно не так.
     */
    static CosmeticEquipment create(JavaPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) return null;
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
            CosmeticEquipment equipment = new CosmeticEquipment(plugin, manager, asNmsCopy, pairOf, head);
            plugin.getLogger().info("Косметика надевается предметом в слот шлема (ProtocolLib).");
            return equipment;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING,
                    "ProtocolLib на месте, но пакет экипировки не собрался — косметика пойдёт сущностью у головы", ex);
            return null;
        }
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
            broken = true;
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось отправить косметику в слот шлема — косметика пойдёт сущностью у головы", ex);
        }
    }

    /**
     * {@code ClientboundSetEquipmentPacket(int entity, List<Pair<EquipmentSlot, ItemStack>>)}:
     * поле 0 — id сущности, поле 1 — список пар. Пустой предмет в паре — это
     * «в слоте ничего нет».
     */
    private PacketContainer packet(int entityId, ItemStack item) throws ReflectiveOperationException {
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getIntegers().write(0, entityId);
        Object stack = item == null ? null : asNmsCopy.invoke(null, item);
        packet.getModifier().write(1, Collections.singletonList(pairOf.invoke(null, headSlot, stack)));
        return packet;
    }
}
