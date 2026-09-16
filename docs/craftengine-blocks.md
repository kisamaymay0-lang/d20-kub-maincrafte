# Блоки плагина в CraftEngine

Плагин ставит в мир три кастомных блока. У них нет ванильного id, поэтому
встретить их можно только там, где их поставили: ни в генерации, ни у соседей,
ни в крафте их не бывает.

| id | что это | что рисует |
|---|---|---|
| `f8resurs:ancient_jug` | пустой Древний кувшин | `f8resurs:block/ancient_jug` |
| `f8resurs:ancient_jug_filled` | налитый Древний кувшин | `f8resurs:block/ancient_jug_filled` |
| `f8resurs:copper_note_block` | медный нотный блок | `f8resurs:block/copper_note_block` |

Все три модели уже лежат в ресурспаке f8resurs. Отдельную папку с моделями
плагин больше не раздаёт — хватает того пака, который вы и так поставили в
CraftEngine.

Содержимое блоков в самих блоках не хранится: у кувшина оно в
`plugins/f8-plugin/jugs.yml`, у медного блока — в `plugins/f8-plugin/blocks.yml`
(ключ `мир_x_y_z`).

## Куда это положить

CraftEngine читает конфиги **только из паков**: `plugins/CraftEngine/resources/<имя пака>/`
с `pack.yml`, папкой `configuration/` (все `.yml` и `.json`, рекурсивно) и
папкой `resourcepack/` (модели и текстуры). Файл, просто брошенный в
`plugins/CraftEngine/blocks/`, плагин не увидит никогда — именно так кувшин
версии 10.13 не ставился.

Пак у вас уже есть (тот, куда вы положили ресурспак). Добавьте в него один
файл: `configuration/blocks/f8resurs.yml`.

### `pack.yml` — пространство имён

Пространство имён пака обязано совпадать с началом id блоков, иначе
CraftEngine зарегистрирует их под другим именем и плагин их не найдёт.

```yaml
namespace: f8resurs
```

### `configuration/blocks/f8resurs.yml` — сами блоки

```yaml
blocks:

  # Пустой кувшин.
  f8resurs:ancient_jug:
    state:
      auto_state: mushroom_stem
      model:
        path: "f8resurs:block/ancient_jug"
    settings:
      hardness: 1.2
      resistance: 6.0
      replaceable: false
      is_suffocating: false
      is_view_blocking: false
      is_redstone_conductor: false
      can_occlude: false
      propagate_skylight: true
      sounds:
        break: minecraft:block.decorated_pot.break
        step: minecraft:block.decorated_pot.step
        place: minecraft:block.decorated_pot.place
        hit: minecraft:block.decorated_pot.hit
        fall: minecraft:block.decorated_pot.fall

  # Налитый кувшин — отдельный блок: по блоку извне видно только «он кастомный»,
  # но не «он пустой или налитый».
  f8resurs:ancient_jug_filled:
    state:
      auto_state: mushroom_stem
      model:
        path: "f8resurs:block/ancient_jug_filled"
    settings:
      hardness: 1.2
      resistance: 6.0
      replaceable: false
      is_suffocating: false
      is_view_blocking: false
      is_redstone_conductor: false
      can_occlude: false
      propagate_skylight: true
      sounds:
        break: minecraft:block.decorated_pot.break
        step: minecraft:block.decorated_pot.step
        place: minecraft:block.decorated_pot.place
        hit: minecraft:block.decorated_pot.hit
        fall: minecraft:block.decorated_pot.fall

  # Медный нотный блок. До 10.20 это был обычный NOTE_BLOCK с нотой 24.
  f8resurs:copper_note_block:
    state:
      auto_state: solid
      model:
        path: "f8resurs:block/copper_note_block"
    settings:
      hardness: 3.0
      resistance: 6.0
      replaceable: false
      is_suffocating: true
      is_view_blocking: true
      is_redstone_conductor: true
      can_occlude: true
      propagate_skylight: false
      sounds:
        break: minecraft:block.copper.break
        step: minecraft:block.copper.step
        place: minecraft:block.copper.place
        hit: minecraft:block.copper.hit
        fall: minecraft:block.copper.fall
```

Дальше — `/ce reload all` или перезапуск сервера.

## Почему именно так

**`state` — единственная обязательная секция**
([вики](https://xiao-momi.github.io/craft-engine-wiki/configuration/block/states/)).

* `auto_state` — группа ванильных состояний-носителей, из которой CraftEngine
  сам берёт свободное. Внутренний блок при этом настоящий, со своим id:
  носитель нужен только для того, чтобы клиенту было что показать.
* `mushroom_stem` у кувшина и `note_block` несовместимы: в ресурспаке f8resurs
  `blockstates/note_block.json` уже привязывает состояния `note=24/flute` и
  `note=1..9/banjo` к моделям кувшина, и CraftEngine выбрал бы занятое
  состояние с предупреждением о конфликте. Поэтому кувшин сидит на
  `mushroom_stem`.
* `solid` у медного блока — модель `cube_all`, то есть обычный глухой куб, так
  что группа сплошных блоков подходит ему как есть.
* `model.path` — путь к **уже существующей** модели. По одному `path`
  CraftEngine ничего не генерирует: модель обязана лежать в паке.

**`settings` — физика блока**
([вики](https://xiao-momi.github.io/craft-engine-wiki/configuration/block/settings/)).
Ключи сплошности (`is_view_blocking`, `can_occlude`, `is_suffocating`,
`is_redstone_conductor`, `propagate_skylight`) заданы явно, потому что по
умолчанию они «не определено» и наследуются от носителя:

* кувшин — не куб, он стоит на глухом `mushroom_stem`. Без этих ключей соседние
  блоки не рисовали грани рядом с кувшином (дыры в постройке), проводили
  редстоун и гасили свет — так было в 10.13;
* медный блок — наоборот, глухой куб, как ванильный нотный блок: он должен
  перекрывать обзор соседям и проводить редстоун, иначе блок перестанет
  срабатывать от провода.

**`loot` нет ни у одного блока.** Без таблицы лута блок не роняет ничего, а
свои предметы роняет плагин: у кувшина — `BlockBreakEvent` в `AncientJug` (вместе
с содержимым), у медного блока — `BlockBreakEvent` в `CopperBlockListener`.
Таблица лута дала бы двойной дроп.

**Музыка медного блока от блока не зависит.** Ноты играет сам плагин
(`world.playSound(...)`), инструмент выбирается по предмету в слоте меню, а не
по блоку, поэтому переезд с NOTE_BLOCK на свой блок ничего в звучании не меняет.

## Как проверить, что всё встало

В логе `f8-plugin` на старте:

```text
Кувшин: блоки CraftEngine f8resurs:ancient_jug и f8resurs:ancient_jug_filled зарегистрированы.
Медный нотный блок: блок CraftEngine f8resurs:copper_note_block зарегистрирован.
```

Если вместо этого предупреждение — в нём написана причина: CraftEngine не
установлен, не включён или не зарегистрировал конкретный id. Пока блока нет,
кувшин и медный блок просто не ставятся (плагин отменяет установку и пишет
причину в чат) — молча портить мир он не будет. Уже стоящие медные блоки
старого образца продолжают работать и переносятся на новый блок сами.

## Крафт медного нотного блока

Бесформенный, порядок в сетке не важен: **нотный блок + редстоун + алмаз +
любая медная решётка** (обычная, потемневшая, состаренная, окисленная и все
вощёные варианты). До 10.20 вместо алмаза был кусочек меди.

## Что проверяют тесты

`CraftEngineBlocksTest` читает этот файл и сверяет конфиг с тем, что реально
разбирает CraftEngine (группы `auto_state` — из `AutoStateGroup`, ключи
`settings` — из `BlockSettingsModifiers`, ключи `sounds` — из
`BlockSounds.fromConfig`), а пути моделей — с собранным ресурспаком
`docs/f8resurspack-fixed.zip`. Если сюда написать ключ, который CraftEngine не
понимает, или модель, которой в паке нет, сборка упадёт, а не «блок молча не
встанет в игре».
