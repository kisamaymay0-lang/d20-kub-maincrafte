# Кувшин как кастомный блок CraftEngine

Кувшин больше не занимает никакой ванильный блок. Он регистрируется в
CraftEngine как настоящий блок со своим id, которого в ванили нет, поэтому
встретить его можно только там, где его поставили — ни в генерации, ни в крафте.

## Установка

1. Скачайте CraftEngine с Modrinth: https://modrinth.com/plugin/craftengine
   (версия для Paper 1.21.11 есть в списке поддерживаемых), положите jar в
   `plugins/`.
2. Скопируйте `blocks/ancient_jug.yml` из этой папки в
   `plugins/CraftEngine/blocks/ancient_jug.yml`.
3. Перезапустите сервер. В логе f8-plugin должно быть видно, что блок найден;
   если id не определён в конфиге, плагин напишет об этом при первой попытке
   поставить кувшин.

## Id блоков

| id | что это |
|---|---|
| `f8resurs:ancient_jug` | пустой кувшин |
| `f8resurs:ancient_jug_filled` | налитый кувшин |

Содержимое кувшина в блоке не хранится — оно живёт в
`plugins/f8-plugin/jugs.yml`, ключ `мир_x_y_z`.

## Что проверить после установки

Ключ `state.model.model` — путь к модели из вашего ресурспака. Если CraftEngine
вашей версии ждёт другой ключ (например, `texture` вместо `model`), поправьте
по вики: https://xiao-momi.github.io/craft-engine-wiki/configuration/block/states
