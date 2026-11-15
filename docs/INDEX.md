# RP Portals — указатель кода

Создано `python _family/tools/mapgen.py` 15.11.2026, версия 0.2.8-1.20.1-forge, коммит `2e3b865`.
**Руками не править**: пересоздаётся скриптом. Смысл, потоки работы и рецепты — в `docs/MAP.md`.
Разбор по шаблонам, без компиляции: необычно оформленная регистрация может отсутствовать в списках.

Всего: 19 файлов, 2898 строк Java.

## 1. Пакеты

| Пакет | Файлов | Строк |
|---|---:|---:|
| `faygolover.rpportals` | 11 | 1653 |
| `faygolover.rpportals.client` | 7 | 1027 |
| `faygolover.rpportals.gametest` | 1 | 218 |

## 2. Классы

### `faygolover.rpportals`
- `MedicineCompat`, 156 — RP Medicine (мягкая зависимость): кто идёт через портал вместе с игроком — тело, которое он несёт (тащит), и каталка или кресло, которое он везёт (с пассажиром), — и как их переставить.
- `PortalBlock`, 112 — Блок-якорь портала: сам невидим и проходим, плоскость рисует клиент по настройкам блок-сущности.
- `PortalBlockEntity`, 423 — Портал: плоскость любого размера и формы над блоком-якорем, вид (цвет, туман, рябь, живой вид той стороны, невидимый, снимок той стороны), связь с другим порталом по имени (по умолчанию двусторонняя).
- `PortalClientSet`, 29 — Клиент: загруженные порталы (для перехода, отрисовки и «держать ПКМ»).
- `PortalFrame` (record), 80 — Плоскость портала: origin — середина нижнего края, normal — «лицо» (горизонтально, к тем, кто подходит), right — вправо, если смотреть на лицо, вверх — мировая ось Y.
- `PortalLinks`, 95 — Порталы по имени (в мире data/rpportals_links.dat): где стоит портал и его плоскость.
- `PortalNet`, 185 — Канал rpportals:main: переход через портал, снимки той стороны.
- `PortalPhotoServer`, 176 — Снимки «той стороны» делаются сами.
- `PortalRegistry`, 47 — Блок и предмет портала; предмет — во вкладке «RP: разное» RP Core.
- `PortalServer`, 314 — Сервер: подтвердить переход игрока.
- `RpPortals`, 36 — RP Portals — порталы семейства RP: проходы любого размера и формы, которые ставит ведущий.

### `faygolover.rpportals.client`
- `PortalClient`, 128 — Клиент RP Portals: шейдер поверхности, отрисовка, переход, снимки той стороны, окно настроек.
- `PortalCrossing`, 155 — Клиент: переход через портал.
- `PortalLight`, 34 — Свет светящихся порталов: окружающие поверхности подкрашиваются цветом портала с затуханием на lightRange блоков (за лицом — слабее).
- `PortalPhotos`, 103 — Снимки «той стороны» на клиенте.
- `PortalRender`, 188 — Поверхность порталов.
- `PortalTechniques`, 20 — Техники RP Portals во вкладке FL Render (подписи — в lang: flrender.technique.rpportals.
- `PortalView`, 399 — Та сторона портала.

### `faygolover.rpportals.gametest`
- `CrossingGameTests`, 218 — Переход через портал на сервере (02–03.11.2026): G2 — после переноса шаг клиента не считается «moved too quickly»; Ж3 — везомая каталка с пассажиром идёт с везущим разом (и в другое измерение) и встаёт перед ним, кресло без везуще…

Классов без описания: 0 из 19.

## 3. Подписки на события

| Событие | Кто слушает |
|---|---|
| `BuildCreativeModeTabContentsEvent` | `PortalRegistry.onTab` |
| `FMLClientSetupEvent` | `PortalClient.onSetup` |
| `FMLCommonSetupEvent` | `RpPortals.commonSetup` |
| `PlayerEvent.PlayerLoggedOutEvent` | `PortalPhotoServer.onLogout` |
| `RegisterGuiOverlaysEvent` | `PortalClient.onOverlays` |
| `RegisterShadersEvent` | `PortalClient.onShaders` |
| `RenderLevelStageEvent` | `PortalRender.onRenderLevel` |
| `TickEvent.ClientTickEvent` | `PortalCrossing.onClientTick` |
| `net.minecraftforge.event.TickEvent.ClientTickEvent` | `PortalPhotos.onClientTick` |
| `net.minecraftforge.event.server.ServerStoppedEvent` | `PortalPhotoServer.onServerStopped` |

## 4. Сеть

Канал: `faygolover/rpportals/PortalNet.java`, имя `main`, версия протокола `не объявлена константой`.
- `Cross` (направление не указано в регистрации)
- `PhotoChunk` (направление не указано в регистрации)
- `PhotoRequest` (направление не указано в регистрации)
- `PhotoData` (направление не указано в регистрации)
- `PhotoChanged` (направление не указано в регистрации)
- `PhotoWanted` (направление не указано в регистрации)
- `Jump` (направление не указано в регистрации)

Классы с обработчиками пакетов (1): `PortalNet`.

## 5. Команды

Нет.

## 6. Данные

- SavedData: `PortalLinks`, файл "rpportals_links"
- свои файлы на диске: `PortalPhotoServer`

## 7. Конфиги

Нет.

## 8. Что берёт из RP Core и что ему даёт

Методы `RpCoreAPI` (сколько вызовов): `MISC_TAB` 1, `registerModule` 1.

## 9. Связи с другими модами

Проверки `isLoaded`: `rpmedicine` (MedicineCompat).

Пакеты других модов семейства в коде: `flrender` — 4 файлов; `rpcore` — 9 файлов; `rpmedicine` — 1 файлов.

## 10. Зарегистрированные id

- `faygolover/rpportals/PortalRegistry.java` (1): `portal`
