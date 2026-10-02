# MCP-инструменты EX-twins

Установлены шесть серверов со скриншота. ElevenLabs исключён по просьбе автора. MCP помогает задаче, но не заменяет исходники, Gradle и GameTests выбранной версии.

| Имя | Назначение | Источник |
| --- | --- | --- |
| blender | Геометрия, материалы, сцена и экспорт через Blender; нужен включённый аддон | [ahujasid/blender-mcp](https://github.com/ahujasid/blender-mcp) |
| blockbench | Работа с bbmodel и моделями Minecraft. Выбран официальный файловый stdio-режим этого проекта, доступный без запущенного GUI | [Blockbench MCP headless](https://github.com/jasonjgardner/blockbench-mcp-plugin/blob/main/headless/README.md) |
| mc-modpack | Диагностика mods, зависимостей, логов и crash reports; передавай конкретный run-каталог | [dcd887/mc-modpack-mcp](https://github.com/dcd887/mc-modpack-mcp) |
| mcdev | Поиск Minecraft-классов, сигнатур и вызовов. Выбирай 1.21.1, а не последнюю версию | [use-ai-for-mc/mcdev-mcp](https://github.com/use-ai-for-mc/mcdev-mcp) |
| mcmodding | Документация и примеры NeoForge; явно указывай loader neoforge и Minecraft 1.21.1 | [OGMatrix/mcmodding-mcp](https://github.com/OGMatrix/mcmodding-mcp) |
| memory | Локальные короткие факты, связи, решения и известные ошибки проекта | [MCP memory](https://github.com/modelcontextprotocol/servers/tree/main/src/memory) |

## Правила использования

- Поиск документации делай точечным. Если индекс не содержит 1.21.1, не выдавай пример другой версии за проверенный; сверяй исходники и документацию нужной версии.
- memory дополняет Markdown. Правила — AGENTS.md, причины/исправления ошибок — docs/testing-lessons.md. Не складывай в память полные логи, чаты, токены или секреты.
- Blender/Blockbench могут менять модели. Указывай файл и границы задачи; не перезаписывай пользовательскую сцену без её сохранения. Файловый Blockbench не управляет текущей вкладкой настольного редактора.
- Установка клиента не равна готовности сервера. Проверяй initialize/tools/list и отдельно необходимые базы/хост-приложение. После изменения MCP-конфига начни новую сессию клиента.
- На скриншоте Blockbench использовал `localhost:3000/bb-mcp` и не подключался. Для GUI-режима нужен запущенный Blockbench с плагином; один адрес не запускает сервер. Файловый режим устраняет эту зависимость для редактирования bbmodel.

## Расположение

Пакеты и кеши — `D:/ex-twins-tools/`. Конфиг Codex — пользовательский `~/.codex/config.toml`, Claude Code — `~/.claude.json` (раздел mcpServers). В Git находятся только документация и результаты проверки без учётных данных. Резервные копии конфигов сохраняются рядом с оригиналами.

Официальная настройка Codex описана в [документации MCP](https://developers.openai.com/codex/mcp). Проверка initialize/tools/list пройдена для всех шести серверов: blender — 36 инструментов, blockbench — 23, mc-modpack — 11, mcdev — 31, mcmodding — 4, memory — 9. Minecraft 1.21.1 проиндексирован; база mcmodding загружена. Для работы со сценой Blender нужен запущенный хост аддона.

Проверка 02.10.2026: аддон Blender 5.1 включён и настройки сохранены (`addon_utils.check`: True, True). Перезапусти Blender для загрузки аддона в уже открытой сессии; для операций со сценой Blender должен работать. Установлены mcdev-mcp 2.2.1, mcmodding-mcp 0.5.0, mc-modpack-mcp 1.3.0, blockbench-mcp 1.10.0 и server-memory 2026.8.31.
