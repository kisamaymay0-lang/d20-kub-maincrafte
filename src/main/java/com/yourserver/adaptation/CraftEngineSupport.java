package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Общий доступ к кастомным блокам CraftEngine.
 *
 * <h2>Как CraftEngine хранит блоки</h2>
 *
 * Блок описывается в <b>паке</b>: {@code plugins/CraftEngine/resources/<имя пака>/}
 * с {@code pack.yml}, папкой {@code configuration/} (все {@code .yml} и
 * {@code .json}, рекурсивно по подпапкам) и папкой {@code resourcepack/}
 * (модели и текстуры, структура обычного ресурспака). Файл, просто брошенный в
 * {@code plugins/CraftEngine/blocks/}, плагин не читает никогда.
 *
 * Внутри конфига у блока обязательна только секция {@code state}:
 * {@code auto_state} — группа ванильных состояний-носителей (например
 * {@code mushroom_stem}), {@code model.path} — путь к уже существующей модели
 * (по одному {@code path} CraftEngine ничего не генерирует).
 *
 * Какие id нужны этому плагину и каким конфигом они описываются —
 * {@value #CONFIG_DOC}.
 *
 * <h2>Про классы CraftEngine</h2>
 *
 * CraftEngine — зависимость необязательная ({@code softdepend}). Все обращения
 * к его API сидят во вложенном классе {@link Bridge}: он загружается только при
 * первом вызове, а вызываем мы его лишь после {@link #available()}. На сервере
 * без CraftEngine этот класс загружается спокойно, и {@code NoClassDefFoundError}
 * быть не может — ни на старте, ни в тике, который идёт на каждый блок.
 */
final class CraftEngineSupport {

    /** Где описаны блоки, которые плагин ждёт от CraftEngine. */
    static final String CONFIG_DOC = "docs/craftengine-blocks.md";

    /** Имя плагина в plugin.yml CraftEngine (и в spigot-, и в paper-загрузчике). */
    private static final String PLUGIN_NAME = "CraftEngine";

    /** Есть ли API CraftEngine в classpath. Считается один раз. */
    private static final boolean CLASS_PRESENT = probe();

    private CraftEngineSupport() { }

    private static boolean probe() {
        try {
            Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Стоит ли CraftEngine и включён ли. */
    static boolean available() {
        if (!CLASS_PRESENT) return false;
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Зарегистрирован ли блок в CraftEngine. {@code byId} отдаёт null, когда
     * плагин стоит, а блок в конфиге не прописан или конфиг не загрузился —
     * это и есть самая частая причина «блок не ставится».
     */
    static boolean registered(String id) {
        return available() && Bridge.registered(id);
    }

    /** Зарегистрированы ли все перечисленные блоки. */
    static boolean registeredAll(String... ids) {
        if (!available()) return false;
        for (String id : ids) {
            if (!Bridge.registered(id)) return false;
        }
        return true;
    }

    /**
     * Короткий диагноз для лога: почему блоков нет. Пустая строка — всё в
     * порядке.
     */
    static String diagnosis(String... ids) {
        if (!CLASS_PRESENT) {
            return "API CraftEngine нет в classpath: на сервере не установлен CraftEngine "
                    + "(https://modrinth.com/plugin/craftengine).";
        }
        if (!available()) {
            return "плагин " + PLUGIN_NAME + " не найден или не включён.";
        }
        StringBuilder missing = new StringBuilder();
        for (String id : ids) {
            if (!Bridge.registered(id)) {
                if (missing.length() > 0) missing.append(", ");
                missing.append(id);
            }
        }
        if (missing.length() > 0) {
            return "в CraftEngine не зарегистрированы блоки: " + missing
                    + ". Они описываются в паке plugins/CraftEngine/resources/<имя пака>/"
                    + "configuration/ — образец и список id: " + CONFIG_DOC
                    + ". После правки: /ce reload all.";
        }
        return "";
    }

    /**
     * id кастомного блока на этом месте или null, если блок ванильный.
     * Именно так отличают свой блок от чужого: «блок кастомный» — ещё не
     * «это наш блок».
     */
    static String idAt(Block block) {
        if (!available() || block == null) return null;
        return Bridge.idAt(block);
    }

    /** Поставить блок; звук места не нужен — свои звуки играют слушатели плагина. */
    static boolean place(Block block, String id) {
        if (!available()) return false;
        return Bridge.place(block, id);
    }

    /** Убрать кастомный блок из мира. */
    static boolean remove(Block block) {
        if (!available()) return false;
        return Bridge.remove(block);
    }

    /**
     * Единственное место, где упоминаются классы CraftEngine. Загружается при
     * первом обращении, а обращаемся мы только после {@link #available()}.
     */
    private static final class Bridge {

        private static net.momirealms.craftengine.core.util.Key key(String id) {
            return net.momirealms.craftengine.core.util.Key.of(id);
        }

        private static boolean registered(String id) {
            return net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.byId(key(id)) != null;
        }

        private static String idAt(Block block) {
            net.momirealms.craftengine.core.block.ImmutableBlockState state =
                    net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(block);
            if (state == null) return null;
            net.momirealms.craftengine.core.util.Key id = state.owner().value().id();
            return id.namespace() + ":" + id.value();
        }

        private static boolean place(Block block, String id) {
            return net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
                    .place(block.getLocation(), key(id), false);
        }

        private static boolean remove(Block block) {
            return net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.remove(block);
        }
    }
}
