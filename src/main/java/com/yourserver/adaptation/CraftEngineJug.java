package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Кувшин как настоящий кастомный блок CraftEngine.
 *
 * Все прежние носители проваливались по одной причине: ресурспак перерисовывает
 * блок целиком, а не конкретный экземпляр. Стекло превращало в кувшин каждое
 * окно сервера, рамка портала Края есть в каждой крепости. CraftEngine
 * регистрирует блок по-настоящему, со своим id, которого в ванили нет, поэтому
 * кувшин существует ровно там, где его поставили.
 *
 * <h2>Как устроен блок CraftEngine</h2>
 *
 * Блок описывается в <b>паке</b>: {@code plugins/CraftEngine/resources/<имя пака>/}
 * с {@code pack.yml}, папкой {@code configuration/} (yml-конфиги, читаются
 * рекурсивно из всех подпапок) и папкой {@code resourcepack/} (модели и
 * текстуры, структура обычного ресурспака). Файл, положенный просто в
 * {@code plugins/CraftEngine/blocks/}, плагин не читает никогда — именно так
 * кувшин не появлялся в 10.13.
 *
 * Внутри конфига у блока обязательна только секция {@code state}:
 * {@code auto_state} — группа ванильных состояний-носителей (например
 * {@code mushroom_stem}), {@code model.path} — путь к модели. По одному только
 * {@code path} CraftEngine ничего не генерирует: модель должна уже лежать в
 * паке, поэтому модель кувшина и её текстуры лежат в
 * {@code craftengine/resources/f8_jug/resourcepack/}.
 *
 * <h2>Про классы CraftEngine</h2>
 *
 * CraftEngine — зависимость необязательная ({@code softdepend}). Все обращения
 * к его API сидят во вложенном классе {@link Bridge}: он загружается только при
 * первом вызове, а вызываем мы его лишь после {@link #available()}. На сервере
 * без CraftEngine этот класс загружается спокойно, и {@code NoClassDefFoundError}
 * быть не может — ни на старте, ни в тике, который идёт на каждый кувшин.
 */
final class CraftEngineJug {

    static final String EMPTY_ID = "f8resurs:ancient_jug";
    static final String FILLED_ID = "f8resurs:ancient_jug_filled";

    /** Куда кладётся конфиг кувшина. В сообщениях — чтобы не искать по вики. */
    static final String PACK_CONFIG = "plugins/CraftEngine/resources/f8_jug/configuration/blocks/ancient_jug.yml";

    /** Имя плагина в plugin.yml CraftEngine (и в spigot-, и в paper-загрузчике). */
    private static final String PLUGIN_NAME = "CraftEngine";

    /** Есть ли API CraftEngine в classpath. Считается один раз. */
    private static final boolean CLASS_PRESENT = probe();

    private CraftEngineJug() { }

    private static boolean probe() {
        try {
            Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Стоит ли CraftEngine и включён ли. Без него кувшин поставить нельзя. */
    static boolean available() {
        if (!CLASS_PRESENT) return false;
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return plugin != null && plugin.isEnabled();
    }

    /** id кувшина под содержимое — строкой, для лога и сообщений. */
    static String keyName(int count) {
        return count > 0 ? FILLED_ID : EMPTY_ID;
    }

    /**
     * Зарегистрирован ли кувшин в CraftEngine. {@code byId} отдаёт null, когда
     * плагин стоит, а блок в конфиге не прописан или конфиг не загрузился —
     * это и есть самая частая причина «кувшин не ставится».
     */
    static boolean registered(int count) {
        return available() && Bridge.registered(count);
    }

    /**
     * Короткий диагноз для лога: почему кувшин не работает. Пустая строка —
     * всё в порядке.
     */
    static String diagnosis() {
        if (!CLASS_PRESENT) {
            return "API CraftEngine нет в classpath: на сервере не установлен CraftEngine "
                    + "(https://modrinth.com/plugin/craftengine).";
        }
        if (!available()) {
            return "плагин " + PLUGIN_NAME + " не найден или не включён.";
        }
        if (!Bridge.registered(0)) {
            return "блок " + EMPTY_ID + " не зарегистрирован в CraftEngine. Конфиг должен лежать в "
                    + PACK_CONFIG + " (вместе с pack.yml и папкой resourcepack/ — CraftEngine читает "
                    + "только паки из resources/). После правки: /ce reload all.";
        }
        if (!Bridge.registered(1)) {
            return "блок " + FILLED_ID + " не зарегистрирован в CraftEngine: в " + PACK_CONFIG
                    + " есть только пустой кувшин.";
        }
        return "";
    }

    /**
     * id кастомного блока на этом месте или null, если блок ванильный.
     * Именно так отличают кувшин от чужого блока CraftEngine:
     * «блок кастомный» — ещё не «это наш кувшин».
     */
    static String idAt(Block block) {
        if (!available() || block == null) return null;
        return Bridge.idAt(block);
    }

    /** Наш ли это кувшин — пустой или налитый. */
    static boolean isJug(Block block) {
        String id = idAt(block);
        return EMPTY_ID.equals(id) || FILLED_ID.equals(id);
    }

    /** Поставить кувшин; звук места не нужен — свой играет AncientJug. */
    static boolean place(Block block, int count) {
        if (!available()) return false;
        return Bridge.place(block, count);
    }

    /** Убрать кувшин из мира. Дроп не заказываем — его роняет AncientJug. */
    static boolean remove(Block block) {
        if (!available()) return false;
        return Bridge.remove(block);
    }

    /**
     * Единственное место, где упоминаются классы CraftEngine. Загружается при
     * первом обращении, а обращаемся мы только после {@link #available()}.
     */
    private static final class Bridge {

        private static net.momirealms.craftengine.core.util.Key key(int count) {
            return net.momirealms.craftengine.core.util.Key.of(keyName(count));
        }

        private static boolean registered(int count) {
            return net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.byId(key(count)) != null;
        }

        private static String idAt(Block block) {
            net.momirealms.craftengine.core.block.ImmutableBlockState state =
                    net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(block);
            if (state == null) return null;
            net.momirealms.craftengine.core.util.Key id = state.owner().value().id();
            return id.namespace() + ":" + id.value();
        }

        private static boolean place(Block block, int count) {
            return net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
                    .place(block.getLocation(), key(count), false);
        }

        private static boolean remove(Block block) {
            return net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.remove(block);
        }
    }
}
