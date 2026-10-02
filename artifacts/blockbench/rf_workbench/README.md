# RF-верстак V4: анимация и Photon

Актуальная версия: 02.10.2026, checkout docs/readme-ru. Исходник rf_workbench.bbmodel: 839 mesh-элементов, две встроенные текстуры, 8 клипов. Основание неподвижно, ровно 2×2 блока; исчезающие треугольные панели удалены из геометрии, четыре угловых узла отделяются и вращаются. Ядро — тёмная сфера с объёмными дорожками внутри прозрачной гранёной оболочки.

## Генерация

`node tools/build_rf_workbench.mjs` создаёт bbmodel и runtime-данные в `src/main/resources/assets/relics_addon/workbench/rf_workbench.json` плюс текстуры в textures/workbench. Данные лежат вне vanilla models/: это произвольный mesh/animation JSON. `node tools/pose_rf_workbench.mjs` создаёт отдельные статические позы для headless-превью.

Клипы: uncharged, charged_closed, player_approach, charged_open, crafting_start, crafting, crafting_end, player_leave. Переход раскрытия — 1,6 с; отделение/возврат — 1,2 с; крафт — цикл 4 с. preview_sequence.json задаёт демонстрацию 14 с. Числовые ключи записываются десятичными строками без scientific notation.

## Клиентские адаптеры

`client/workbench/RfWorkbenchModel` читает те же вершины и числовые ключи, интерполирует иерархическую позу, отрисовывает корпус/дорожки/стекло; reload сбрасывает модель. Координаты делятся на 16 для перевода в блоки.

`RfWorkbenchEffects` работает один раз в игровой тик и вызывает реальные эффекты Photon через ExFx/PointEffectExecutor. Сохраняются общий лимит 128, дальность отсечения и LOD. Новые кодовые определения в ExFxLibrary:

- rf_workbench_core — небольшой ореол, пульсирующее кольцо, точки и слабая тёмная дымка;
- rf_workbench_trail — короткий след узла по позе следующего тика;
- rf_workbench_unfold — искры раскрытия и отделения/возврата;
- rf_workbench_arc — разряд от ядра к узлу.

Авторские assets/relics_addon/fx/<name>.fx могут переопределять их через существующий механизм ExFx. Это реальные кодовые Photon-определения, не частицы, дорисованные в GIF. Освещение окружающих блоков не добавлено.

## Нативный предпросмотр

В dev-клиенте `/rfworkbench_preview` запускает цикл у выбранного блока. Режимы: uncharged, charged_closed, charged_open, crafting, cycle, stop. Команда реализована в gametest/client/RfWorkbenchPreview и исключена из release JAR.

Автозапись: `./gradlew.bat '-Pneo_version=21.1.251' runRfWorkbenchClient`. Отдельный каталог D:/ex-twins-captures/rf-workbench-run, мир saves/RFWorkbench, options.txt с завершённым onboarding. Для подготовки скопирован только level.dat из сценарного мира; оригинальный мир не менялся. Capture-сценарий создаёт площадку и spectator-камеру только в отдельном мире, после 14 с останавливает свой клиент.

Финальные PNG: D:/ex-twins-captures/rf-workbench-native-final/screenshots/. Сборка: `tools/assemble_rf_workbench_preview.py --frames <папка> --output <gif>`. Длительности берутся из игровых тиков в именах файлов. Постобработка — кадрирование, масштаб и подписи, без дорисовки эффектов.

## Предыдущая проверка V3

- Генератор PASS: 992 meshes / 8 clips. validate_animations: 0 ошибок / 0 предупреждений (animation-validation-v3.json).
- compileJava/processResources на 21.1.251: BUILD SUCCESSFUL (compile-251-attempt-4.log).
- Нативный прогон 3: BUILD SUCCESSFUL, 250 кадров, PhotonMaxLive=64, без ошибок наших ресурсов/анимации/Photon (native-photon-attempt-3.log). Стороннее сообщение Pride об отсутствующем access transformer не остановило клиент.
- jar/verifyReleaseContents на 21.1.251: BUILD SUCCESSFUL (jar-251-verification.log), runtime-данные и клиентские адаптеры входят в JAR, тестовые команды исключены.

Игровой блок, FE-хранилище и рецепты не добавлены. Подробная модель ещё не проверялась на производительность массового размещения. В main код пока не перенесён. V1 сохранена отдельно.

Финальная анимация проверена: D:/ex-twins-captures/rf-workbench-photon-verified.gif (204 различных кадра, 14 с) и .webp (около 3,9 MB). GIF объединяет одинаковые статические кадры, сохраняя длительности из игровых тиков; исходный native-прогон содержит 250 PNG. Проверены закрытая, открытая и крафтовая позы; верхушка раскрытой формы помещается в исправленный кадр. Лог native-gif-verified-assembly.log. Видеокадры сняты настоящим Minecraft/Photon, не Blender.

## Проверка V4 — 02.10.2026

- Генератор: 839 meshes, 8 clips, основание 32×32 единицы (2×2 блока). Проверка анимаций: 0 ошибок / 0 предупреждений, animation-validation-v4.json.
- Девять контактов стенок и четыре контакта ядра двигаются вместе с моделью. Молнии идут стенка ↔ ядро и ядро ↔ угловые узлы.
- Непрозрачный корпус рисуется AFTER_ENTITIES, до захвата глубины Photon; стекло — AFTER_TRANSLUCENT_BLOCKS. DepthTest включён и для авторских переопределений эффектов.
- Нативный прогон: BUILD SUCCESSFUL, exit 0, 171 PNG, PhotonMaxLive=67 (native-photon-v4-attempt-1.log).
- jar/verifyReleaseContents на 21.1.251: BUILD SUCCESSFUL (jar-251-v4-verification.log).
- Актуальные кадры: D:/ex-twins-captures/rf-workbench-native-v4/screenshots. Превью rf-workbench-photon-v4.gif и .webp: 143 разных кадра, 14 с (native-gif-v4-assembly.log). На площадке отмечены четыре блока под основанием.
