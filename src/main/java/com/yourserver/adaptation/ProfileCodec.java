package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Профиль v2 и отдельный файл медалей. v1 читается для безопасного переноса данных 9.3. */
final class ProfileCodec {
    record Decoded(ProfileData data, boolean legacy) { }
    private ProfileCodec() { }

    /** Старый формат оставлен для проверки миграции и чтения резервных копий. */
    static String encode(ProfileData data) {
        YamlConfiguration yaml = profileYaml(data, 1);
        writeMedals(yaml, data);
        return yaml.saveToString();
    }

    static String encodeProfile(ProfileData data) { return profileYaml(data, 2).saveToString(); }

    static String encodeMedals(ProfileData data) {
        YamlConfiguration yaml = identity(data, 1);
        yaml.options().setHeader(List.of("Медали игрока. После правки: /profile medal reload",
                "Удалите запись UUID, чтобы забрать медаль. Для добавления удобнее команда give.",
                "Дата awarded-at хранится в миллисекундах Unix. UUID существующих медалей не меняйте."));
        writeMedals(yaml, data);
        return yaml.saveToString();
    }

    private static YamlConfiguration identity(ProfileData data, int version) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", version);
        yaml.set("owner", data.owner.toString());
        yaml.set("name", data.name());
        return yaml;
    }

    private static YamlConfiguration profileYaml(ProfileData data, int version) {
        YamlConfiguration yaml = identity(data, version);
        yaml.set("description", data.description());
        data.votes().forEach((voter, vote) -> yaml.set("votes." + voter, vote.name()));
        yaml.set("medal-history.claimed", new ArrayList<>(data.rewardHistory()));
        data.notificationHistory().forEach((id, when) -> yaml.set("medal-history.announced." + id, when));
        List<String> slots = new ArrayList<>();
        for (UUID id : data.layout()) slots.add(id == null ? "" : id.toString());
        yaml.set("display", slots);
        return yaml;
    }

    private static void writeMedals(YamlConfiguration yaml, ProfileData data) {
        if (data.medals().isEmpty()) yaml.createSection("medals");
        data.medals().forEach((id, medal) -> {
            String root = "medals." + id;
            yaml.set(root + ".metal", medal.metal().name());
            yaml.set(root + ".title", medal.title());
            yaml.set(root + ".reasons", medal.reasons());
            yaml.set(root + ".awarded-at", medal.awardedAt());
            yaml.set(root + ".source", medal.source());
        });
    }

    private static YamlConfiguration read(UUID owner, String input) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(input);
        if (!owner.toString().equals(yaml.getString("owner"))) throw new IllegalArgumentException("Неверный владелец файла");
        return yaml;
    }

    static ProfileData decode(UUID owner, String input) throws InvalidConfigurationException { return decodeState(owner, input).data(); }

    static Decoded decodeState(UUID owner, String input) throws InvalidConfigurationException {
        YamlConfiguration yaml = read(owner, input);
        int version = yaml.getInt("version");
        if (version != 1 && version != 2) throw new IllegalArgumentException("Неверная версия профиля");
        ProfileData data = new ProfileData(owner, yaml.getString("name", owner.toString()));
        data.describe(owner, yaml.getString("description", ""));
        ConfigurationSection votes = yaml.getConfigurationSection("votes");
        if (yaml.contains("votes") && votes == null) throw new IllegalArgumentException("Неверные оценки");
        if (votes != null) for (String voter : votes.getKeys(false)) {
            if (!data.vote(UUID.fromString(voter), ProfileData.Vote.valueOf(votes.getString(voter, "")))) {
                throw new IllegalArgumentException("Самооценка в файле профиля");
            }
        }
        Map<UUID, Long> announced = new HashMap<>();
        ConfigurationSection history = yaml.getConfigurationSection("medal-history.announced");
        if (history != null) for (String id : history.getKeys(false)) announced.put(UUID.fromString(id), history.getLong(id));
        data.restoreHistory(yaml.getStringList("medal-history.claimed"), announced);
        if (version == 1) data.replaceMedals(readMedals(yaml));
        if (!yaml.isList("display")) throw new IllegalArgumentException("Неверные слоты профиля");
        List<?> display = yaml.getList("display", List.of());
        if (display.size() > 18) throw new IllegalArgumentException("Слишком много слотов");
        UUID[] slots = new UUID[18];
        HashSet<UUID> placed = new HashSet<>();
        for (int i = 0; i < display.size(); i++) {
            Object raw = display.get(i);
            if (raw == null || raw.equals("")) continue;
            UUID id = UUID.fromString(String.valueOf(raw));
            if (!placed.add(id) || (version == 1 && !data.medals().containsKey(id))) throw new IllegalArgumentException("Неверное размещение медали");
            slots[i] = id;
        }
        data.restoreLayout(slots);
        return new Decoded(data, version == 1);
    }

    static List<ProfileMedal> decodeMedals(UUID owner, String input) throws InvalidConfigurationException {
        YamlConfiguration yaml = read(owner, input);
        if (yaml.getInt("version") != 1) throw new IllegalArgumentException("Неверная версия файла медалей");
        return readMedals(yaml);
    }

    private static List<ProfileMedal> readMedals(YamlConfiguration yaml) {
        ConfigurationSection medals = yaml.getConfigurationSection("medals");
        if (yaml.contains("medals") && medals == null) throw new IllegalArgumentException("Неверные медали");
        List<ProfileMedal> result = new ArrayList<>();
        HashSet<String> sources = new HashSet<>();
        if (medals != null) for (String key : medals.getKeys(false)) {
            ConfigurationSection medal = medals.getConfigurationSection(key);
            if (medal == null || !(medal.get("awarded-at") instanceof Number)) throw new IllegalArgumentException("Неверная дата медали");
            ProfileMedal value = new ProfileMedal(UUID.fromString(key), ProfileMedal.Metal.valueOf(medal.getString("metal", "")),
                    medal.getString("title", ""), medal.getStringList("reasons"), medal.getLong("awarded-at"), medal.getString("source", ""));
            if (!value.source().isEmpty() && !sources.add(value.source())) throw new IllegalArgumentException("Повторная награда");
            result.add(value);
        }
        return List.copyOf(result);
    }
}
