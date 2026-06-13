# Transora — Notification Service
## Сервис уведомлений, табло и системы озвучивания: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `notification-service`

---

## Содержание

1. [Назначение сервиса](#1-назначение-сервиса)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Бизнес-правила и ограничения](#3-бизнес-правила-и-ограничения)
4. [Модель данных (ERD)](#4-модель-данных-erd)
5. [Схемы таблиц БД](#5-схемы-таблиц-бд)
6. [Подсистема табло](#6-подсистема-табло)
7. [Подсистема озвучивания](#7-подсистема-озвучивания)
8. [Синтез речи и аудиокеш](#8-синтез-речи-и-аудиокеш)
9. [Очередь сообщений и приоритеты](#9-очередь-сообщений-и-приоритеты)
10. [Шаблоны объявлений](#10-шаблоны-объявлений)
11. [Примеры данных](#11-примеры-данных)
12. [События (NATS JetStream)](#12-события-nats-jetstream)
13. [Зависимости и взаимодействие](#13-зависимости-и-взаимодействие)

---

## 1. Назначение сервиса

`notification-service` отвечает за всё, что видят и слышат пассажиры и сотрудники вокзала в реальном времени: актуальное состояние табло прибытия/отправления, перронные табло, а также автоматизированную систему звуковых объявлений.

Основные обязанности:

- Приём событий от других сервисов и трансляция обновлений на табло через WebSocket
- Формирование и управление очередью звуковых объявлений на каждом вокзале
- Синтез речи через Yandex SpeechKit с локальным кешированием аудиофайлов
- Fallback-синтез через RHVoice при недоступности Yandex SpeechKit
- Автоматическое создание рейсовых объявлений по расписанию и событиям
- Предоставление диспетчеру инструментов управления очередью объявлений
- Трансляция состояния табло при восстановлении соединения (последний кеш)

Сервис реализован на **Go** — это обоснованный выбор, поскольку основная нагрузка приходится на I/O: WebSocket-соединения с десятками клиентов (табло, диспетчеры), управление аудиопотоком и работу с внешними TTS API.

**Стек:** Go + PostgreSQL + Redis + NATS JetStream + Yandex SpeechKit + RHVoice + MinIO

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **DisplayBoard** (Табло) | Конкретный экземпляр табло на вокзале: главное или перронное |
| **BoardState** (Состояние табло) | Текущий набор рейсов и их статусов для отображения на табло |
| **AnnouncementQueue** (Очередь объявлений) | Упорядоченная по приоритету очередь звуковых сообщений на конкретном вокзале |
| **Announcement** (Объявление) | Единица звукового сообщения: тип, текст, аудиофайл, приоритет, время |
| **AnnouncementTemplate** (Шаблон объявления) | Текстовый шаблон с переменными для автогенерации текста объявления |
| **AudioCache** (Аудиокеш) | Хранилище синтезированных аудиофайлов, ключ — хеш от текста и параметров голоса |
| **TtsProvider** (Провайдер TTS) | Сервис синтеза речи: PRIMARY = Yandex SpeechKit, FALLBACK = RHVoice |
| **PlaybackAgent** (Агент воспроизведения) | Компонент Station Agent на вокзале, выполняющий воспроизведение аудио |
| **DisplayClient** (Клиент табло) | Браузер на TV, подключённый к WebSocket для получения обновлений |

---

## 3. Бизнес-правила и ограничения

### 3.1 Табло

- **BR-NOT-001:** Каждое табло регистрируется в системе с типом (`MAIN` — главное, `PLATFORM` — перронное) и привязкой к вокзалу и, для перронных, к номеру перрона.
- **BR-NOT-002:** Главное табло отображает все рейсы вокзала за настраиваемый горизонт — по умолчанию ближайшие 3 часа отправлений и прибытий.
- **BR-NOT-003:** Перронное табло отображает только рейс, назначенный на данный перрон в данный момент.
- **BR-NOT-004:** При потере WebSocket-соединения клиент табло автоматически переподключается. До восстановления показываются последние полученные данные.
- **BR-NOT-005:** При восстановлении соединения сервер немедленно отправляет полный текущий `BoardState` (не только дельту).
- **BR-NOT-006:** Обновление данных табло происходит в реальном времени — не реже чем за 2 секунды с момента публикации события в NATS.
- **BR-NOT-007:** Табло не имеет прав на запись — только чтение данных. Никакие действия через интерфейс табло невозможны.
- **BR-NOT-008:** На главном табло рейсы сортируются: сначала по плановому/ожидаемому времени, затем по номеру рейса.

### 3.2 Очередь объявлений

- **BR-NOT-010:** Каждый вокзал имеет независимую очередь объявлений.
- **BR-NOT-011:** Три уровня приоритета объявлений: `HIGH` (рейсовые), `MEDIUM` (информационные), `LOW` (рекламные).
- **BR-NOT-012:** Объявление с приоритетом `HIGH` вставляется в начало очереди и прерывает текущее воспроизведение, если оно имеет приоритет `LOW`. Объявления `HIGH` и `MEDIUM` дожидаются завершения текущего.
- **BR-NOT-013:** Между объявлениями обязательна пауза не менее 3 секунд (настраивается).
- **BR-NOT-014:** Рекламные объявления воспроизводятся только при отсутствии рейсовых и информационных в очереди. Рекламный слот не может занять более 30% эфирного времени за час.
- **BR-NOT-015:** Одно и то же рейсовое объявление (один тип + один рейс) не может быть в очереди более одного раза одновременно. Дубликаты игнорируются.
- **BR-NOT-016:** Диспетчер может: добавить внеочередное сообщение, удалить запланированное, приостановить всю очередь, изменить текст ещё не озвученного объявления.
- **BR-NOT-017:** Максимальная длина очереди — 50 объявлений на вокзал. При переполнении рекламные объявления вытесняются в первую очередь.

### 3.3 Автоматические рейсовые объявления

- **BR-NOT-020:** Система автоматически планирует объявления на основе расписания при получении данных о рейсе.
- **BR-NOT-021:** Стандартный набор автоматических объявлений для рейса отправления:
  - За 30 минут до отправления: «Внимание! Начинается регистрация...»
  - За 15 минут: «Приглашаем пассажиров к посадке...»
  - В момент открытия посадки диспетчером: немедленное объявление
  - При задержке: «Рейс задерживается...» с новым временем
  - При отмене: «Рейс отменяется...»
- **BR-NOT-022:** Для прибывающих рейсов:
  - За 10 минут до прибытия: «Ожидается прибытие...»
  - При фактическом прибытии: «Прибыл рейс...»
- **BR-NOT-023:** При изменении задержки рейса все ранее запланированные объявления этого рейса пересчитываются с новыми временами.
- **BR-NOT-024:** Если до объявления осталось менее 60 секунд, оно не отменяется при пересчёте — озвучивается как есть.

### 3.4 Синтез речи

- **BR-NOT-030:** При запросе на синтез система сначала ищет аудиофайл в кеше по ключу `sha256(text + voice_id + format)`.
- **BR-NOT-031:** Если файл найден в кеше — используется без обращения к TTS API.
- **BR-NOT-032:** Если файл не найден — запрос к Yandex SpeechKit. Результат сохраняется в MinIO и кеш-таблицу.
- **BR-NOT-033:** Если Yandex SpeechKit недоступен (таймаут > 3 сек или HTTP 5xx) — запрос к RHVoice (локальный, всегда доступен). Результат также кешируется, но с флагом `is_fallback = true`.
- **BR-NOT-034:** Аудиофайлы хранятся в формате MP3, 48kbps, 22050Hz — оптимально для трансляции через IP-аудиосистему.
- **BR-NOT-035:** Кешированные файлы не имеют срока истечения — текст объявлений детерминирован и не меняется.
- **BR-NOT-036:** Голос для синтеза настраивается на уровне вокзала (разные вокзалы могут использовать разные голоса).

---

## 4. Модель данных (ERD)

```
┌─────────────────────┐
│    display_board    │ ← Регистрация каждого табло
│─────────────────────│
│ id (PK)             │
│ station_id          │
│ board_type          │  (MAIN | PLATFORM)
│ platform_number     │  NULL для главного
│ name                │
│ is_active           │
└──────────┬──────────┘
           │ 1:1 (хранится в Redis)
           ▼
      [board_state]   ← Текущее состояние (Redis only)
      { trips[], ts }

┌─────────────────────────┐
│  announcement_template  │ ← Шаблоны текстов объявлений
│─────────────────────────│
│ id (PK)                 │
│ event_type              │
│ language                │
│ template_text           │
│ voice_id                │
│ is_active               │
└──────────┬──────────────┘
           │ используется при
           │ создании объявления
           ▼
┌─────────────────────────┐      ┌──────────────────────┐
│     announcement        │      │    audio_cache       │
│─────────────────────────│      │──────────────────────│
│ id (PK)                 │ N:1  │ id (PK)              │
│ station_id              │──────│ text_hash            │
│ template_id (FK, NULL)  │      │ voice_id             │
│ announcement_type       │      │ tts_provider         │
│ priority                │      │ is_fallback          │
│ text                    │      │ file_path (MinIO)    │
│ audio_cache_id (FK)     │      │ duration_ms          │
│ status                  │      │ file_size_bytes      │
│ scheduled_at            │      │ created_at           │
│ played_at               │      └──────────────────────┘
│ trip_id (NULL)          │
│ created_by              │
│ created_at              │
└─────────────────────────┘

┌──────────────────────────────────┐
│    scheduled_announcement        │ ← Запланированные авто-объявления
│──────────────────────────────────│
│ id (PK)                          │
│ trip_id                          │
│ station_id                       │
│ event_type                       │
│ scheduled_at                     │
│ status  (PENDING|FIRED|CANCELLED)│
│ announcement_id (FK, NULL)       │
│ created_at                       │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│         station_audio_config     │ ← Настройки аудио на вокзале
│──────────────────────────────────│
│ station_id (PK)                  │
│ voice_id                         │
│ pause_between_ms                 │
│ ad_max_percent_per_hour          │
│ auto_announcements_enabled       │
│ operating_hours_start            │
│ operating_hours_end              │
└──────────────────────────────────┘
```

---

## 5. Схемы таблиц БД

Все таблицы в схеме `notifications`. СУБД: PostgreSQL 15+.

---

### 5.1 `display_board` — Регистрация табло

```sql
CREATE TYPE notifications.board_type AS ENUM ('MAIN', 'PLATFORM');

CREATE TABLE notifications.display_board (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id       UUID NOT NULL,
    board_type       notifications.board_type NOT NULL,
    platform_number  SMALLINT,       -- NULL для MAIN, обязателен для PLATFORM
    name             VARCHAR(100) NOT NULL,  -- Например: "Главное табло", "Перрон 3"
    -- Горизонт отображения рейсов (минуты до и после текущего времени)
    display_window_before_min  INTEGER NOT NULL DEFAULT 30,
    display_window_after_min   INTEGER NOT NULL DEFAULT 180,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_platform_number CHECK (
        (board_type = 'PLATFORM' AND platform_number IS NOT NULL)
        OR
        (board_type = 'MAIN' AND platform_number IS NULL)
    )
);

CREATE INDEX idx_board_station ON notifications.display_board(station_id, is_active);
```

---

### 5.2 `announcement_template` — Шаблоны объявлений

```sql
CREATE TABLE notifications.announcement_template (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Тип события, для которого используется шаблон:
    -- DEPARTURE_30MIN | DEPARTURE_15MIN | BOARDING_OPEN | BOARDING_CLOSE |
    -- TRIP_DELAYED | TRIP_CANCELLED | ARRIVAL_10MIN | ARRIVAL_ACTUAL |
    -- TRANSIT_GATE_OPENED | TRANSIT_GATE_CLOSED | CUSTOM
    event_type    VARCHAR(40) NOT NULL,
    language      CHAR(2) NOT NULL DEFAULT 'ru',
    -- Текст с переменными: {trip_number}, {route_name}, {departure_time},
    -- {platform_number}, {delay_minutes}, {new_departure_time} и др.
    template_text TEXT NOT NULL,
    -- ID голоса Yandex SpeechKit: 'alena' | 'filipp' | 'jane' | 'omazh' | ...
    voice_id      VARCHAR(30) NOT NULL DEFAULT 'alena',
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_by    UUID NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_template_event_lang UNIQUE (event_type, language, is_active)
        DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON COLUMN notifications.announcement_template.template_text IS
    'Пример: "Внимание! Рейс {trip_number} до {destination} отправляется в {departure_time}
     с {platform_number} перрона. Просьба пройти на посадку."';
```

---

### 5.3 `announcement` — Объявления

```sql
CREATE TYPE notifications.announcement_type AS ENUM (
    'FLIGHT_INFO',    -- Рейсовое (автоматическое или ручное диспетчером)
    'INFORMATIONAL',  -- Информационное (только ручное)
    'ADVERTISEMENT'   -- Рекламное
);

CREATE TYPE notifications.announcement_priority AS ENUM (
    'HIGH',    -- Рейсовые объявления
    'MEDIUM',  -- Информационные
    'LOW'      -- Реклама
);

CREATE TYPE notifications.announcement_status AS ENUM (
    'QUEUED',     -- В очереди ожидания
    'PREPARING',  -- Синтез речи в процессе
    'READY',      -- Аудио готово, ожидает своей очереди
    'PLAYING',    -- Воспроизводится в данный момент
    'PLAYED',     -- Успешно озвучено
    'SKIPPED',    -- Пропущено диспетчером
    'CANCELLED',  -- Отменено (рейс отменён, пересчёт и т.д.)
    'FAILED'      -- Ошибка синтеза/воспроизведения
);

CREATE TABLE notifications.announcement (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id        UUID NOT NULL,
    template_id       UUID REFERENCES notifications.announcement_template(id),
    announcement_type notifications.announcement_type NOT NULL,
    priority          notifications.announcement_priority NOT NULL,
    text              TEXT NOT NULL,
    audio_cache_id    UUID REFERENCES notifications.audio_cache(id),
    status            notifications.announcement_status NOT NULL DEFAULT 'QUEUED',
    -- Когда должно быть озвучено (NULL = немедленно / как только дойдёт очередь)
    scheduled_at      TIMESTAMPTZ,
    -- Дедлайн: если не озвучено до этого времени — автоматически отменяется
    deadline_at       TIMESTAMPTZ,
    played_at         TIMESTAMPTZ,
    -- Привязка к рейсу (для дедупликации и пересчёта)
    trip_id           UUID,
    event_type        VARCHAR(40),
    -- Источник: 'system' или ID диспетчера
    created_by        VARCHAR(50) NOT NULL DEFAULT 'system',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Дедупликация: один тип события для одного рейса в очереди
    CONSTRAINT uq_active_trip_event UNIQUE (station_id, trip_id, event_type, status)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_ann_station_status ON notifications.announcement(station_id, status);
CREATE INDEX idx_ann_station_queue  ON notifications.announcement(station_id, priority, scheduled_at)
    WHERE status IN ('QUEUED', 'READY');
CREATE INDEX idx_ann_trip           ON notifications.announcement(trip_id)
    WHERE trip_id IS NOT NULL;
```

---

### 5.4 `audio_cache` — Кеш синтезированных аудиофайлов

```sql
CREATE TYPE notifications.tts_provider AS ENUM ('YANDEX', 'RHVOICE');

CREATE TABLE notifications.audio_cache (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- SHA-256 от (text + voice_id + format)
    text_hash        VARCHAR(64) NOT NULL UNIQUE,
    text_preview     VARCHAR(200) NOT NULL,  -- Первые 200 символов для отладки
    voice_id         VARCHAR(30) NOT NULL,
    tts_provider     notifications.tts_provider NOT NULL,
    is_fallback      BOOLEAN NOT NULL DEFAULT FALSE,
    -- Путь в MinIO: audio-cache/{text_hash[:2]}/{text_hash}.mp3
    file_path        VARCHAR(500) NOT NULL,
    duration_ms      INTEGER NOT NULL CHECK (duration_ms > 0),
    file_size_bytes  INTEGER NOT NULL CHECK (file_size_bytes > 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    use_count        INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_audio_cache_hash     ON notifications.audio_cache(text_hash);
CREATE INDEX idx_audio_cache_provider ON notifications.audio_cache(tts_provider, is_fallback);
```

---

### 5.5 `scheduled_announcement` — Плановые авто-объявления

```sql
CREATE TYPE notifications.scheduled_status AS ENUM (
    'PENDING',    -- Ожидает своего времени
    'FIRED',      -- Передано в очередь объявлений
    'CANCELLED',  -- Отменено (рейс отменён / задержка пересчитана)
    'EXPIRED'     -- Время прошло, объявление не было сделано
);

CREATE TABLE notifications.scheduled_announcement (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id          UUID NOT NULL,
    station_id       UUID NOT NULL,
    event_type       VARCHAR(40) NOT NULL,
    scheduled_at     TIMESTAMPTZ NOT NULL,
    status           notifications.scheduled_status NOT NULL DEFAULT 'PENDING',
    -- Заполняется после перехода в FIRED
    announcement_id  UUID REFERENCES notifications.announcement(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_scheduled_trip_event UNIQUE (trip_id, station_id, event_type, status)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_sched_ann_fire_time ON notifications.scheduled_announcement(scheduled_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_sched_ann_trip      ON notifications.scheduled_announcement(trip_id);
```

---

### 5.6 `station_audio_config` — Настройки аудиосистемы вокзала

```sql
CREATE TABLE notifications.station_audio_config (
    station_id                 UUID PRIMARY KEY,
    -- Голос по умолчанию для вокзала
    voice_id                   VARCHAR(30) NOT NULL DEFAULT 'alena',
    -- Пауза между объявлениями (мс)
    pause_between_ms           INTEGER NOT NULL DEFAULT 3000
                               CHECK (pause_between_ms BETWEEN 1000 AND 10000),
    -- Максимальная доля рекламы в эфире за 1 час (%)
    ad_max_percent_per_hour    SMALLINT NOT NULL DEFAULT 30
                               CHECK (ad_max_percent_per_hour BETWEEN 0 AND 50),
    -- Автоматические объявления включены
    auto_announcements_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- Рабочие часы системы озвучивания (вне этих часов объявления не воспроизводятся)
    operating_hours_start      TIME NOT NULL DEFAULT '06:00',
    operating_hours_end        TIME NOT NULL DEFAULT '23:00',
    -- Громкость (0-100, интерпретируется агентом)
    volume                     SMALLINT NOT NULL DEFAULT 80
                               CHECK (volume BETWEEN 0 AND 100),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 6. Подсистема табло

### 6.1 Архитектура WebSocket-соединений

```
                    notification-service (Go)
                    ┌──────────────────────────────────────┐
                    │                                      │
  NATS events ─────►│  Event Handler                       │
                    │  · scheduling.trip.*                 │
                    │  · inventory.transit_gate.*          │
                    │  · sales.boarding.*                  │
                    │         │                            │
                    │         ▼                            │
                    │  Board State Manager                 │
                    │  · Обновить BoardState               │
                    │  · Сохранить в Redis                 │
                    │  · Опубликовать delta/full           │
                    │         │                            │
                    │         ▼                            │
                    │  WebSocket Hub                       │
                    │  · map[stationId][]Connection        │
                    │  · Broadcast по stationId            │
                    └──────────────────────────────────────┘
                              │  WebSocket
          ┌───────────────────┼─────────────────────┐
          │                   │                     │
    ┌─────▼──────┐    ┌───────▼──────┐    ┌────────▼─────┐
    │  Главное   │    │  Перрон 1    │    │  Перрон 2    │
    │  табло     │    │  (TV + браузер│    │              │
    │  (TV)      │    │              │    │              │
    └────────────┘    └──────────────┘    └──────────────┘
```

### 6.2 WebSocket-протокол (клиент ↔ сервер)

**Подключение клиента:**
```
ws://notification-service/ws/boards/{boardId}?token={jwt}
```

**Сообщения от сервера:**

```typescript
// Полное состояние (при первом подключении или переподключении)
{
  "type": "BOARD_STATE_FULL",
  "board_id": "board-001",
  "board_type": "MAIN",
  "station_id": "st-001",
  "generated_at": "2025-07-15T07:00:00Z",
  "trips": [TripBoardRow, ...]
}

// Дельта-обновление (при изменении конкретного рейса)
{
  "type": "BOARD_STATE_DELTA",
  "board_id": "board-001",
  "generated_at": "2025-07-15T07:28:00Z",
  "updated_trips": [TripBoardRow],
  "removed_trip_ids": []
}

// Heartbeat (каждые 30 сек, клиент должен ответить pong)
{
  "type": "PING",
  "ts": "2025-07-15T07:00:30Z"
}
```

**Структура `TripBoardRow`:**

```typescript
interface TripBoardRow {
  trip_id: string
  trip_number: string
  direction: "DEPARTURE" | "ARRIVAL"
  route_name: string
  destination_stop: string    // Конечная или начальная остановка
  via_stops: string[]         // Промежуточные остановки через запятую
  platform_number: number | null
  scheduled_time: string      // ISO8601
  estimated_time: string | null
  actual_time: string | null
  display_time: string        // "07:30" — итоговое для отображения
  delay_minutes: number
  status: BoardTripStatus
  status_label: string        // Локализованный текст: "Посадка", "Задержан"
  carrier_name: string
  vehicle_plate: string | null
}

type BoardTripStatus =
  | "SCHEDULED"      // По расписанию
  | "BOARDING"       // Идёт посадка
  | "DEPARTED"       // Отправился
  | "DELAYED"        // Задержан
  | "CANCELLED"      // Отменён
  | "ARRIVING"       // Ожидается прибытие (для прибытий)
  | "ARRIVED"        // Прибыл
```

### 6.3 Хранение состояния табло в Redis

```
# Полное состояние главного табло вокзала
Redis key:  board:state:{stationId}:MAIN
Value:      JSON (BoardState)
TTL:        none (обновляется при каждом событии)

# Состояние перронного табло
Redis key:  board:state:{stationId}:PLATFORM:{platformNo}
Value:      JSON (BoardState для одного рейса)
TTL:        none

# Список подключённых boardId для вокзала (для маршрутизации broadcast)
Redis key:  board:connections:{stationId}
Value:      SET of boardId
TTL:        none (управляется явно при connect/disconnect)
```

### 6.4 Логика формирования состояния табло

```
ФУНКЦИЯ buildBoardState(stationId, boardType, platformNo?):

  1. Получить список рейсов вокзала за горизонт отображения:
     GET scheduling-service /stations/{stationId}/trips
     ?window_before_min=30&window_after_min=180

  2. Для каждого рейса:
     a. Определить direction:
        - если stop.station_id == stationId AND stop.stop_order == 1 → DEPARTURE
        - если stop.station_id == stationId AND stop.stop_order == last → ARRIVAL
        - иначе → DEPARTURE (транзит отображается как отправление)

     b. Вычислить display_time:
        - actual_departure/arrival если заполнено
        - иначе estimated_departure/arrival
        - иначе scheduled_departure/arrival

     c. Определить status:
        - trip.status = CANCELLED → CANCELLED
        - trip.delay_minutes > 0 AND status != DEPARTED → DELAYED
        - trip_stop.stop_status = DEPARTED → DEPARTED
        - boarding_open event получен → BOARDING
        - иначе → SCHEDULED

     d. Для PLATFORM табло: отобрать только рейс текущего перрона

  3. Отсортировать по display_time ASC

  4. Сохранить в Redis

  5. Broadcast через WebSocket всем подключённым клиентам
```

---

## 7. Подсистема озвучивания

### 7.1 Архитектура аудиосистемы

```
notification-service                    Station Agent (вокзал)
┌──────────────────────────────┐       ┌────────────────────────────┐
│                              │       │                            │
│  Announcement Scheduler      │       │  Playback Agent (Go)       │
│  · Планирует авто-объявления │       │  · WebSocket клиент        │
│  · Запускает по времени      │       │  · Получает команды        │
│         │                    │       │  · Управляет аудио         │
│         ▼                    │       │  · ffplay / aplay          │
│  Queue Manager               │ WS    │         │                  │
│  · Независимые очереди       │◄─────►│  Статус ─►                 │
│    по вокзалам                │       │  (PLAYING, DONE, ERROR)    │
│  · Приоритеты                │       │                            │
│  · Дедупликация              │       │  HTTP-сервер               │
│         │                    │       │  GET /audio/{cacheId}      │
│         ▼                    │       │  → стриминг MP3 файла      │
│  TTS Synthesizer             │       └────────────────────────────┘
│  · Yandex SpeechKit          │
│  · RHVoice fallback          │       Физические аудиоустройства:
│  · AudioCache lookup/write   │       ┌──────────────────────────┐
│                              │       │  IP-усилитель / колонки  │
└──────────────────────────────┘       │  (подключён к Station    │
                                       │   Agent через audio out) │
                                       └──────────────────────────┘
```

### 7.2 Жизненный цикл объявления

```
Триггер (NATS событие или диспетчер)
        │
        ▼
┌────────────────────────────────────────────────────────┐
│  1. СОЗДАНИЕ ОБЪЯВЛЕНИЯ                                │
│                                                        │
│  · Определить шаблон по event_type                     │
│  · Подставить переменные → получить текст              │
│  · Проверить дедупликацию (BR-NOT-015)                 │
│  · INSERT announcement (status=QUEUED)                 │
│  · Добавить в очередь вокзала с учётом приоритета      │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│  2. ПОДГОТОВКА АУДИО (статус: PREPARING)               │
│                                                        │
│  · Вычислить text_hash = sha256(text + voice_id)       │
│  · Поиск в audio_cache по text_hash                    │
│    → Найдено: пропустить синтез, status=READY          │
│    → Не найдено: запрос к TTS провайдеру               │
│      → Yandex SpeechKit (PRIMARY)                      │
│        → Успех: сохранить в MinIO + audio_cache        │
│        → Таймаут/ошибка: RHVoice (FALLBACK)            │
│          → Синтез локально                             │
│          → Сохранить с is_fallback=true                │
│  · UPDATE announcement.audio_cache_id                  │
│  · status = READY                                      │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│  3. ВОСПРОИЗВЕДЕНИЕ (статус: PLAYING)                  │
│                                                        │
│  · Queue Manager отправляет команду Station Agent:     │
│    WS: { "cmd": "PLAY",                                │
│          "announcement_id": "...",                     │
│          "audio_url": "/audio/{cacheId}" }             │
│  · Station Agent скачивает MP3 из notification-service │
│  · Воспроизводит через системный аудиовыход            │
│  · Отправляет статус DONE / ERROR обратно              │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│  4. ЗАВЕРШЕНИЕ                                         │
│                                                        │
│  · status = PLAYED / FAILED                            │
│  · Пауза {pause_between_ms} мс                         │
│  · Переход к следующему в очереди                      │
└────────────────────────────────────────────────────────┘
```

---

## 8. Синтез речи и аудиокеш

### 8.1 Интеграция с Yandex SpeechKit

```go
// Go: запрос к Yandex SpeechKit TTS API
type YandexTTSRequest struct {
    Text         string `json:"text"`
    Voice        string `json:"voice"`        // "alena", "filipp" и др.
    Format       string `json:"format"`       // "mp3"
    SampleRate   int    `json:"sampleRateHertz"` // 22050
    FolderId     string `json:"folderId"`
}

func (t *YandexTTSProvider) Synthesize(text, voiceID string) ([]byte, error) {
    req := YandexTTSRequest{
        Text:       text,
        Voice:      voiceID,
        Format:     "mp3",
        SampleRate: 22050,
        FolderId:   t.config.FolderID,
    }

    ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
    defer cancel()

    resp, err := t.client.Post(ctx, "/tts:synthesize", req)
    if err != nil {
        // Fallback to RHVoice
        return nil, ErrProviderUnavailable
    }
    return io.ReadAll(resp.Body)
}
```

### 8.2 Интеграция с RHVoice (локальный fallback)

```go
// RHVoice запускается как локальный HTTP-сервис на Station Agent
// или на ядре для генерации fallback-файлов

type RHVoiceProvider struct {
    baseURL string // http://localhost:8880
}

func (r *RHVoiceProvider) Synthesize(text, voiceID string) ([]byte, error) {
    // RHVoice REST API (rhvoice-rest)
    url := fmt.Sprintf("%s/say?text=%s&voice=%s&format=mp3",
        r.baseURL,
        url.QueryEscape(text),
        voiceID,
    )
    resp, err := http.Get(url)
    if err != nil {
        return nil, fmt.Errorf("rhvoice unavailable: %w", err)
    }
    return io.ReadAll(resp.Body)
}
```

### 8.3 Алгоритм кеширования

```go
func (s *TTSSynthesizer) GetOrSynthesize(text, voiceID string) (*AudioCacheEntry, error) {

    // 1. Вычислить хеш
    hash := sha256.Sum256([]byte(text + "|" + voiceID + "|mp3"))
    textHash := hex.EncodeToString(hash[:])

    // 2. Поиск в БД
    cached, err := s.repo.FindByHash(textHash)
    if err == nil && cached != nil {
        // Обновить last_used_at и use_count
        s.repo.UpdateUsage(cached.ID)
        return cached, nil
    }

    // 3. Синтез через PRIMARY провайдер
    audioBytes, err := s.primary.Synthesize(text, voiceID)
    isFallback := false

    if err != nil {
        // 4. Fallback
        log.Warn("Primary TTS unavailable, using fallback", "error", err)
        audioBytes, err = s.fallback.Synthesize(text, voiceID)
        if err != nil {
            return nil, fmt.Errorf("all TTS providers failed: %w", err)
        }
        isFallback = true
    }

    // 5. Определить длительность MP3
    durationMs := mp3Duration(audioBytes)

    // 6. Сохранить в MinIO
    filePath := fmt.Sprintf("audio-cache/%s/%s.mp3", textHash[:2], textHash)
    if err := s.storage.Put(filePath, audioBytes); err != nil {
        return nil, fmt.Errorf("storage write failed: %w", err)
    }

    // 7. Сохранить запись в БД
    entry := &AudioCacheEntry{
        TextHash:      textHash,
        TextPreview:   truncate(text, 200),
        VoiceID:       voiceID,
        TtsProvider:   providerName(isFallback),
        IsFallback:    isFallback,
        FilePath:      filePath,
        DurationMs:    durationMs,
        FileSizeBytes: len(audioBytes),
    }
    return s.repo.Save(entry)
}
```

### 8.4 Структура хранения аудиокеша в MinIO

```
minio/
└── transora-audio/
    ├── audio-cache/
    │   ├── a3/
    │   │   └── a3f5c9d2e1b847f6....mp3
    │   ├── b7/
    │   │   └── b7e2a1d4c8f03912....mp3
    │   └── ...  (первые 2 символа хеша = директория)
    └── ad-content/
        └── {id}.mp3   ← рекламные аудиоролики (загружаются вручную)
```

---

## 9. Очередь сообщений и приоритеты

### 9.1 Структура очереди в памяти

```go
// Queue Manager — независимая очередь на каждый вокзал
type StationQueue struct {
    StationID   uuid.UUID
    mu          sync.Mutex
    high        []*Announcement   // приоритет HIGH
    medium      []*Announcement   // приоритет MEDIUM
    low         []*Announcement   // приоритет LOW
    currentItem *Announcement     // воспроизводится сейчас
    paused      bool
}

func (q *StationQueue) Next() *Announcement {
    q.mu.Lock()
    defer q.mu.Unlock()

    // Строгий приоритет: HIGH → MEDIUM → LOW
    if item := q.nextReady(q.high); item != nil {
        return item
    }
    if item := q.nextReady(q.medium); item != nil {
        return item
    }
    // LOW воспроизводится только если HIGH и MEDIUM пусты
    // и не превышена квота рекламы за час
    if q.adQuotaOk() {
        return q.nextReady(q.low)
    }
    return nil
}

func (q *StationQueue) nextReady(items []*Announcement) *Announcement {
    now := time.Now()
    for i, item := range items {
        if item.Status == StatusReady &&
           (item.ScheduledAt == nil || item.ScheduledAt.Before(now)) &&
           (item.DeadlineAt == nil || item.DeadlineAt.After(now)) {
            // Удалить из слайса
            q.high = append(items[:i], items[i+1:]...)
            return item
        }
    }
    return nil
}
```

### 9.2 Правила вытеснения при переполнении

```
Очередь достигла 50 объявлений (BR-NOT-017):

  1. Если есть LOW (реклама) → вытеснить самое старое LOW
  2. Если нет LOW и очередь MEDIUM переполнена → 
     вытеснить самое старое MEDIUM с типом INFO 
     (информационное без привязки к рейсу)
  3. Рейсовые объявления (HIGH) никогда не вытесняются
  4. Если вытеснение невозможно → отклонить новое объявление
     с ошибкой QUEUE_FULL
```

### 9.3 API управления очередью для диспетчера

```
# Получить текущую очередь вокзала
GET /announcements/queue?stationId={stationId}
Response: { playing, queued: [Announcement...], stats }

# Добавить ручное объявление (вне очереди)
POST /announcements
Body: {
  "station_id": "st-001",
  "text": "Уважаемые пассажиры, временно закрыт перрон №2",
  "priority": "MEDIUM",
  "announcement_type": "INFORMATIONAL",
  "play_immediately": false   // true = вставить в начало MEDIUM
}

# Пропустить текущее объявление
POST /announcements/{id}/skip

# Удалить из очереди (ещё не озвученное)
DELETE /announcements/{id}

# Приостановить очередь вокзала
POST /announcements/queue/pause?stationId={stationId}

# Возобновить
POST /announcements/queue/resume?stationId={stationId}

# Изменить текст ещё не озвученного объявления
PUT /announcements/{id}/text
Body: { "text": "Новый текст объявления" }
# При изменении текста: аудио из кеша инвалидируется,
# запускается новый синтез
```

---

## 10. Шаблоны объявлений

### 10.1 Стандартные шаблоны (русский язык)

```
event_type: DEPARTURE_30MIN
template_text: "Внимание! Через тридцать минут отправляется рейс {trip_number}
  до {destination}. Просьба пройти регистрацию в кассе."

event_type: DEPARTURE_15MIN
template_text: "Внимание! Рейс {trip_number} {route_name} отправляется в {departure_time}.
  Просьба пройти на {platform_number} перрон."

event_type: BOARDING_OPEN
template_text: "Объявляется посадка на рейс {trip_number} до {destination}.
  Отправление в {departure_time} с {platform_number} перрона.
  Просьба пройти на посадку."

event_type: BOARDING_CLOSE
template_text: "Заканчивается посадка на рейс {trip_number} до {destination}.
  Просьба срочно пройти на {platform_number} перрон."

event_type: TRIP_DELAYED
template_text: "Уважаемые пассажиры! Рейс {trip_number} до {destination}
  задерживается на {delay_minutes} минут.
  Новое время отправления — {new_departure_time}.
  Приносим свои извинения за доставленные неудобства."

event_type: TRIP_CANCELLED
template_text: "Уважаемые пассажиры! Рейс {trip_number} до {destination}
  отменяется. Просьба обратиться в кассу для возврата билетов."

event_type: ARRIVAL_10MIN
template_text: "Через десять минут ожидается прибытие рейса {trip_number}
  из {origin} на {platform_number} перрон."

event_type: ARRIVAL_ACTUAL
template_text: "Прибыл рейс {trip_number} из {origin}.
  Перрон {platform_number}."

event_type: TRANSIT_GATE_OPENED
template_text: "Уважаемые пассажиры! Открыта продажа билетов
  на проходящий рейс {trip_number} до {destination}.
  Отправление в {departure_time}. Обращайтесь в кассу."
```

### 10.2 Переменные шаблонов

| Переменная | Источник | Пример |
|------------|---------|--------|
| `{trip_number}` | trip.trip_number | «101» |
| `{route_name}` | route.name | «Красноярск — Канск» |
| `{destination}` | Последняя остановка маршрута | «Канск» |
| `{origin}` | Первая остановка маршрута | «Красноярск» |
| `{departure_time}` | estimated или scheduled departure | «семь тридцать» |
| `{new_departure_time}` | Пересчитанное время | «семь пятьдесят пять» |
| `{platform_number}` | trip_stop.platform_number | «второго» |
| `{delay_minutes}` | trip.delay_minutes | «двадцать пять» |
| `{carrier_name}` | carrier.name | «АвтоЭкспресс» |

> **Важно:** Время и числа должны передаваться в TTS в текстовом виде («семь тридцать», «двадцать пять минут»), не цифрами — Yandex SpeechKit и RHVoice читают числа по-разному. Функция форматирования реализуется на уровне сервиса перед синтезом.

---

## 11. Примеры данных

### 11.1 Конфигурация аудиосистемы вокзала

```json
{
  "station_id": "st-001",
  "voice_id": "alena",
  "pause_between_ms": 3000,
  "ad_max_percent_per_hour": 30,
  "auto_announcements_enabled": true,
  "operating_hours_start": "06:00",
  "operating_hours_end": "23:00",
  "volume": 80
}
```

### 11.2 Объявление о задержке рейса

```json
{
  "id": "ann-001",
  "station_id": "st-001",
  "template_id": "tmpl-delay",
  "announcement_type": "FLIGHT_INFO",
  "priority": "HIGH",
  "text": "Уважаемые пассажиры! Рейс сто один до Канска задерживается
           на двадцать пять минут. Новое время отправления — семь пятьдесят пять.
           Приносим свои извинения за доставленные неудобства.",
  "audio_cache_id": "cache-b7e2a1d4",
  "status": "PLAYED",
  "scheduled_at": null,
  "deadline_at": "2025-07-15T08:30:00+07:00",
  "played_at": "2025-07-15T07:29:05+07:00",
  "trip_id": "trip-e5f6a7b8",
  "event_type": "TRIP_DELAYED",
  "created_by": "system",
  "created_at": "2025-07-15T07:28:10+07:00"
}
```

### 11.3 Запись аудиокеша

```json
{
  "id": "cache-b7e2a1d4",
  "text_hash": "b7e2a1d4c8f039120a5b3c7e9f1d2048...",
  "text_preview": "Уважаемые пассажиры! Рейс сто один до Канска задерживается...",
  "voice_id": "alena",
  "tts_provider": "YANDEX",
  "is_fallback": false,
  "file_path": "audio-cache/b7/b7e2a1d4c8f039120a5b3c7e9f1d2048.mp3",
  "duration_ms": 8400,
  "file_size_bytes": 50400,
  "created_at": "2025-07-15T07:28:11+07:00",
  "last_used_at": "2025-07-15T07:28:11+07:00",
  "use_count": 1
}
```

### 11.4 Плановые объявления для рейса

```json
[
  {
    "trip_id": "trip-e5f6a7b8",
    "station_id": "st-001",
    "event_type": "DEPARTURE_30MIN",
    "scheduled_at": "2025-07-15T07:00:00+07:00",
    "status": "FIRED"
  },
  {
    "trip_id": "trip-e5f6a7b8",
    "station_id": "st-001",
    "event_type": "DEPARTURE_15MIN",
    "scheduled_at": "2025-07-15T07:15:00+07:00",
    "status": "FIRED"
  },
  {
    "trip_id": "trip-e5f6a7b8",
    "station_id": "st-001",
    "event_type": "BOARDING_OPEN",
    "scheduled_at": null,
    "status": "PENDING"
  }
]
```

### 11.5 Состояние очереди (ответ API диспетчеру)

```json
{
  "station_id": "st-001",
  "paused": false,
  "playing": {
    "id": "ann-005",
    "text": "Рейс сто три до Иланского отправляется в четырнадцать ноль ноль...",
    "priority": "HIGH",
    "started_at": "2025-07-15T07:35:00+07:00",
    "duration_ms": 7200
  },
  "queued": [
    {
      "id": "ann-006",
      "priority": "HIGH",
      "text": "Прибыл рейс двести двенадцать из Канска...",
      "status": "READY",
      "scheduled_at": null
    },
    {
      "id": "ann-007",
      "priority": "MEDIUM",
      "text": "Уважаемые пассажиры, зал ожидания работает...",
      "status": "READY",
      "scheduled_at": null
    }
  ],
  "stats": {
    "high_count": 1,
    "medium_count": 1,
    "low_count": 3,
    "played_last_hour": 14,
    "ad_percent_last_hour": 18
  }
}
```

---

## 12. События (NATS JetStream)

### События, которые сервис **потребляет**

| Subject | Источник | Действие |
|---------|----------|---------|
| `scheduling.trip.created` | scheduling-service | Запланировать авто-объявления для рейса |
| `scheduling.trip.status_changed` | scheduling-service | Обновить табло; отменить/пересоздать объявления |
| `scheduling.trip.delay_updated` | scheduling-service | Обновить табло; создать объявление о задержке; пересчитать расписание |
| `scheduling.trip.departed` | scheduling-service | Обновить статус табло → DEPARTED |
| `scheduling.trip.arrived` | scheduling-service | Обновить статус табло → ARRIVED; создать объявление |
| `scheduling.trip.cancelled` | scheduling-service | Обновить табло → CANCELLED; создать объявление об отмене; отменить запланированные |
| `inventory.transit_gate.opened` | inventory-service | Создать объявление об открытии транзитных продаж |
| `inventory.transit_gate.closed` | inventory-service | Создать объявление о завершении посадки |
| `sales.boarding.started` | sales-service | Создать объявление о начале посадки; обновить табло → BOARDING |
| `sales.ticket.issued` | sales-service | Обновить счётчик мест на табло (опционально) |

### События, которые сервис **публикует** (stream: `transora.notifications`)

| Subject | Триггер | Потребители |
|---------|---------|------------|
| `notifications.announcement.played` | Объявление воспроизведено | — (аудит) |
| `notifications.announcement.failed` | Ошибка воспроизведения | — (алерты) |
| `notifications.board.updated` | Состояние табло изменилось | — (мониторинг) |

---

## 13. Зависимости и взаимодействие

```
                  ┌────────────────────────────────┐
                  │       notification-service      │
                  │            (Go)                │
                  └──────────────┬─────────────────┘
                                 │
        ┌────────────────────────┼───────────────────────┐
        │                        │                       │
  Потребляет                Синхронные               Публикует
  события NATS              REST-запросы             события NATS
        │                   (данные рейсов)               │
        │                        │                       │
┌───────▼──────────┐  ┌──────────▼──────────┐  ┌────────▼──────────┐
│ scheduling-svc   │  │  scheduling-service │  │   (аудит,         │
│ inventory-svc    │  │  /trips/{id}        │  │    мониторинг)    │
│ sales-service    │  └─────────────────────┘  └───────────────────┘
└──────────────────┘

  Исходящие соединения:
┌──────────────────────┐  ┌──────────────────────┐
│  Yandex SpeechKit    │  │  Station Agent       │
│  (TTS API, HTTPS)    │  │  (WebSocket:         │
│                      │  │   табло + аудио)     │
└──────────────────────┘  └──────────────────────┘
┌──────────────────────┐
│  RHVoice (local)     │
│  (HTTP fallback TTS) │
└──────────────────────┘

  Хранилища:
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐
│   PostgreSQL         │  │      Redis           │  │     MinIO        │
│  (объявления,        │  │  (board states,      │  │  (MP3 аудио,     │
│   кеш метаданные,    │  │   очередь,           │  │   кеш файлы)     │
│   конфиги)           │  │   WS соединения)     │  │                  │
└──────────────────────┘  └──────────────────────┘  └──────────────────┘

  Входящие WebSocket (клиенты):
┌──────────────────────┐  ┌──────────────────────┐
│  Табло (браузер, TV) │  │  Station Agent       │
│  /ws/boards/{boardId}│  │  (Playback Agent)    │
└──────────────────────┘  └──────────────────────┘
```

**Ключевые архитектурные решения:**

- **Go для I/O нагрузки** — сотни одновременных WebSocket-соединений (табло, агенты) обрабатываются эффективно благодаря goroutine-модели.
- **Очередь в памяти + Redis** — активная очередь живёт в памяти процесса для минимальной латентности. Redis используется для восстановления состояния после перезапуска.
- **Station Agent как прокси воспроизведения** — сервер не воспроизводит аудио напрямую, он лишь отдаёт MP3-файлы и управляет очередью. Воспроизведение — ответственность агента на вокзале.
- **Детерминированный кеш** — один и тот же текст с одним голосом всегда даёт один и тот же хеш, что гарантирует высокое попадание в кеш для стандартных шаблонных фраз.

---

*Следующий документ: `transora-hardware-agent.md` — Go-агент для управления кассовым оборудованием*
