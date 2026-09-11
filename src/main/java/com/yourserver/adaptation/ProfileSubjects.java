package com.yourserver.adaptation;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Реальные игроки и временные приватные манекены для проверки профилей в одиночку. */
final class ProfileSubjects implements Listener {
    record Subject(LivingEntity entity, UUID profile, String name) { }
    private static final class Clone {
        final UUID owner, profile;
        final Mannequin entity;
        final Map<UUID, ProfileData.Vote> testVotes = new HashMap<>();
        ProfileData data;
        long observed = -1;
        Clone(UUID owner, UUID profile, Mannequin entity) { this.owner = owner; this.profile = profile; this.entity = entity; }
    }

    private final JavaPlugin plugin;
    private final Function<Player, ProfileData> originals;
    private final Map<UUID, Clone> byEntity = new HashMap<>();
    private final Map<UUID, Clone> byOwner = new HashMap<>();
    private final Map<UUID, Clone> byProfile = new HashMap<>();
    private final Map<UUID, UUID> knownPreview = new HashMap<>();

    ProfileSubjects(JavaPlugin plugin, Function<Player, ProfileData> originals) {
        this.plugin = plugin; this.originals = originals;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    Subject resolve(Player viewer, Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()
                || !viewer.getWorld().equals(living.getWorld()) || !viewer.canSee(entity) || living.isInvisible()) return null;
        if (living instanceof Player player) {
            if (player.getUniqueId().equals(viewer.getUniqueId()) || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return null;
            return new Subject(player, player.getUniqueId(), player.getName());
        }
        Clone clone = byEntity.get(entity.getUniqueId());
        if (clone == null || !clone.owner.equals(viewer.getUniqueId())) return null;
        return new Subject(living, clone.profile, viewer.getName());
    }

    Subject resolve(Player viewer, UUID entity) {
        Entity found = Bukkit.getEntity(entity);
        return found == null ? null : resolve(viewer, found);
    }

    boolean preview(UUID profile) { return knownPreview.containsKey(profile); }
    boolean cloneEntity(UUID entity) { return byEntity.containsKey(entity); }

    ProfileData profile(UUID id) {
        Clone clone = byProfile.get(id);
        if (clone == null || !clone.entity.isValid()) throw new IllegalStateException("Тестовый профиль закрыт");
        Player owner = Bukkit.getPlayer(clone.owner);
        if (owner == null) throw new IllegalStateException("Владелец клона вышел");
        ProfileData real = originals.apply(owner);
        if (clone.data == null || clone.observed != real.revision()) {
            long previous = clone.data == null ? 0 : clone.data.revision();
            clone.data = real.previewCopy(clone.profile);
            clone.testVotes.forEach(clone.data::vote);
            clone.data.newerThan(previous);
            clone.observed = real.revision();
        }
        return clone.data;
    }

    boolean vote(ProfileData data, UUID voter, ProfileData.Vote vote) {
        if (!data.vote(voter, vote)) return false;
        Clone clone = byProfile.get(data.owner);
        if (clone != null) {
            ProfileData.Vote current = data.voteBy(voter);
            if (current == null) clone.testVotes.remove(voter); else clone.testVotes.put(voter, current);
        }
        return true;
    }

    UUID remove(UUID owner) {
        Clone old = byOwner.remove(owner);
        if (old == null) return null;
        byProfile.remove(old.profile); byEntity.remove(old.entity.getUniqueId());
        old.entity.remove();
        return old.profile;
    }

    void spawn(Player owner) {
        Vector forward = owner.getEyeLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward = new Vector(0, 0, 1);
        Location at = owner.getLocation().add(forward.normalize().multiply(3));
        if (!owner.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                || !at.getBlock().isPassable() || !at.clone().add(0, 1, 0).getBlock().isPassable()) {
            throw new IllegalStateException("Перед тобой недостаточно свободного места");
        }
        remove(owner.getUniqueId());
        at.setYaw(owner.getLocation().getYaw() + 180); at.setPitch(0);
        Mannequin entity = owner.getWorld().spawn(at, Mannequin.class, mannequin -> {
            mannequin.setProfile(ResolvableProfile.resolvableProfile(owner.getPlayerProfile()));
            mannequin.setSkinParts(owner.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS));
            mannequin.setDescription(null);
            mannequin.setImmovable(true); mannequin.setGravity(false);
            mannequin.setInvulnerable(true); mannequin.setSilent(true); mannequin.setCollidable(false);
            mannequin.setPersistent(false); mannequin.setVisibleByDefault(false);
            mannequin.customName(Component.text(owner.getName(), NamedTextColor.WHITE)); mannequin.setCustomNameVisible(true);
            ItemStack[] armor = owner.getInventory().getArmorContents();
            for (int i = 0; i < armor.length; i++) if (armor[i] != null) armor[i] = armor[i].clone();
            mannequin.getEquipment().setArmorContents(armor);
            mannequin.getEquipment().setItemInMainHand(owner.getInventory().getItemInMainHand().clone());
            mannequin.getEquipment().setItemInOffHand(owner.getInventory().getItemInOffHand().clone());
        });
        UUID profile = UUID.nameUUIDFromBytes(("f8-profile-preview:" + owner.getUniqueId()).getBytes(StandardCharsets.UTF_8));
        Clone clone = new Clone(owner.getUniqueId(), profile, entity);
        byOwner.put(owner.getUniqueId(), clone); byProfile.put(profile, clone); byEntity.put(entity.getUniqueId(), clone);
        knownPreview.put(profile, owner.getUniqueId());
        owner.showEntity(plugin, entity);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void damage(EntityDamageEvent event) { if (cloneEntity(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void interact(PlayerInteractEntityEvent event) { if (cloneEntity(event.getRightClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void interactAt(PlayerInteractAtEntityEvent event) { if (cloneEntity(event.getRightClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void death(EntityDeathEvent event) {
        Clone clone = byEntity.get(event.getEntity().getUniqueId());
        if (clone != null) { event.getDrops().clear(); event.setDroppedExp(0); remove(clone.owner); }
    }
    void disable() {
        for (UUID owner : java.util.List.copyOf(byOwner.keySet())) remove(owner);
        knownPreview.clear();
    }
}
