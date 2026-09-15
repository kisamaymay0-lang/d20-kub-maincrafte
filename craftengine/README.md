# Кувшин как кастомный блок CraftEngine

Кувшин не занимает никакой ванильный блок. Он регистрируется в CraftEngine как
настоящий блок со своим id, которого в ванили нет, поэтому встретить его можно
только там, где его поставили — ни в генерации, ни в крафте, ни у соседей.

| id | что это |
|---|---|
| `f8resurs:ancient_jug` | пустой кувшин |
| `f8resurs:ancient_jug_filled` | налитый кувшин |

Содержимое кувшина в блоке не хранится — оно живёт в
`plugins/f8-plugin/jugs.yml`, ключ `мир_x_y_z`.

## Установка

1. **CraftEngine.** Скачайте с Modrinth: <https://modrinth.com/plugin/craftengine>,
   положите jar в `plugins/`.

2. **Пак с кувшином.** Скопируйте папку `resources/f8_jug` из этого каталога в
   `plugins/CraftEngine/resources/`, чтобы получилось:

   ```text
   plugins/CraftEngine/resources/f8_jug/
   ├── pack.yml
   ├── configuration/blocks/ancient_jug.yml
   └── resourcepack/assets/f8resurs/{models,textures}/block/…
   ```

   Папку можно создать и командой в игре — `/ce resource create f8_jug` — но
   тогда `pack.yml` надо поправить: `namespace: f8resurs` (id блоков начинаются
   с этого пространства имён).

3. **Перезапуск сервера** (или `/ce reload all`).

4. **Проверка.** В логе f8-plugin должна появиться строка
   `Кувшин: блоки CraftEngine f8resurs:ancient_jug и f8resurs:ancient_jug_filled
   зарегистрированы.` Если вместо неё предупреждение — в нём написан путь к
   конфигу и причина.

## Почему именно так

**Конфиг живёт в паке, а не рядом.** CraftEngine читает только паки из
`plugins/CraftEngine/resources/<имя пака>/`: `pack.yml` + `configuration/`
(все `.yml` и `.json`, рекурсивно по подпапкам) + `resourcepack/`. Файл,
положенный в `plugins/CraftEngine/blocks/`, плагин не увидит никогда — именно
поэтому кувшин 10.13 не ставился.

**`state` — единственная обязательная секция.**

* `auto_state: mushroom_stem` — группа ванильных состояний-носителей, из
  которой CraftEngine сам берёт свободное. Полный список групп:
  <https://xiao-momi.github.io/craft-engine-wiki/configuration/block/states/>.
  Группа `note_block` здесь не годится: в ресурспаке f8resurs
  `blockstates/note_block.json` уже привязывает состояния `note=24/flute` и
  `note=1..9/banjo` к моделям кувшина, и CraftEngine выбрал бы занятое
  состояние с предупреждением о конфликте.
* `model.path` — путь к **уже существующей** модели. По одному `path`
  CraftEngine ничего не генерирует, поэтому модель кувшина и её текстуры лежат
  в `resourcepack/` этого же пака.

**`settings` — физика блока.** Кроме `hardness`, `resistance` и `sounds` сняты
признаки сплошного куба: `is_view_blocking`, `can_occlude`, `is_suffocating`,
`is_redstone_conductor` — `false`, а `propagate_skylight` — `true`. Носитель
`mushroom_stem` глухой, и без этих ключей соседние блоки не рисовали грани
рядом с кувшином, проводили редстоун и гасили свет.

**`loot` нет намеренно.** Без таблицы лута блок не роняет ничего; предмет
кувшина со всем содержимым роняет плагин (`BlockBreakEvent` в `AncientJug`).
Таблица лута дала бы двойной дроп.

Полный список ключей `settings`:
<https://xiao-momi.github.io/craft-engine-wiki/configuration/block/settings/>.
Проверка, что конфиг им соответствует, — тест `CraftEnginePackTest`.

## API

Плагин обращается к CraftEngine через `CraftEngineBlocks` (`place`, `remove`,
`byId`, `getCustomBlockState`), зависимость объявлена как
`softdepend: [CraftEngine]`. Все обращения к API сидят во вложенном классе
`CraftEngineJug.Bridge`, который загружается только после проверки, что
CraftEngine стоит: на сервере без него не бывает ни `NoClassDefFoundError` на
старте, ни в тике, который идёт на каждый кувшин.

Кувшин определяется по id блока, а не по признаку «блок кастомный»: на сервере
могут стоять чужие блоки CraftEngine, и их нельзя ни переставлять, ни принимать
за кувшин.
