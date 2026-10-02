# EX-twins — техническая памятка

Общие правила — [AGENTS.md](../AGENTS.md), системы и ветки — [карта проекта](project-map.md). Читай нужный раздел. Java-пути ниже относительны `src/main/java/dev/hurtify/relicsaddon/`; расположение классов зависит от миграции.

## Build, run, test

Нужен JDK 21. Проверенный ранее путь: `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`; перед использованием проверь наличие. Используй Gradle Wrapper выбранного checkout.

| Действие | PowerShell | Git Bash |
|---|---|---|
| Сборка, verify-проверки, содержимое release JAR | `./gradlew.bat build` | `./gradlew build` |
| Серверные GameTests | `./gradlew.bat runGameTestServer` | `./gradlew runGameTestServer` |
| Клиент для игровой проверки | `./gradlew.bat runClient` | `./gradlew runClient` |

В PowerShell задай `$env:JAVA_HOME` путём установленного JDK; в Git Bash — `export JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'`. Не устанавливай Java, пока не проверишь существующую.

Сохраняй полный лог и проверяй код возврата процесса. Для краткого вывода фильтруй `error|FAIL|required tests|BUILD`. Лог GameTests: `run-gametest/logs/latest.log`; успех — фактическое завершение с `All N required tests passed`. Обычный клиентский лог: `run/logs/latest.log`; у сценариев свой run-каталог. Клиент на этом ПК запускается несколько минут.

## Какие проверки выбирать

- Чистые правила и математика: соответствующие JavaExec `verify*` в build.gradle. На main после S6 — `DomainRulesCheck` и architecture gate с `--max-legacy`/`--min-domain`; не ослабляй лимиты ради зелёного теста.
- Сеть и компоненты: `NetworkCodecCheck`; путь теста зависит от ветки.
- Энергия: `DevicePowerCheck` либо выделенные domain-проверки. Формы и распределение роя: `HiveFormationCheck`, `HiveAllocationCheck`, `HiveConstructsCheck`, где они существуют.
- Защита и взаимодействие с миром: `ShieldDefenseGameTests`, `DeviceGameTests`, `ArmageddonGameTests` по изменённой механике. Используй helpers; тестируй поведение, не текст реализации.
- Свет: `EffectLightsCheck`, `client/light/EffectLightPoolCheck`. Модели: проверки pose/animation и `verifyObjCompact` выбранной ветки.
- Для Markdown достаточно ссылок, путей и согласованности правил; Gradle/GameTests не нужны.

`DeviceTestSupport.player(helper, ...)` создаёт survival ServerPlayer с двумя Curios charm-слотами; `equip(...)` выдаёт включённое заряженное устройство. Шаблоны `test_room` и `field_arena` содержат блоки — очищай область, если тесту нужен воздух. События можно моделировать прямыми вызовами damage/tick handlers и `runAfterDelay`.

Автономные корабельные ульи удаляй в конце теста: структуры остаются в мире и могут влиять на последующие проверки. Golden masters в `src/test/resources/golden/` на новой базе — контракт поведения.

## Architecture map

Маршрут по системам — [карта проекта](project-map.md), раздел «Карта систем». На main после S6 правила — в `domain/`, кодеки — в `adapter/out/persistence/`; legacy-контроллеры ещё присутствуют. На старой `docs/readme-ru` domain отсутствует.

Спецификация миграции — `docs/architecture/hexagonal-migration.md` на main. Если файла нет, сначала посмотри заголовки через `git show main:docs/architecture/hexagonal-migration.md`. S7–S13 — дальнейший план, а не автоматически выполненные этапы.

Состояние предметов — `ModDataComponents`: progression, energy, shield/hive state, instance id. `RelicRuntime.canOperate` учитывает включение и питание. В legacy-прогрессии ранги модулей упакованы по `bit * 2`; миграция сохраняет смысл существующих данных.

Shield-фильтры AddonConfig используют `RegistryFilter`. `ConfigValue.set` не отправляет событие смены конфигурации: кеш должен замечать замену объекта списка; тесты восстанавливают исходные значения. Числовые лимиты энергии, XP, залпов и света сверяй с кодом выбранной ветки.

## Pitfalls already hit

- **Сеть:** `playToClient` регистрируется на обеих сторонах. Client-only условие вызывало kick с `channel relics_addon:armageddon_blast absent on server`. Клиентская реализация остаётся внутри handler lambda.
- **Архитектура callback:** прямая ссылка на client-класс даже внутри lambda остаётся в bytecode. Для ArmageddonPayloads после S6 нужен отложенный reflective handler при безусловной регистрации; это проверяют architecture gate и NetworkRegistrationGameTests. История исправления — [журнал](testing-lessons.md).
- **Stream codec:** byte для количества дронов ломал синхронизацию роя из 500 дронов; используй VarInt/VarLong и проверку кодека.
- **Урон:** damage=0 оставляет knockback/on-hit эффекты, например Wither от черепа. Полное поглощение отменяет событие. `minecraft:bypasses_shield` включает огонь, магию, падение и не определяет поведение наших щитов.
- **Зависимости:** целевая NeoForge 21.1.251; Photon и LDLib2 нужны также на сервере. Relics не требуется. Версии определяет build.gradle выбранной ветки.
- **Curios:** определение charm-слота само по себе не выдаёт его игроку; проверь `src/main/resources/data/relics_addon/curios/entities/devices.json`.
- **Клиент:** common/server не загружает client-классы. Для optional LambDynamicLights только `client/light` касается типов LDL; мост — `yumi:entrypoints`, настройка — `lights.dynamic`.
- **Вид изнутри щита:** оболочка остаётся слабой по яркости (`ShieldSurfaceLighting.INSIDE`), чтобы не закрывать обзор.
- **Новый run-каталог:** без options.txt клиент ждёт accessibility onboarding. Для сценарного запуска используй существующие настройки с `onboardAccessibility:false`, не затирай настройки пользовательского клиента.
- **MDG classpath:** мод для отдельных run-задач добавляй через JVM `classpathProvider`, не `additionalRuntimeClasspath`, иначе FML может пропустить boot-layer library. LDL нужны `net.minecraft.mappings=mojmap` и `bundling=external`, чтобы не выбрать shadowed dev JAR.
- **Windows:** checkout-пути держи короткими (`C:/dev/...`). Наличие Python/ffmpeg проверяй; существующие генераторы обычно используют Node.js, видео — WinRT.

## Корабли и шейдеры

Читай этот раздел при работе с кораблями. `ship/ShipHiveBlockEntity`: `save/load` сохраняет долгоживущие данные; `saveSync/loadSync` передаёт runtime-состояние через update tag. ИИ — `ShipBrain`, отношения — `ShipAllies`, мировое положение — `ShipFrame`. Модули Aegis/Escort/Lance не равнозначны отложенной ветке корабельных щитов.

Sable хранит корабельные блоки в удалённом plot. `level.clip` может вернуть попадание в координатах plot: перед сравнением расстояний или поиском сущностей переведи через `SableCompanion.projectOutOfSubLevel`. Иначе AABB растягивается на миллионы блоков. Pose изменяемый — копируй `new Pose3d(pose)`; скорость в блоках/секунду. `stillValid` меню учитывает мировую позицию улья. Sable-селектор `@l` требует player source; на сервере используй UUID корабля.

Veil в Sable перепечатывает core shader через glsl-processor 0.2.3, который терял `;` после одиночного `x++;`. Используй `x += 1;`; заголовки for не затронуты. Проверяй parse/print либо `Couldn't compile dynamic` в логе Sable-клиента.

## Ресурсы и инструменты

- Произвольные mesh/animation-данные держи вне `assets/<modid>/models/`: Minecraft пытается прочитать JSON оттуда как vanilla-модели. RF-верстак использует `assets/relics_addon/workbench/rf_workbench.json`.
- Для Gradle property с точками в PowerShell заключай весь аргумент в кавычки: `'-Pneo_version=21.1.251'`. Иначе версия может разделиться и `.1.251` станет именем задачи.
- В capture-сценариях Minecraft 1.21.1 нижние допустимые границы опций: FOV 30, simulationDistance 5. Недопустимое значение сбрасывается и искажает результат съёмки.

- `tools/build_combat_sounds.mjs` — Ogg-звуки, `--validate`; зависимости описаны в tools.
- `tools/build_mana_shield_mesh.mjs` — OBJ мана-щита.
- `tools/compact_obj.mjs` — дедупликация v/vt/vn; `--check a.obj b.obj` сравнивает запекание. После генераторов RF-моделей учитывай `verifyObjCompact`.
- `tools/draw_component_icons.mjs --preview` — иконки и локальное превью.
- `tools/video_frames.ps1` — кадры видео; ограничивай интервал/число кадров. Результаты — в `D:/ex-twins-captures`.
- `src/main/resources/assets/relics_addon/fx/<name>.fx` переопределяет кодовые эффекты ExFx; refraction shader — в `shaders/core/shield_refraction.*` того же assets-корня.

При изменении OBJ проверь рендер на дыры, включая обратную сторону. Локальная визуальная проверка не разрешает отправлять скриншоты без просьбы. Новый пользовательский текст должен иметь `en_us` и `ru_ru`.

Для Photon 2.2.7 непрозрачный пользовательский рендер должен завершиться до захвата глубины AFTER_BLOCK_ENTITIES (например, AFTER_ENTITIES). Стекло рисуй отдельно в прозрачном проходе; depthTest частиц не компенсирует отсутствующую в захвате глубину корпуса.
