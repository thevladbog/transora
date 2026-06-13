# Transora — Inventory Service
## Сервис управления инвентарём мест: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `inventory-service`

---

## Содержание

1. [Назначение сервиса](#1-назначение-сервиса)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Бизнес-правила и ограничения](#3-бизнес-правила-и-ограничения)
4. [Модель данных (ERD)](#4-модель-данных-erd)
5. [Схемы таблиц БД](#5-схемы-таблиц-бд)
6. [Механизм резервирования мест](#6-механизм-резервирования-мест)
7. [Система блокировок продаж](#7-система-блокировок-продаж)
8. [Обслуживание транзитных рейсов](#8-обслуживание-транзитных-рейсов)
9. [Примеры данных](#9-примеры-данных)
10. [Жизненный цикл места](#10-жизненный-цикл-места)
11. [События (NATS JetStream)](#11-события-nats-jetstream)
12. [Зависимости и взаимодействие](#12-зависимости-и-взаимодействие)

---

## 1. Назначение сервиса

`inventory-service` — сервис, отвечающий за управление инвентарём мест по всем рейсам системы Transora. Является **единственным авторитетным источником правды** о доступности, статусе и блокировках мест.

Основные обязанности:

- Ведение реестра мест по каждому рейсу с актуальным статусом
- Резервирование мест при начале оформления с автоматическим TTL
- Подтверждение продажи и освобождение мест при возврате
- Управление правилами блокировок: квоты по вокзалам, ручные блокировки
- Управление инвентарём транзитных рейсов (открытие продаж по факту прибытия)
- Публикация событий изменения доступности для кассовых мест, табло и других потребителей

**Критическое требование:** все операции изменения статуса мест должны быть атомарны и защищены от гонки условий (race condition). Двойная продажа одного места недопустима.

**Стек:** Kotlin + Spring Boot + PostgreSQL + Redis + NATS JetStream

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **TripInventory** | Инвентарь рейса — совокупность всех мест конкретного рейса с их статусами |
| **SeatRecord** | Запись о конкретном месте в конкретном рейсе |
| **Reservation** | Временная блокировка места при начале оформления билета. Имеет TTL. |
| **SalesRestriction** | Правило, ограничивающее продажу определённых мест на определённом вокзале |
| **TransitGate** | Объект управления продажами на транзитном рейсе. Открывается диспетчером по факту прибытия. |
| **BlockingRule** | Конкретная блокировка места (ручная или системная) |
| **SegmentSale** | Продажа места на отрезке маршрута (от остановки A до остановки B) |
| **SeatAvailability** | Проекция доступных мест для конкретного вокзала с учётом всех ограничений |

---

## 3. Бизнес-правила и ограничения

### 3.1 Общие правила инвентаря

- **BR-INV-001:** Инвентарь рейса создаётся автоматически при получении события `scheduling.trip.created` и наличии у рейса назначенного ТС со схемой мест.
- **BR-INV-002:** Если рейс создан без ТС, инвентарь создаётся при получении события `scheduling.trip.vehicle_changed` (первое назначение ТС).
- **BR-INV-003:** При замене ТС на рейс инвентарь пересчитывается. Проданные места сохраняют статус `SOLD`. Если новое ТС имеет меньше мест и некоторые проданные места отсутствуют в новой схеме — они помечаются флагом `requires_reaccommodation = true` и генерируется событие `inventory.reaccommodation.required`.
- **BR-INV-004:** Инвентарь рейса доступен только для чтения после перехода рейса в статус `COMPLETED` или `CANCELLED`.
- **BR-INV-005:** Место может быть продано только один раз на один и тот же сегмент маршрута. Продажи на непересекающиеся сегменты одного места допускаются (например, место 10 продано от ост. 1 до ост. 2, и от ост. 3 до ост. 5).

### 3.2 Резервирование мест

- **BR-INV-010:** TTL резервирования — **10 минут** с момента создания. Настраивается на уровне конфигурации сервиса.
- **BR-INV-011:** По истечении TTL резервирование снимается автоматически фоновым процессом (Redis expiry + планировщик).
- **BR-INV-012:** Одновременно на одно место может существовать только одно активное резервирование.
- **BR-INV-013:** Резервирование привязано к сессии кассира (`cashier_session_id`). При разрыве сессии все резервирования сессии освобождаются немедленно.
- **BR-INV-014:** Кассир не может создать более **10 резервирований** в рамках одной транзакции (групповая продажа).

### 3.3 Блокировки по вокзалам (квоты)

- **BR-INV-020:** Для каждой пары (вокзал, рейс) может быть задан список разрешённых для продажи мест (`SalesRestriction`). Если правило задано — кассир на данном вокзале может продавать **только** указанные места.
- **BR-INV-021:** Если для пары (вокзал, рейс) правило не задано — кассир может продавать любые свободные места (с учётом других ограничений).
- **BR-INV-022:** Правило может быть привязано к `schedule_entry` (применяется ко всем будущим рейсам шаблона) или к конкретному `trip_id` (только для одного рейса).
- **BR-INV-023:** При наличии обоих типов правил для одной пары (вокзал, рейс) приоритет имеет правило на конкретный `trip_id`.
- **BR-INV-024:** Диспетчер вокзала может приостановить (`PAUSED`) или возобновить (`ACTIVE`) правило. Изменение вступает в силу немедленно.
- **BR-INV-025:** Администратор системы может создавать, изменять и удалять правила для любого вокзала. Диспетчер — только приостанавливать/возобновлять существующие.

### 3.4 Ручные блокировки мест

- **BR-INV-030:** Диспетчер может заблокировать конкретное место на конкретном рейсе вручную (например, для VIP-пассажира, неисправного кресла и т.д.) с указанием причины.
- **BR-INV-031:** Заблокированное вручную место недоступно для продажи ни на одном вокзале.
- **BR-INV-032:** Снять ручную блокировку может только диспетчер вокзала, установившего блокировку, или администратор системы.

### 3.5 Транзитные рейсы

- **BR-INV-040:** Рейс считается транзитным для вокзала, если вокзал не является ни начальной, ни конечной остановкой маршрута.
- **BR-INV-041:** Для транзитного рейса продажи на данном вокзале по умолчанию заблокированы до ручного открытия диспетчером (`TransitGate.status = AWAITING_ARRIVAL`).
- **BR-INV-042:** Диспетчер открывает продажи после физического прибытия автобуса, внося список фактически свободных мест.
- **BR-INV-043:** При открытии транзитных продаж диспетчер указывает только те места, которые реально свободны согласно информации от водителя. Система сверяет с уже проданными местами и принимает только места в статусе `FREE`.
- **BR-INV-044:** После завершения посадки диспетчер закрывает транзитные продажи (`TransitGate.status = CLOSED`). После закрытия продажа на данном вокзале и всех последующих вокзалах маршрута блокируется.
- **BR-INV-045:** Повторное открытие закрытого TransitGate разрешено только администратору системы.

### 3.6 Посегментные продажи

- **BR-INV-050:** Место считается занятым на сегменте [A, B], если существует продажа с `from_stop_order <= A` и `to_stop_order >= B`.
- **BR-INV-051:** При проверке доступности места для вокзала учитывается сегмент: от текущего вокзала до конечной остановки рейса (или остановки назначения пассажира).
- **BR-INV-052:** Продажа места на сегмент, пересекающийся с уже проданным сегментом — запрещена.

---

## 4. Модель данных (ERD)

```
┌──────────────────────┐
│    trip_inventory    │ ◄── Создаётся на каждый рейс при назначении ТС
│──────────────────────│
│ id (PK)              │
│ trip_id (FK, UNIQUE) │
│ vehicle_id (FK)      │
│ seat_layout_id (FK)  │
│ total_seats          │
│ status               │     1:N
│ created_at           ├──────────────────────────────────┐
└──────────────────────┘                                  │
                                                          ▼
                                               ┌──────────────────────┐
                                               │     seat_record      │
                                               │──────────────────────│
                                               │ id (PK)              │
                                               │ inventory_id (FK)    │
                                               │ seat_number          │
                                               │ seat_type            │
                                               │ status               │
                                               │ requires_reaccom.    │
                                               └──────────┬───────────┘
                                                          │
                              ┌───────────────────────────┼──────────────────────┐
                              │                           │                      │
                    ┌─────────▼────────┐      ┌──────────▼───────┐   ┌──────────▼──────────┐
                    │   reservation    │      │   seat_sale      │   │  seat_block         │
                    │──────────────────│      │──────────────────│   │─────────────────────│
                    │ id (PK)          │      │ id (PK)          │   │ id (PK)             │
                    │ seat_record_id   │      │ seat_record_id   │   │ seat_record_id      │
                    │ cashier_sess_id  │      │ ticket_id (FK)   │   │ blocked_by (user)   │
                    │ station_id       │      │ from_stop_order  │   │ station_id          │
                    │ from_stop_order  │      │ to_stop_order    │   │ reason              │
                    │ to_stop_order    │      │ sold_at          │   │ block_type          │
                    │ expires_at       │      │ sold_by          │   │ created_at          │
                    │ status           │      │ station_id       │   │ released_at         │
                    │ created_at       │      └──────────────────┘   │ released_by         │
                    └──────────────────┘                             └─────────────────────┘

┌────────────────────────────┐        ┌──────────────────────────────────┐
│      sales_restriction     │        │          transit_gate            │
│────────────────────────────│        │──────────────────────────────────│
│ id (PK)                    │        │ id (PK)                          │
│ station_id (FK)            │        │ trip_id (FK)                     │
│ trip_id (FK, NULL)         │        │ station_id (FK)                  │
│ schedule_entry_id (FK,NULL)│        │ stop_order                       │
│ allowed_seats (INT[])      │        │ status                           │
│ status                     │        │ opened_by                        │
│ scope                      │        │ opened_at                        │
│ created_by                 │        │ closed_by                        │
│ created_at                 │        │ closed_at                        │
└────────────────────────────┘        │ available_seats (INT[])          │
                                      │ notes                            │
                                      └──────────────────────────────────┘
```

---

## 5. Схемы таблиц БД

Все таблицы в схеме `inventory`. СУБД: PostgreSQL 15+.

---

### 5.1 `trip_inventory` — Инвентарь рейса

```sql
CREATE TYPE inventory.inventory_status AS ENUM (
    'INITIALIZING',   -- Создаётся (обработка события от scheduling)
    'ACTIVE',         -- Активен, продажи возможны
    'FROZEN',         -- Заморожен (рейс завершён или отменён)
    'REACCOMMODATING' -- Пересчёт после замены ТС
);

CREATE TABLE inventory.trip_inventory (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id         UUID NOT NULL UNIQUE,           -- Ссылка на trip в scheduling-service
    vehicle_id      UUID NOT NULL,                  -- Денормализовано из scheduling
    seat_layout_id  UUID NOT NULL,                  -- Денормализовано из scheduling
    total_seats     SMALLINT NOT NULL CHECK (total_seats > 0),
    status          inventory.inventory_status NOT NULL DEFAULT 'INITIALIZING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_trip ON inventory.trip_inventory(trip_id);

COMMENT ON TABLE inventory.trip_inventory IS
    'Инвентарь мест для конкретного рейса. Создаётся по событию из scheduling-service.';
```

---

### 5.2 `seat_record` — Запись о месте в рейсе

```sql
CREATE TYPE inventory.seat_status AS ENUM (
    'FREE',                   -- Свободно, доступно для продажи
    'RESERVED',               -- Временно зарезервировано (TTL активен)
    'SOLD',                   -- Продано
    'BLOCKED',                -- Заблокировано вручную
    'UNAVAILABLE'             -- Недоступно (технически, не в схеме нового ТС)
);

CREATE TYPE inventory.seat_type AS ENUM (
    'STANDARD',
    'UPPER_BERTH',
    'LOWER_BERTH',
    'DISABLED'
);

CREATE TABLE inventory.seat_record (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_id             UUID NOT NULL REFERENCES inventory.trip_inventory(id),
    seat_number              SMALLINT NOT NULL CHECK (seat_number > 0),
    seat_type                inventory.seat_type NOT NULL DEFAULT 'STANDARD',
    status                   inventory.seat_status NOT NULL DEFAULT 'FREE',
    requires_reaccommodation BOOLEAN NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_seat_inventory UNIQUE (inventory_id, seat_number)
);

CREATE INDEX idx_seat_inventory        ON inventory.seat_record(inventory_id);
CREATE INDEX idx_seat_status           ON inventory.seat_record(inventory_id, status);
CREATE INDEX idx_seat_reaccommodation  ON inventory.seat_record(requires_reaccommodation)
    WHERE requires_reaccommodation = TRUE;

COMMENT ON COLUMN inventory.seat_record.requires_reaccommodation IS
    'TRUE если место было продано, но при замене ТС оно отсутствует в новой схеме.
     Требует ручного урегулирования администратором.';
```

---

### 5.3 `reservation` — Резервирования (TTL)

```sql
CREATE TYPE inventory.reservation_status AS ENUM (
    'ACTIVE',    -- Резервирование действует
    'CONFIRMED', -- Переведено в продажу
    'EXPIRED',   -- Истёк TTL
    'RELEASED'   -- Отменено кассиром или при разрыве сессии
);

CREATE TABLE inventory.reservation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seat_record_id      UUID NOT NULL REFERENCES inventory.seat_record(id),
    cashier_session_id  VARCHAR(128) NOT NULL,   -- ID сессии кассира
    station_id          UUID NOT NULL,
    from_stop_order     SMALLINT NOT NULL,
    to_stop_order       SMALLINT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    status              inventory.reservation_status NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_stop_order CHECK (to_stop_order > from_stop_order)
);

CREATE INDEX idx_reservation_seat       ON inventory.reservation(seat_record_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_reservation_session    ON inventory.reservation(cashier_session_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_reservation_expires    ON inventory.reservation(expires_at)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE inventory.reservation IS
    'Временные резервирования мест. TTL = 10 минут.
     Истёкшие записи очищаются фоновым процессом раз в минуту.';
```

---

### 5.4 `seat_sale` — Продажи мест

```sql
CREATE TABLE inventory.seat_sale (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seat_record_id  UUID NOT NULL REFERENCES inventory.seat_record(id),
    ticket_id       UUID NOT NULL UNIQUE,   -- Ссылка на билет в sales-service
    reservation_id  UUID REFERENCES inventory.reservation(id),
    from_stop_order SMALLINT NOT NULL,
    to_stop_order   SMALLINT NOT NULL,
    station_id      UUID NOT NULL,          -- Вокзал, на котором куплен билет
    sold_by         UUID NOT NULL,          -- ID кассира
    sold_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    refunded_at     TIMESTAMPTZ,            -- Заполняется при возврате
    refunded_by     UUID,

    CONSTRAINT chk_sale_stop_order CHECK (to_stop_order > from_stop_order)
);

CREATE INDEX idx_sale_seat_record ON inventory.seat_sale(seat_record_id);
CREATE INDEX idx_sale_ticket      ON inventory.seat_sale(ticket_id);
CREATE INDEX idx_sale_station     ON inventory.seat_sale(station_id);
CREATE INDEX idx_sale_active      ON inventory.seat_sale(seat_record_id)
    WHERE refunded_at IS NULL;

COMMENT ON TABLE inventory.seat_sale IS
    'Факты продажи мест. Запись не удаляется при возврате — заполняется refunded_at.';
```

---

### 5.5 `seat_block` — Ручные блокировки мест

```sql
CREATE TYPE inventory.block_type AS ENUM (
    'MANUAL',       -- Ручная блокировка диспетчером
    'TECHNICAL',    -- Техническая неисправность кресла
    'REACCOMMODATION' -- Блокировка при пересчёте после замены ТС
);

CREATE TABLE inventory.seat_block (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seat_record_id  UUID NOT NULL REFERENCES inventory.seat_record(id),
    block_type      inventory.block_type NOT NULL DEFAULT 'MANUAL',
    reason          TEXT,
    blocked_by      UUID NOT NULL,        -- ID пользователя
    station_id      UUID NOT NULL,        -- Вокзал, с которого выставлена блокировка
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at     TIMESTAMPTZ,
    released_by     UUID,

    -- Только одна активная блокировка на место
    CONSTRAINT uq_active_block UNIQUE (seat_record_id, released_at)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_block_seat    ON inventory.seat_block(seat_record_id)
    WHERE released_at IS NULL;
CREATE INDEX idx_block_station ON inventory.seat_block(station_id)
    WHERE released_at IS NULL;
```

---

### 5.6 `sales_restriction` — Правила квот по вокзалам

```sql
CREATE TYPE inventory.restriction_status AS ENUM (
    'ACTIVE',   -- Правило активно
    'PAUSED'    -- Приостановлено диспетчером
);

CREATE TYPE inventory.restriction_scope AS ENUM (
    'SCHEDULE_ENTRY',  -- Применяется ко всем рейсам шаблона
    'SPECIFIC_TRIP'    -- Применяется только к конкретному рейсу
);

CREATE TABLE inventory.sales_restriction (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id          UUID NOT NULL,
    -- Только одно из двух должно быть заполнено:
    trip_id             UUID,
    schedule_entry_id   UUID,
    allowed_seats       SMALLINT[] NOT NULL,   -- Список разрешённых номеров мест
    status              inventory.restriction_status NOT NULL DEFAULT 'ACTIVE',
    scope               inventory.restriction_scope NOT NULL,
    created_by          UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paused_by           UUID,
    paused_at           TIMESTAMPTZ,

    -- Ровно одна из ссылок должна быть заполнена
    CONSTRAINT chk_restriction_target CHECK (
        (trip_id IS NOT NULL AND schedule_entry_id IS NULL)
        OR
        (trip_id IS NULL AND schedule_entry_id IS NOT NULL)
    ),
    -- Соответствие scope и заполненного поля
    CONSTRAINT chk_restriction_scope CHECK (
        (scope = 'SPECIFIC_TRIP' AND trip_id IS NOT NULL)
        OR
        (scope = 'SCHEDULE_ENTRY' AND schedule_entry_id IS NOT NULL)
    )
);

CREATE INDEX idx_restriction_station      ON inventory.sales_restriction(station_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_restriction_trip         ON inventory.sales_restriction(trip_id)
    WHERE trip_id IS NOT NULL;
CREATE INDEX idx_restriction_entry        ON inventory.sales_restriction(schedule_entry_id)
    WHERE schedule_entry_id IS NOT NULL;

COMMENT ON COLUMN inventory.sales_restriction.allowed_seats IS
    'Массив номеров мест, доступных для продажи на данном вокзале.
     Пример: {40,41,42,43,44,45} — только эти места можно продать.';
```

---

### 5.7 `transit_gate` — Управление транзитными продажами

```sql
CREATE TYPE inventory.transit_gate_status AS ENUM (
    'AWAITING_ARRIVAL', -- Автобус ещё не прибыл, продажи закрыты
    'OPEN',             -- Диспетчер открыл продажи после прибытия
    'CLOSED'            -- Посадка завершена, продажи закрыты
);

CREATE TABLE inventory.transit_gate (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id          UUID NOT NULL,
    station_id       UUID NOT NULL,
    stop_order       SMALLINT NOT NULL,     -- Порядковый номер остановки маршрута
    status           inventory.transit_gate_status NOT NULL DEFAULT 'AWAITING_ARRIVAL',
    -- Места, доступные для продажи на транзите (заполняется при открытии)
    available_seats  SMALLINT[],
    opened_by        UUID,
    opened_at        TIMESTAMPTZ,
    closed_by        UUID,
    closed_at        TIMESTAMPTZ,
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_transit_gate UNIQUE (trip_id, station_id)
);

CREATE INDEX idx_transit_gate_trip    ON inventory.transit_gate(trip_id);
CREATE INDEX idx_transit_gate_station ON inventory.transit_gate(station_id, status);

COMMENT ON TABLE inventory.transit_gate IS
    'Управление продажами для транзитных рейсов.
     Запись создаётся автоматически при регистрации рейса для каждого транзитного вокзала.
     Открывается вручную диспетчером по факту прибытия автобуса.';
```

---

### 5.8 `inventory_event_log` — Журнал событий инвентаря

```sql
CREATE TABLE inventory.inventory_event_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_id  UUID NOT NULL REFERENCES inventory.trip_inventory(id),
    seat_number   SMALLINT,          -- NULL для событий уровня инвентаря
    event_type    VARCHAR(60) NOT NULL,
    -- SEAT_RESERVED, SEAT_SOLD, SEAT_RELEASED, SEAT_BLOCKED, SEAT_UNBLOCKED,
    -- TRANSIT_GATE_OPENED, TRANSIT_GATE_CLOSED, RESTRICTION_PAUSED, etc.
    actor_id      UUID NOT NULL,     -- Кассир, диспетчер или system
    station_id    UUID,
    payload       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inv_event_inventory ON inventory.inventory_event_log(inventory_id);
CREATE INDEX idx_inv_event_created   ON inventory.inventory_event_log(created_at DESC);
CREATE INDEX idx_inv_event_type      ON inventory.inventory_event_log(event_type);

COMMENT ON TABLE inventory.inventory_event_log IS
    'Неизменяемый журнал всех операций с инвентарём. Только INSERT.';
```

---

## 6. Механизм резервирования мест

Резервирование — критическая операция. Ниже описана полная последовательность с защитой от гонки условий.

### 6.1 Схема резервирования

```
Кассир выбирает место
        │
        ▼
  POST /inventory/trips/{tripId}/seats/{seatNumber}/reserve
        │
        ▼
┌───────────────────────────────────────────────────────┐
│              inventory-service                        │
│                                                       │
│  1. Проверить доступность инвентаря (статус ACTIVE)  │
│                                                       │
│  2. Проверить правила для вокзала:                   │
│     · SalesRestriction (входит ли место в квоту?)    │
│     · TransitGate (открыт ли транзит?)               │
│                                                       │
│  3. Атомарная блокировка в Redis:                    │
│     SET lock:seat:{inventoryId}:{seatNumber}         │
│         {reservationId} NX EX 10                     │
│     → Если lock занят → 409 Conflict                 │
│                                                       │
│  4. BEGIN TRANSACTION (PostgreSQL)                   │
│     SELECT * FROM seat_record                        │
│     WHERE inventory_id = ? AND seat_number = ?       │
│     FOR UPDATE NOWAIT                                │
│     → Если строка заблокирована → 409 Conflict       │
│                                                       │
│  5. Проверить статус seat_record = FREE              │
│     Проверить отсутствие пересечения сегментов       │
│                                                       │
│  6. INSERT INTO reservation (...)                    │
│     UPDATE seat_record SET status = 'RESERVED'       │
│                                                       │
│  7. COMMIT                                           │
│                                                       │
│  8. Запустить TTL в Redis для auto-release:          │
│     SET ttl:reservation:{reservationId} "" EX 600   │
└───────────────────────────────────────────────────────┘
        │
        ▼
  200 OK { reservationId, expiresAt }
```

### 6.2 Подтверждение резервирования (при оплате)

```
POST /inventory/reservations/{reservationId}/confirm
        │
        ▼
  1. Проверить reservation.status = ACTIVE
  2. Проверить reservation.expires_at > NOW()
  3. BEGIN TRANSACTION
     UPDATE seat_record SET status = 'SOLD'
     UPDATE reservation SET status = 'CONFIRMED'
     INSERT INTO seat_sale (...)
     COMMIT
  4. Удалить TTL ключ из Redis
  5. Опубликовать событие inventory.seat.sold
```

### 6.3 Автоматическое освобождение по TTL

```
Фоновый процесс (каждые 30 сек):

SELECT r.* FROM reservation r
WHERE r.status = 'ACTIVE'
  AND r.expires_at < NOW()
FOR UPDATE SKIP LOCKED

→ Для каждой истёкшей записи:
  BEGIN TRANSACTION
    UPDATE reservation SET status = 'EXPIRED'
    UPDATE seat_record SET status = 'FREE'
      WHERE id = reservation.seat_record_id
        AND status = 'RESERVED'  -- доп. проверка
  COMMIT
  → Удалить Redis lock
  → Опубликовать inventory.seat.released
```

### 6.4 Redis-ключи

| Ключ | Значение | TTL | Назначение |
|------|----------|-----|------------|
| `lock:seat:{invId}:{seatNo}` | `{reservationId}` | 10 мин | Distributed lock на резервирование |
| `ttl:reservation:{resId}` | `""` | 10 мин | Триггер для авто-освобождения |
| `session:cashier:{sessionId}` | `[reservationId, ...]` | сессия | Список резервирований сессии |
| `avail:trip:{tripId}:station:{stId}` | `{freeCount}` | 30 сек | Кеш доступных мест для быстрого ответа |

---

## 7. Система блокировок продаж

### 7.1 Алгоритм проверки доступности места для вокзала

```
ФУНКЦИЯ isSeatAvailableForStation(tripId, seatNumber, stationId, fromStop, toStop):

  1. Получить seat_record по tripId + seatNumber
     → Если status != FREE → НЕДОСТУПНО

  2. Проверить ручную блокировку:
     → Если существует активный seat_block → НЕДОСТУПНО (причина: BLOCKED)

  3. Проверить пересечение сегментов:
     → Если существует seat_sale с пересекающимся [from,to] → НЕДОСТУПНО

  4. Проверить TransitGate (если рейс транзитный для вокзала):
     → Если transit_gate.status != OPEN → НЕДОСТУПНО (причина: TRANSIT_CLOSED)
     → Если seatNumber NOT IN transit_gate.available_seats → НЕДОСТУПНО

  5. Проверить SalesRestriction:
     → Найти активное правило для (stationId, tripId) — сначала SPECIFIC_TRIP,
       затем SCHEDULE_ENTRY
     → Если правило найдено И seatNumber NOT IN allowed_seats → НЕДОСТУПНО

  6. → ДОСТУПНО
```

### 7.2 Матрица доступности

| Ситуация | Результат для кассира |
|----------|-----------------------|
| Место свободно, нет ограничений | ✅ Доступно |
| Место зарезервировано другим кассиром | ❌ Недоступно (временно) |
| Место продано | ❌ Недоступно |
| Место заблокировано вручную | ❌ Недоступно |
| Место не входит в квоту вокзала | ❌ Недоступно (нет прав) |
| Транзитный рейс, gate не открыт | ❌ Недоступно (ожидание прибытия) |
| Транзитный рейс, gate открыт, место в списке | ✅ Доступно |
| Транзитный рейс, gate открыт, место НЕ в списке | ❌ Недоступно (занято на борту) |
| Транзитный рейс, gate закрыт | ❌ Недоступно (посадка завершена) |

---

## 8. Обслуживание транзитных рейсов

### 8.1 Полная последовательность

```
┌─────────────────────────────────────────────────────────────────────┐
│                   ЖИЗНЕННЫЙ ЦИКЛ ТРАНЗИТНОГО РЕЙСА                 │
│                         на примере вокзала Б                       │
└─────────────────────────────────────────────────────────────────────┘

Маршрут: Вокзал А → Вокзал Б (транзит) → Вокзал В

ВОКЗАЛ А                    ЯДРО СИСТЕМЫ              ВОКЗАЛ Б
    │                            │                         │
    │ Рейс создан                │                         │
    │ ──────────────────────────►│                         │
    │                            │ Создать transit_gate    │
    │                            │ для Вокзала Б           │
    │                            │ status=AWAITING_ARRIVAL │
    │                            │ ───────────────────────►│
    │                            │                         │ Кассир видит рейс
    │                            │                         │ "Ожидается прибытие"
    │ Продажи открыты            │                         │ (продажи закрыты)
    │ Кассир продаёт места 1-39  │                         │
    │                            │                         │
    │ Рейс отправился            │                         │
    │ ──────────────────────────►│                         │
    │                            │ trip.status = DEPARTED  │
    │                            │ ───────────────────────►│
    │                            │                         │
    │                            │        [Автобус едет]   │
    │                            │                         │
    │                            │         Автобус прибыл  │
    │                            │         на Вокзал Б     │
    │                            │                   ◄─────│ Диспетчер: фиксирует
    │                            │                         │ прибытие, вносит список
    │                            │                         │ свободных мест [40..45]
    │                            │                         │
    │                            │ Открыть transit_gate    │
    │                            │ available=[40,41,42,    │
    │                            │ 43,44,45]               │
    │                            │◄────────────────────────│
    │                            │ status = OPEN           │
    │                            │ ───────────────────────►│
    │                            │                         │ Кассиры видят места
    │                            │                         │ 40-45 как доступные
    │                            │                         │ Продажи идут...
    │                            │                         │
    │                            │         Посадка завершена│
    │                            │                   ◄─────│ Диспетчер: закрывает
    │                            │                         │ transit_gate
    │                            │ status = CLOSED         │
    │                            │ ───────────────────────►│
    │                            │                         │ Продажи закрыты
    │                            │                         │ на Вокзале Б и далее
```

### 8.2 API для работы диспетчера с TransitGate

```
# Получить статус транзитного рейса на своём вокзале
GET /inventory/transit-gates?tripId={tripId}&stationId={stationId}

# Открыть транзитные продажи
POST /inventory/transit-gates/{gateId}/open
Body: {
  "availableSeats": [40, 41, 42, 43, 44, 45],
  "notes": "По данным водителя Иванова С.П."
}

# Закрыть транзитные продажи (завершение посадки)
POST /inventory/transit-gates/{gateId}/close
Body: {
  "notes": "Посадка завершена в 14:35"
}
```

---

## 9. Примеры данных

### 9.1 Инвентарь рейса

```json
{
  "id": "inv-001",
  "trip_id": "trip-e5f6a7b8",
  "vehicle_id": "veh-001",
  "seat_layout_id": "layout-001",
  "total_seats": 45,
  "status": "ACTIVE",
  "summary": {
    "free": 38,
    "reserved": 2,
    "sold": 5,
    "blocked": 0
  }
}
```

### 9.2 Состояние мест для вокзала (ответ API)

```json
{
  "trip_id": "trip-e5f6a7b8",
  "station_id": "st-002",
  "requested_at": "2025-07-15T09:00:00+07:00",
  "seats": [
    { "seat_number": 1,  "status": "SOLD",     "available_for_station": false },
    { "seat_number": 2,  "status": "FREE",     "available_for_station": false,
      "restriction_reason": "NOT_IN_QUOTA" },
    { "seat_number": 40, "status": "FREE",     "available_for_station": true },
    { "seat_number": 41, "status": "RESERVED", "available_for_station": false,
      "restriction_reason": "RESERVED" },
    { "seat_number": 42, "status": "FREE",     "available_for_station": true },
    { "seat_number": 43, "status": "FREE",     "available_for_station": true },
    { "seat_number": 44, "status": "SOLD",     "available_for_station": false },
    { "seat_number": 45, "status": "FREE",     "available_for_station": true }
  ],
  "transit_gate": {
    "status": "OPEN",
    "available_seats": [40, 41, 42, 43, 44, 45],
    "opened_at": "2025-07-15T08:58:00+07:00"
  }
}
```

### 9.3 Правило блокировки (квота вокзала)

```json
{
  "id": "restr-001",
  "station_id": "st-002",
  "schedule_entry_id": "entry-101",
  "trip_id": null,
  "scope": "SCHEDULE_ENTRY",
  "allowed_seats": [40, 41, 42, 43, 44, 45],
  "status": "ACTIVE",
  "created_by": "admin-001",
  "created_at": "2025-06-01T10:00:00Z",
  "comment": "Вокзал Канск — только места 40-45 для продажи"
}
```

### 9.4 Ручная блокировка места

```json
{
  "id": "block-001",
  "seat_record_id": "seat-trip001-38",
  "block_type": "TECHNICAL",
  "reason": "Неисправен ремень безопасности",
  "blocked_by": "dispatcher-002",
  "station_id": "st-001",
  "created_at": "2025-07-15T07:15:00+07:00",
  "released_at": null
}
```

### 9.5 Событие в журнале аудита

```json
{
  "id": "log-inv-001",
  "inventory_id": "inv-001",
  "seat_number": 42,
  "event_type": "SEAT_RESERVED",
  "actor_id": "cashier-003",
  "station_id": "st-002",
  "payload": {
    "reservation_id": "res-001",
    "cashier_session_id": "sess-abc123",
    "from_stop_order": 2,
    "to_stop_order": 3,
    "expires_at": "2025-07-15T09:10:00+07:00"
  },
  "created_at": "2025-07-15T09:00:00+07:00"
}
```

---

## 10. Жизненный цикл места

```
                        ┌────────────────────────────┐
                        │   Создание инвентаря рейса  │
                        │   (событие trip.created)    │
                        └───────────────┬────────────┘
                                        │
                                        ▼
                                    ┌──────┐
                               ┌───►│ FREE │◄───────────────────────┐
                               │    └──┬───┘                        │
                               │       │                            │
                    TTL истёк  │  Кассир резервирует           Возврат билета
                    или сессия │       │                       / снятие брони
                    разорвана  │       ▼                            │
                               │  ┌──────────┐                     │
                               └──│ RESERVED │                     │
                                  └────┬─────┘                     │
                                       │                            │
                                  Оплата прошла                     │
                                       │                            │
                                       ▼                            │
                                   ┌──────┐                         │
                                   │ SOLD │─────────────────────────┘
                                   └──────┘

          Из FREE в любой момент:
                    │
               Диспетчер                         При замене ТС
               блокирует                         (место исчезло)
                    │                                  │
                    ▼                                  ▼
               ┌─────────┐                     ┌─────────────┐
               │ BLOCKED │                     │ UNAVAILABLE │
               └────┬────┘                     └─────────────┘
                    │
               Диспетчер
               снимает блок
                    │
                    ▼
                ┌──────┐
                │ FREE │
                └──────┘
```

---

## 11. События (NATS JetStream)

### События, которые сервис **потребляет**

| Subject | Источник | Действие |
|---------|----------|---------|
| `scheduling.trip.created` | scheduling-service | Создать `trip_inventory` и `transit_gate` записи |
| `scheduling.trip.vehicle_changed` | scheduling-service | Пересчитать инвентарь |
| `scheduling.trip.cancelled` | scheduling-service | Заморозить инвентарь, вернуть все резервирования |
| `scheduling.trip.status_changed` | scheduling-service | Обновить статус инвентаря |

### События, которые сервис **публикует** (stream: `transora.inventory`)

| Subject | Триггер | Потребители |
|---------|---------|------------|
| `inventory.seat.reserved` | Успешное резервирование | — |
| `inventory.seat.sold` | Подтверждение продажи | notification-service |
| `inventory.seat.released` | Освобождение (TTL/отмена) | — |
| `inventory.seat.blocked` | Ручная блокировка | — |
| `inventory.transit_gate.opened` | Открытие транзита | notification-service, display-service |
| `inventory.transit_gate.closed` | Закрытие транзита | notification-service |
| `inventory.availability.changed` | Любое изменение доступности | display-service (табло) |
| `inventory.reaccommodation.required` | Замена ТС с конфликтом мест | notification-service, sales-service |

### Пример payload `inventory.transit_gate.opened`

```json
{
  "event_id": "evt-inv-001",
  "event_type": "inventory.transit_gate.opened",
  "occurred_at": "2025-07-15T08:58:00Z",
  "payload": {
    "gate_id": "gate-001",
    "trip_id": "trip-e5f6a7b8",
    "trip_number": "101",
    "station_id": "st-002",
    "station_name": "Канск (Автовокзал)",
    "available_seats": [40, 41, 42, 43, 44, 45],
    "available_count": 6,
    "opened_by": "dispatcher-002",
    "notes": "По данным водителя Иванова С.П."
  }
}
```

---

## 12. Зависимости и взаимодействие

```
                  ┌────────────────────────────┐
                  │      inventory-service      │
                  └──────────┬─────────────────┘
                             │
        ┌────────────────────┼──────────────────────┐
        │                    │                      │
   Потребляет            Публикует              Использует
   события из            события в              хранилища
   NATS                  NATS
        │                    │                      │
┌───────▼────────┐  ┌────────▼────────┐    ┌────────▼──────┐
│ scheduling-svc │  │notification-svc │    │  PostgreSQL   │
│ (trip events)  │  │ display-service │    │  (основная БД)│
└────────────────┘  │ sales-service   │    ├───────────────┤
                    └─────────────────┘    │     Redis     │
                                           │  (locks, TTL, │
                    REST API (входящие):   │   кеш)        │
                    ┌─────────────────┐    └───────────────┘
                    │ cashier-app     │
                    │ dispatcher-app  │
                    │ sales-service   │
                    └─────────────────┘
```

**Принципиально важно:** `inventory-service` не вызывает другие бизнес-сервисы синхронно. Все исходящие взаимодействия — только через события NATS. Это исключает циклические зависимости и каскадные отказы.

---

*Следующий документ: `transora-sales-service.md` — Сервис продаж и возвратов билетов*
