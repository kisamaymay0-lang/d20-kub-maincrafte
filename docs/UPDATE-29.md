# Обновление 10.30 — плагин под Minecraft 26.2 (Purpur / Paper 26.2, Java 25)

Версия плагина: **10.30**. Артефакт сборки: `f8-plugin-10.30`.

Механики не менялись: это та же **10.29** (клешня краба с зачарованиями инструментов, изморозь с
зацепом и дрифтом, миниигра особого улова, кастомные блоки CraftEngine, полный конфиг при запуске),
переведённая с Paper 1.21.11 на Minecraft 26.2. Правок в игровом коде нет — только сборка,
объявление версии и формат ресурспака.

## Что нужно на сервере

| Что | Версия | Почему именно она |
| :-- | :-- | :-- |
| **Ядро** | Purpur 26.2 (стабильный build 2633) или Paper 26.2 | 26.2 — текущая поддерживаемая линия Paper |
| **Java** | **25** | Minecraft 26.2 требует Java 25: Paper отдаёт это в своём API (`fill.papermc.io/v3/projects/paper/versions/26.2` → `java.version.minimum = 25`). На Java 21 сервер 26.2 не поднимется |
| **CraftEngine** | **26.8.x** (у нас 26.8.2) | линия 26.8 собрана строго под Paper 26.2 (`paper_version=26.2.build.+`, `latest_supported_version=26.2`). Линия 26.9 уже идёт на 26.3 |
| **Plasmo Voice** | 2.1.16 | та же, что и на 1.21.11: эта версия уже поддерживает 26.2 |

Обновлять CraftEngine при переезде не нужно, если он у вас уже 26.8.x: `__version__: '92'` в
`plugins/CraftEngine/config.yml` — это как раз конфиг линии 26.8.

## Что changed

| Было (1.21.11) | Стало (26.2) |
| :-- | :-- |
| `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` | `io.papermc.paper:paper-api:26.2.build.129-stable` |
| `maven.compiler.source/target` 21 | 25 |
| `plugin.yml: api-version: '1.21.11'` | `api-version: '26.2'` |
| CI: JDK 21 | JDK 25 |
| ресурспак: `pack_format` 75 | 88 |
| артефакт `f8-plugin-10.29` | `f8-plugin-10.30` |

**Почему координаты `paper-api` другие.** С 26.1 Paper публикует готовые версии вида
`26.2.build.<номер>-stable` вместо `1.21.11-R0.1-SNAPSHOT` — старой схемы для 26.x в репозитории
нет. Взят стабильный Paper 26.2 build 129 (последний на момент порта).

**Почему не нужен `purpur-api`.** Purpur — форк Paper и отдельно publishes свой API, но плагин не
пользуется ничем Purpur-специфичным, поэтому собирается против `paper-api` и на Purpur грузится как
родной.

**Почему игровой код не тронут.** В плагине нет ни одного обращения к внутренностям сервера (ни
`org.bukkit.craftbukkit`, ни `net.minecraft`, ни `getHandle`), а классы CraftEngine подключены
зависимостью `provided` и вызываются только из ленивого моста `CraftEngineSupport.Bridge` — то есть
на сервере без CraftEngine этот код даже не загружается. Компиляция против `paper-api` 26.2 прошла
без правок: `ChatColor`, `DataComponentTypes` (включая `TOOL`, `ENCHANTMENTS`,
`ENCHANTMENT_GLINT_OVERRIDE`, `TOOLTIP_DISPLAY`) и всё остальное, чем пользуется плагин, в 26.2 на
месте.

**Формат ресурспака 88.** С 75 (1.21.11) он поднялся на 88. Breaking-изменения формата касаются
кроватей, табличек и висячих табличек: специальные модели `minecraft:bed`, `minecraft:standing_sign`
и `minecraft:hanging_sign` убраны, атласы `minecraft:beds` и `minecraft:signs` удалены. В паке
f8resurs ничего из этого нет, поэтому правится только номер: в исходнике
(`resourcepack/pack.mcmeta`) и внутри готового `docs/f8resurspack-fixed.zip`.

## Что посмотреть руками

1. **Старт.** В логе не должно быть предупреждения про `api-version`: плагин должен читаться как
   нативный для 26.2. Если Paper не поймёт строку, он напишет про legacy api-version.
2. **Клешня краба.** Копает инструментами из инвентаря, зачарования инструмента (удача, шёлк,
   прочность, починка) работают — скорость и дроп задаёт движок через компонент `minecraft:tool`.
3. **Изморозь.** Зацеп на зажатой ПКМ, дрифт на приседе, прыжок вверх, перезарядка после
   скольжения.
4. **Блоки CraftEngine.** Медный нотный блок и древний кувшин ставятся и ломаются; если блоков
   нет — `/ce reload all` и диагностика плагина в логе (ссылка на `docs/craftengine-blocks.md`).
   Пак с блоками у вас — `plugins/CraftEngine/resources/f8_jug/`.
5. **Ресурспак.** У клиента не должно быть предупреждения «pack made for an older version»: пак
   теперь объявлен форматом 88.

---

### Файлы правки 10.30

| Файл | Что в нём |
| --- | --- |
| `pom.xml` | `paper-api` 26.2.build.129-stable, `source/target` 25, версия 10.30; CraftEngine оставлен на 26.8.2 |
| `src/main/resources/plugin.yml` | `api-version: '26.2'`, версия 10.30 |
| `.github/workflows/build.yml` | JDK 25, триггеры на ветки `arena/**`, артефакт `f8-plugin-10.30` |
| `resourcepack/pack.mcmeta` | `pack_format`/`min_format`/`max_format` 75 → 88 |
| `docs/f8resurspack-fixed.zip` | тот же пак, обновлён `pack.mcmeta` (остальные файлы — байт в байт) |
| `docs/UPDATE-29.md` | это описание |
