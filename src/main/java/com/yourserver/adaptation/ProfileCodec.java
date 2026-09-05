package com.yourserver.adaptation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Версионированный YAML одного профиля. Не содержит сериализации Bukkit-предметов. */
final class ProfileCodec {
    private ProfileCodec() { }

    static String encode(ProfileData data) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        yaml.set("owner", data.owner.toString());
        yaml.set("name", data.name());
        yaml.set("description", data.description());
        data.votes().forEach((voter, vote) -> yaml.set("votes." + voter, vote.name()));
        data.medals().forEach((id, medal) -> {
            String root = "medals." + id;
            yaml.set(root + ".metal", medal.metal().name());
            yaml.set(root + ".title", medal.title());
            yaml.set(root + ".reasons", medal.reasons());
            yaml.set(root + ".awarded-at", medal.awardedAt());
            yaml.set(root + ".source", medal.source());
        });
        List<String> slots = new ArrayList<>();
        for (UUID id : data.layout()) slots.add(id == null ? "" : id.toString());
        yaml.set("display", slots);
        return yaml.saveToString();
    }

    static ProfileData decode(UUID owner, String input) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(input);
        if (yaml.getInt("version") != 1 || !owner.toString().equals(yaml.getString("owner"))) {
            throw new IllegalArgumentException("Неверная версия или владелец профиля");
        }
        ProfileData data = new ProfileData(owner, yaml.getString("name", owner.toString()));
        data.describe(owner, yaml.getString("description", ""));
        ConfigurationSection votes = yaml.getConfigurationSection("votes");
        if (yaml.contains("votes") && votes == null) throw new IllegalArgumentException("Неверные оценки");
        if (votes != null) for (String voter : votes.getKeys(false)) {
            if (!data.vote(UUID.fromString(voter), ProfileData.Vote.valueOf(votes.getString(voter, "")))) {
                throw new IllegalArgumentException("Самооценка в файле профиля");
            }
        }
        ConfigurationSection medals = yaml.getConfigurationSection("medals");
        if (yaml.contains("medals") && medals == null) throw new IllegalArgumentException("Неверные медали");
        if (medals != null) for (String key : medals.getKeys(false)) {
            ConfigurationSection medal = medals.getConfigurationSection(key);
            if (medal == null || !(medal.get("awarded-at") instanceof Number)) throw new IllegalArgumentException("Неверная медаль");
            if (!data.award(new ProfileMedal(UUID.fromString(key), ProfileMedal.Metal.valueOf(medal.getString("metal", "")),
                    medal.getString("title", ""), medal.getStringList("reasons"), medal.getLong("awarded-at"), medal.getString("source", "")))) {
                throw new IllegalArgumentException("Повторная медаль/награда");
            }
        }
        if (!yaml.isList("display")) throw new IllegalArgumentException("Неверные слоты профиля");
        List<?> display = yaml.getList("display", List.of());
        if (display.size() > 18) throw new IllegalArgumentException("Слишком много слотов");
        HashSet<UUID> placed = new HashSet<>();
        for (int i = 0; i < display.size(); i++) {
            Object raw = display.get(i);
            if (raw == null || raw.equals("")) continue;
            UUID id = UUID.fromString(String.valueOf(raw));
            if (!placed.add(id) || !data.place(owner, id, i)) throw new IllegalArgumentException("Неверное размещение медали");
        }
        return data;
    }
}
