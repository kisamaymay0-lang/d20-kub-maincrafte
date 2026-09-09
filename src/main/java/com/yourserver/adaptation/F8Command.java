package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Меню F8.
 *
 * ВАЖНО: заголовки окон (MAIN_TITLE и т.д.) являются идентификаторами меню
 * в обработчиках кликов — они НАМЕРЕННО не вынесены в config.yml.
 * Всё остальное оформление (рамки, цвета, кнопки, лор) читается из gui.*.
 */
public class F8Command implements CommandExecutor, Listener {

    private static final String MAIN_TITLE = "§8F8";
    private static final String ENCHANT_TITLE = "§8Новые чарки";
    private static final String BLOCK_TITLE = "§8Новые блоки";
    private static final String ITEM_TITLE = "§8Новые предметы";

    private final AdaptationPlugin plugin;
    private final DiceRollListener diceRollListener;
    private final FlaskListener flaskListener;
    private final RollbackListener rollbackListener;
    private final CopperBlockListener copperBlockListener;

    private final NamespacedKey menuKey;

    public F8Command(
            AdaptationPlugin plugin,
            DiceRollListener diceRollListener,
            FlaskListener flaskListener,
            RollbackListener rollbackListener,
            CopperBlockListener copperBlockListener
    ) {
        this.plugin = plugin;
        this.diceRollListener = diceRollListener;
        this.flaskListener = flaskListener;
        this.rollbackListener = rollbackListener;
        this.copperBlockListener = copperBlockListener;
        this.menuKey = new NamespacedKey(plugin, "f8_menu");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            return true;
        }

        if (!player.hasPermission("f8.admin")) {
            return true;
        }

        openMainMenu(player);
        return true;
    }

    /* ===================== Окна ===================== */

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                MAIN_TITLE
        );

        drawFrame(inventory, "main");

        inventory.setItem(
                10,
                createMenuItem(
                        Material.ENCHANTED_BOOK,
                        style("gui.colors.enchant", "Новые чарки"),
                        List.of(
                                "&7Новые зачарования",
                                "&8▸ &7Адаптация I-III — защита брони",
                                "&8▸ &7Бросок I — кубик d20 в ударах"
                        ),
                        "enchants"
                )
        );

        inventory.setItem(
                13,
                createMenuItem(
                        Material.WAXED_CHISELED_COPPER,
                        style("gui.colors.block", "Новые блоки"),
                        List.of(
                                "&7Новые блоки",
                                "&8▸ &7Медный нотный блок — своя мелодия",
                                "&8▸ &7Свой инвентарь и редстоун-триггер"
                        ),
                        "blocks"
                )
        );

        inventory.setItem(
                16,
                createMenuItem(
                        Material.POTION,
                        style("gui.colors.item", "Новые предметы"),
                        List.of(
                                "&7Новые предметы",
                                "&8▸ &7Флакон с водой — смывает отравление",
                                "&8▸ &7Флакон с отравлением — яд на мече"
                        ),
                        "items"
                )
        );

        // Единая кнопка «Закрыть» по центру нижней рамки
        inventory.setItem(
                22,
                createCloseButton()
        );

        playOpenEffect(player);
        player.openInventory(inventory);
    }

    private void openEnchantMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                36,
                ENCHANT_TITLE
        );

        drawFrame(inventory, "enchant");

        inventory.setItem(
                10,
                createEnchantmentBook(
                        "Адаптация I",
                        "adaptation_1"
                )
        );

        inventory.setItem(
                12,
                createEnchantmentBook(
                        "Адаптация II",
                        "adaptation_2"
                )
        );

        inventory.setItem(
                14,
                createEnchantmentBook(
                        "Адаптация III",
                        "adaptation_3"
                )
        );

        inventory.setItem(
                16,
                createEnchantmentBook(
                        "Бросок I",
                        "d20"
                )
        );

        inventory.setItem(
                22,
                createMenuItem(
                        Material.TOTEM_OF_UNDYING,
                        style("gui.colors.enchant", "Тотем «Откат I»"),
                        List.of(
                                "&7Тотем бессмертия с чаром Откат I",
                                "&8▸ &7При смерти возвращает вас",
                                "&8▸ &7на позицию 5 секунд назад"
                        ),
                        null
                )
        );

        inventory.setItem(
                30,
                createBackButton()
        );

        inventory.setItem(
                32,
                createCloseButton()
        );

        playOpenEffect(player);
        player.openInventory(inventory);
    }

    private void openBlockMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                BLOCK_TITLE
        );

        drawFrame(inventory, "blocks");

        inventory.setItem(
                13,
                createMenuItem(
                        Material.WAXED_CHISELED_COPPER,
                        style("gui.colors.block", "Медный нотный блок"),
                        List.of(
                                "&7Нажмите, чтобы получить блок",
                                "&8▸ &7ПКМ — открыть инвентарь блока",
                                "&8▸ &7Сигнал редстоуна — проиграть мелодию"
                        ),
                        "copper_note_block"
                )
        );

        inventory.setItem(
                20,
                createBackButton()
        );

        inventory.setItem(
                24,
                createCloseButton()
        );

        playOpenEffect(player);
        player.openInventory(inventory);
    }

    private void openItemMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                ITEM_TITLE
        );

        drawFrame(inventory, "items");

        inventory.setItem(
                11,
                createFlaskMenuItem(
                        flaskListener.createWaterFlask(),
                        "water_flask"
                )
        );

        inventory.setItem(
                15,
                createFlaskMenuItem(
                        flaskListener.createPoisonFlask(),
                        "poison_flask"
                )
        );

        inventory.setItem(
                20,
                createBackButton()
        );

        inventory.setItem(
                24,
                createCloseButton()
        );

        playOpenEffect(player);
        player.openInventory(inventory);
    }

    /* ===================== Создание предметов ===================== */

    private ItemStack createEnchantmentBook(
            String enchantment,
            String id
    ) {
        return createMagicBook(
                "Чародейская книга",
                enchantment,
                id
        );
    }

    /** Книга-обложка меню с чаром (ПДК f8_menu) и книга для выдачи. */
    private ItemStack createMagicBook(
            String displayName,
            String enchantment,
            String id
    ) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);

        ItemMeta meta = book.getItemMeta();

        if (meta == null) {
            return book;
        }

        meta.setDisplayName(MessageUtils.legacy(
                style("gui.colors.enchant", displayName)
        ));
        meta.setLore(
                Collections.singletonList(
                        MessageUtils.legacy(
                                style("gui.colors.tag", enchantment)
                        )
                )
        );
        meta.setEnchantmentGlintOverride(true);

        if (id != null) {
            meta.getPersistentDataContainer().set(
                    menuKey,
                    PersistentDataType.STRING,
                    id
            );
        }

        book.setItemMeta(meta);

        return book;
    }

    private ItemStack createFlaskMenuItem(
            ItemStack item,
            String id
    ) {
        ItemStack result = item.clone();

        ItemMeta meta = result.getItemMeta();

        if (meta != null) {
            meta.getPersistentDataContainer().set(
                    menuKey,
                    PersistentDataType.STRING,
                    id
            );

            result.setItemMeta(meta);
        }

        return result;
    }

    private ItemStack createMenuItem(
            Material material,
            String name,
            List<String> lore,
            String id
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(MessageUtils.legacy(name));

        if (lore != null && !lore.isEmpty()) {
            List<String> styledLore = new ArrayList<>(lore.size());
            for (String line : lore) {
                styledLore.add(MessageUtils.legacy(line));
            }
            meta.setLore(styledLore);
        }

        if (id != null) {
            meta.getPersistentDataContainer().set(
                    menuKey,
                    PersistentDataType.STRING,
                    id
            );
        }

        item.setItemMeta(meta);

        return item;
    }

    /* ===================== Рамки и кнопки (gui.*) ===================== */

    private ItemStack createBackButton() {
        String materialName = plugin.getConfig().getString(
                "gui.buttons.back-material",
                "ARROW"
        );

        return createMenuItem(
                materialOr(materialName, Material.ARROW),
                plugin.getConfig().getString(
                        "gui.buttons.back-name",
                        "&#C8CFD8Назад"
                ),
                plugin.getConfig().getStringList(
                        "gui.buttons.back-lore"
                ),
                "back"
        );
    }

    private ItemStack createCloseButton() {
        String materialName = plugin.getConfig().getString(
                "gui.buttons.close-material",
                "BARRIER"
        );

        return createMenuItem(
                materialOr(materialName, Material.BARRIER),
                plugin.getConfig().getString(
                        "gui.buttons.close-name",
                        "&#FF6B6BЗакрыть"
                ),
                plugin.getConfig().getStringList(
                        "gui.buttons.close-lore"
                ),
                "close"
        );
    }

    /** Цвет + текст из gui.colors.* (поддержка & и &#RRGGBB). */
    private String style(String colorPath, String text) {
        String color = plugin.getConfig().getString(colorPath, "");
        return color + text;
    }

    private Material materialOr(String name, Material fallback) {
        Material material = Material.matchMaterial(name, false);
        return material == null ? fallback : material;
    }

    /** Рамка окна из стеклянных панелей (материал: gui.frames.{key}). */
    private void drawFrame(Inventory inventory, String frameKey) {
        String materialName = plugin.getConfig().getString(
                "gui.frames." + frameKey,
                "BLACK_STAINED_GLASS_PANE"
        );

        Material frameMaterial =
                materialOr(materialName, Material.BLACK_STAINED_GLASS_PANE);

        ItemStack pane = new ItemStack(frameMaterial);

        ItemMeta meta = pane.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }

        int size = inventory.getSize();
        int rows = size / 9;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                boolean border = row == 0
                        || row == rows - 1
                        || col == 0
                        || col == 8;

                if (border) {
                    inventory.setItem(row * 9 + col, pane);
                }
            }
        }
    }

    /** Небольшой визуальный эффект при открытии меню (gui.open-effect). */
    private void playOpenEffect(Player player) {
        if (!MessageUtils.bool("gui.open-effect", true)
                || !MessageUtils.particles()) {
            return;
        }

        Location center = player.getLocation().add(0, 1.1, 0);

        player.getWorld().spawnParticle(
                Particle.END_ROD,
                center,
                16,
                0.4,
                0.35,
                0.4,
                0.02
        );

        if (MessageUtils.sounds()) {
            player.playSound(
                    player.getLocation(),
                    Sound.UI_BUTTON_CLICK,
                    0.6f,
                    1.4f
            );
        }
    }

    /* ===================== Выдача ===================== */

    private void giveItem(Player player, ItemStack item) {
        if (item == null) {
            return;
        }

        var leftovers = player.getInventory().addItem(item);

        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    leftover
            );
        }
    }

    /** Человеческое название предмета для уведомления в чате. */
    private String itemLabel(String id) {
        return switch (id) {
            case "adaptation_1" -> "Книга «Адаптация I»";
            case "adaptation_2" -> "Книга «Адаптация II»";
            case "adaptation_3" -> "Книга «Адаптация III»";
            case "d20" -> "Книга «Бросок I»";
            case "water_flask" -> "Флакон с водой";
            case "poison_flask" -> "Флакон с отравлением";
            case "copper_note_block" -> "Медный нотный блок";
            default -> id;
        };
    }

    private void giveAndNotify(Player player, ItemStack item, String id) {
        giveItem(player, item);
        player.closeInventory();
        MessageUtils.send(player, "menu.given", "{item}", itemLabel(id));
    }

    /* ===================== Обработчики кликов ===================== */

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.equals(MAIN_TITLE)
                && !title.equals(ENCHANT_TITLE)
                && !title.equals(BLOCK_TITLE)
                && !title.equals(ITEM_TITLE)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        String id = getId(clicked);

        if (id == null) {
            return;
        }

        switch (id) {
            case "enchants" -> openEnchantMenu(player);

            case "blocks" -> openBlockMenu(player);

            case "items" -> openItemMenu(player);

            case "back" -> openMainMenu(player);

            case "close" -> player.closeInventory();

            case "adaptation_1" -> giveAndNotify(
                    player,
                    createAdaptationBook(1),
                    "adaptation_1"
            );

            case "adaptation_2" -> giveAndNotify(
                    player,
                    createAdaptationBook(2),
                    "adaptation_2"
            );

            case "adaptation_3" -> giveAndNotify(
                    player,
                    createAdaptationBook(3),
                    "adaptation_3"
            );

            case "d20" -> giveAndNotify(
                    player,
                    createD20Book(),
                    "d20"
            );

            case "water_flask" -> giveAndNotify(
                    player,
                    flaskListener.createWaterFlask(),
                    "water_flask"
            );

            case "poison_flask" -> giveAndNotify(
                    player,
                    flaskListener.createPoisonFlask(),
                    "poison_flask"
            );

            case "copper_note_block" -> giveAndNotify(
                    player,
                    createCopperBlock(),
                    "copper_note_block"
            );
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();

        if (title.equals(MAIN_TITLE)
                || title.equals(ENCHANT_TITLE)
                || title.equals(BLOCK_TITLE)
                || title.equals(ITEM_TITLE)) {
            event.setCancelled(true);
        }
    }

    /* ===================== Книги и блоки (выдача) ===================== */

    private ItemStack createAdaptationBook(int level) {
        String roman = switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            default -> "III";
        };

        return createMagicBook(
                "Чародейская книга",
                "Адаптация " + roman,
                null
        );
    }

    private ItemStack createD20Book() {
        return createMagicBook(
                "Чародейская книга",
                "Бросок I",
                null
        );
    }

    private ItemStack createCopperBlock() {
        return new ItemStack(
                Material.WAXED_CHISELED_COPPER
        );
    }

    private String getId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer().get(
                menuKey,
                PersistentDataType.STRING
        );
    }
}
