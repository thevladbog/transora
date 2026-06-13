# Transora — Scheduling Service
## Сервис планирования рейсов: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `scheduling-service`

---

## Содержание

1. [Назначение сервиса](#1-назначение-сервиса)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Бизнес-правила и ограничения](#3-бизнес-правила-и-ограничения)
4. [Модель данных (ERD)](#4-модель-данных-erd)
5. [Схемы таблиц БД](#5-схемы-таблиц-бд)
6. [Примеры данных](#6-примеры-данных)
7. [Жизненный цикл рейса](#7-жизненный-цикл-рейса)
8. [События (NATS JetStream)](#8-события-nats-jetstream)
9. [Зависимости и взаимодействие](#9-зависимости-и-взаимодействие)

---

## 1. Назначение сервиса

`scheduling-service` — центральный сервис Transora, отвечающий за:

- Управление маршрутами и остановками
- Управление расписаниями (постоянными, сезонными, исключениями)
- Создание и ведение конкретных рейсов (trip) на основе расписаний
- Назначение транспортных средств и водителей на рейсы
- Фиксацию фактических времён движения и статусов рейса
- Публикацию событий изменений для остальных сервисов системы

Сервис является **единственным источником правды** по всем данным о движении. Все остальные сервисы (инвентарь, табло, озвучивание) потребляют данные через события или API этого сервиса.

**Стек:** Kotlin + Spring Boot + PostgreSQL + NATS JetStream

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **Route** (Маршрут) | Именованный путь с упорядоченным списком остановок. Не привязан к датам. |
| **Stop** (Остановка) | Точка на маршруте. Может быть вокзалом в системе или внешней точкой. |
| **Schedule** (Расписание) | Шаблон повторяемости рейсов: по каким дням и в какое время они выполняются. |
| **ScheduleEntry** (Запись расписания) | Один рейс в рамках шаблона расписания с нормативными временами. |
| **Trip** (Рейс) | Конкретный рейс на конкретную дату. Создаётся на основе ScheduleEntry или вручную. |
| **TripStop** (Остановка рейса) | Конкретная остановка в конкретном рейсе с плановым и фактическим временем. |
| **Vehicle** (ТС) | Транспортное средство. Содержит модель, госномер, ссылку на схему мест. |
| **Driver** (Водитель) | Водитель. Назначается на рейс. |
| **Carrier** (Перевозчик) | Автобусный парк. Владелец маршрута или плательщик за обслуживание. |
| **ServiceStation** (Вокзал) | Вокзал в системе Transora. Обслуживает определённые остановки маршрутов. |

---

## 3. Бизнес-правила и ограничения

### 3.1 Маршруты

- **BR-SCH-001:** Маршрут должен содержать минимум 2 остановки.
- **BR-SCH-002:** Порядок остановок (`stop_order`) должен быть уникальным в рамках маршрута и начинаться с 1.
- **BR-SCH-003:** Нормативное время (`scheduled_duration_min`) между остановками должно быть > 0.
- **BR-SCH-004:** Маршрут нельзя удалить, если на него ссылается хотя бы одно активное расписание или будущий рейс. Допускается только деактивация (`is_active = false`).
- **BR-SCH-005:** Остановка может быть внешней (`is_external = true`) — в этом случае она не привязана к вокзалу системы и не имеет функций продажи или диспетчеризации.

### 3.2 Расписания

- **BR-SCH-010:** Тип расписания — одно из трёх значений: `PERMANENT`, `SEASONAL`, `EXCEPTION`.
- **BR-SCH-011:** Для типа `SEASONAL` обязательны `valid_from` и `valid_to`. `valid_to` должен быть позже `valid_from`.
- **BR-SCH-012:** Для типа `EXCEPTION` указывается ровно одна дата (`valid_from = valid_to`).
- **BR-SCH-013:** Для типа `PERMANENT` даты действия не указываются.
- **BR-SCH-014:** Приоритет при конфликте расписаний: `EXCEPTION` > `SEASONAL` > `PERMANENT`. То есть если на конкретную дату есть исключение — применяется оно.
- **BR-SCH-015:** Расписание нельзя деактивировать, если на него есть продажи в будущих рейсах.
- **BR-SCH-016:** Дни недели (`days_of_week`) для `PERMANENT` и `SEASONAL` задаются как массив числовых значений: `1` (пн) … `7` (вс).

### 3.3 Рейсы (Trip)

- **BR-SCH-020:** Рейс создаётся либо автоматически генератором (на основе расписания) либо вручную диспетчером/администратором.
- **BR-SCH-021:** Рейс нельзя создать на прошедшую дату.
- **BR-SCH-022:** Нельзя создать два рейса на одном маршруте с одним и тем же `ScheduleEntry` и одной и той же датой.
- **BR-SCH-023:** Назначение ТС на рейс обязательно до открытия продаж. Без назначенного ТС продажа невозможна (нет схемы мест).
- **BR-SCH-024:** Замена ТС на рейс допускается до момента фактического отправления. При замене:
  - Если схема мест новая и отличается от старой — инвентарь пересчитывается. Места, проданные сверх новой вместимости, помечаются флагом `requires_reaccommodation`.
  - Если схема мест идентична — пересчёт не требуется.
- **BR-SCH-025:** Рейс нельзя отменить (`CANCELLED`), если по нему есть активные билеты. Сначала необходим массовый возврат или перевод пассажиров.
- **BR-SCH-026:** Факт опоздания фиксируется как разница между `scheduled_departure` и `actual_departure` (или `estimated_departure`, если рейс ещё не отправился).
- **BR-SCH-027:** Рейс считается «проходящим» для вокзала, если первая и последняя остановка маршрута не совпадают с вокзалом. Такие рейсы требуют ручного открытия продаж диспетчером по факту прибытия.

### 3.4 Водители и ТС

- **BR-SCH-030:** Один водитель не может быть назначен на два рейса, у которых пересекаются временные интервалы (с допуском на пересадку 30 минут).
- **BR-SCH-031:** Одно ТС не может быть назначено на два рейса с пересекающимися интервалами.
- **BR-SCH-032:** Данные ТС (модель, схема мест, госномер) поступают от перевозчика. Диспетчер может редактировать только оперативные поля: фактическое ТС на рейс.
- **BR-SCH-033:** Водитель хранится в системе с минимальными данными: ФИО, номер ВУ. Контроль трудовых норм — вне зоны ответственности Transora.

### 3.5 Генерация рейсов

- **BR-SCH-040:** Автоматическая генерация рейсов запускается ежедневно (планировщик) для формирования рейсов на горизонте `N` дней вперёд (настраивается, по умолчанию 60 дней).
- **BR-SCH-041:** Перед созданием рейса генератор проверяет приоритет расписаний (правило BR-SCH-014) и не создаёт дубликатов.
- **BR-SCH-042:** Рейсы, сгенерированные автоматически, имеют флаг `auto_generated = true`. Вручную созданные — `false`.

---

## 4. Модель данных (ERD)

```
┌──────────────┐         ┌─────────────────────┐
│   carrier    │         │       route         │
│──────────────│         │─────────────────────│
│ id (PK)      │◄────────│ carrier_id (FK)     │
│ name         │    1:N  │ id (PK)             │
│ legal_name   │         │ name                │
│ inn          │         │ code                │
│ contract_type│         │ is_active           │
│ is_active    │         │ created_at          │
└──────────────┘         └──────────┬──────────┘
                                    │ 1:N
                         ┌──────────▼──────────┐       ┌──────────────────┐
                         │     route_stop      │       │  service_station │
                         │─────────────────────│       │──────────────────│
                         │ id (PK)             │  N:1  │ id (PK)          │
                         │ route_id (FK)       │──────►│ name             │
                         │ stop_order          │       │ city             │
                         │ stop_name           │       │ timezone         │
                         │ station_id (FK, NULL│       │ is_active        │
                         │ is_external         │       └──────────────────┘
                         │ scheduled_duration  │
                         └─────────────────────┘
                                    ▲
                                    │
┌──────────────────┐      ┌─────────┴──────────┐
│     schedule     │      │   schedule_entry   │
│──────────────────│      │────────────────────│
│ id (PK)          │ 1:N  │ id (PK)            │
│ route_id (FK)    │◄─────│ schedule_id (FK)   │
│ name             │      │ departure_time     │
│ schedule_type    │      │ days_of_week[]     │
│ valid_from       │      │ trip_number        │
│ valid_to         │      │ is_active          │
│ is_active        │      └─────────┬──────────┘
│ created_by       │                │ 1:N
└──────────────────┘                │
                         ┌──────────▼──────────┐
┌──────────────┐         │        trip         │       ┌──────────────┐
│   vehicle    │    N:1  │─────────────────────│  N:1  │    driver    │
│──────────────│◄────────│ id (PK)             │──────►│──────────────│
│ id (PK)      │         │ schedule_entry_id   │       │ id (PK)      │
│ carrier_id   │         │ route_id (FK)       │       │ carrier_id   │
│ model        │         │ trip_date           │       │ full_name    │
│ plate_number │         │ trip_number         │       │ license_no   │
│ seat_layout_id│        │ vehicle_id (FK,NULL)│       │ is_active    │
│ total_seats  │         │ driver_id (FK, NULL)│       └──────────────┘
│ is_active    │         │ status              │
└──────────────┘         │ auto_generated      │
                         │ created_at          │
┌──────────────┐         │ updated_at          │
│ seat_layout  │         └──────────┬──────────┘
│──────────────│                    │ 1:N
│ id (PK)      │         ┌──────────▼──────────┐
│ name         │         │      trip_stop      │
│ total_seats  │         │─────────────────────│
│ layout_json  │         │ id (PK)             │
└──────────────┘         │ trip_id (FK)        │
                         │ route_stop_id (FK)  │
                         │ stop_order          │
                         │ stop_name           │
                         │ station_id (FK,NULL)│
                         │ scheduled_arrival   │
                         │ scheduled_departure │
                         │ estimated_arrival   │
                         │ estimated_departure │
                         │ actual_arrival      │
                         │ actual_departure    │
                         │ stop_status         │
                         └─────────────────────┘
```

---

## 5. Схемы таблиц БД

Все таблицы находятся в схеме `scheduling`. СУБД: PostgreSQL 15+.

---

### 5.1 `carrier` — Перевозчики

```sql
CREATE TABLE scheduling.carrier (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(255) NOT NULL,              -- Краткое название
    legal_name    VARCHAR(500) NOT NULL,              -- Юридическое название
    inn           VARCHAR(12)  NOT NULL UNIQUE,       -- ИНН
    contract_type VARCHAR(20)  NOT NULL               -- ROUTE_RENT | SERVICE_FEE
                  CHECK (contract_type IN ('ROUTE_RENT', 'SERVICE_FEE')),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE scheduling.carrier IS 'Перевозчики (автобусные парки)';
COMMENT ON COLUMN scheduling.carrier.contract_type IS
    'ROUTE_RENT — парк арендует маршрут у вокзала;
     SERVICE_FEE — вокзал оказывает услуги на маршруте парка';
```

---

### 5.2 `service_station` — Вокзалы системы

```sql
CREATE TABLE scheduling.service_station (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    city       VARCHAR(255) NOT NULL,
    timezone   VARCHAR(64)  NOT NULL DEFAULT 'Europe/Moscow',  -- IANA timezone
    address    TEXT,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE scheduling.service_station IS 'Вокзалы, подключённые к системе Transora';
```

---

### 5.3 `route` — Маршруты

```sql
CREATE TABLE scheduling.route (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    carrier_id  UUID NOT NULL REFERENCES scheduling.carrier(id),
    name        VARCHAR(500) NOT NULL,   -- Например: "Красноярск — Новосибирск"
    code        VARCHAR(50) UNIQUE,      -- Внутренний код маршрута
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_route_carrier ON scheduling.route(carrier_id);
CREATE INDEX idx_route_active  ON scheduling.route(is_active);

COMMENT ON TABLE scheduling.route IS 'Маршруты движения';
```

---

### 5.4 `route_stop` — Остановки маршрута

```sql
CREATE TABLE scheduling.route_stop (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id                 UUID NOT NULL REFERENCES scheduling.route(id),
    stop_order               SMALLINT NOT NULL,          -- Порядковый номер: 1, 2, 3...
    stop_name                VARCHAR(255) NOT NULL,      -- Отображаемое название
    station_id               UUID REFERENCES scheduling.service_station(id), -- NULL для внешних
    is_external              BOOLEAN NOT NULL DEFAULT FALSE,
    -- Нормативное время от предыдущей остановки (мин), NULL для первой остановки
    scheduled_duration_min   INTEGER CHECK (scheduled_duration_min > 0),
    -- Нормативное время стоянки на остановке (мин)
    dwell_time_min           INTEGER NOT NULL DEFAULT 5 CHECK (dwell_time_min >= 0),

    CONSTRAINT uq_route_stop_order UNIQUE (route_id, stop_order),
    CONSTRAINT chk_external_no_station CHECK (
        NOT (is_external = FALSE AND station_id IS NULL)
    )
);

CREATE INDEX idx_route_stop_route ON scheduling.route_stop(route_id);

COMMENT ON COLUMN scheduling.route_stop.scheduled_duration_min IS
    'Нормативное время в пути от предыдущей остановки. NULL для первой остановки маршрута.';
```

---

### 5.5 `seat_layout` — Схемы мест ТС

```sql
CREATE TABLE scheduling.seat_layout (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,    -- Например: "ЛиАЗ 52292 — 44 места"
    total_seats  SMALLINT NOT NULL CHECK (total_seats > 0),
    -- JSON-схема раскладки мест для визуального отображения в кассе
    -- Формат описан в отдельной спецификации seat-layout-schema.md
    layout_json  JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN scheduling.seat_layout.layout_json IS
    'Визуальная схема мест. Содержит массив рядов, каждый ряд — массив мест
     с номером, типом (SEAT/AISLE/DRIVER) и дополнительными атрибутами.';
```

**Пример `layout_json`:**
```json
{
  "rows": [
    {
      "row_number": 1,
      "seats": [
        { "seat_number": 1, "type": "SEAT", "side": "LEFT" },
        { "seat_number": null, "type": "AISLE" },
        { "seat_number": 2, "type": "SEAT", "side": "RIGHT" },
        { "seat_number": 3, "type": "SEAT", "side": "RIGHT" }
      ]
    },
    {
      "row_number": 2,
      "seats": [
        { "seat_number": 4, "type": "SEAT", "side": "LEFT" },
        { "seat_number": null, "type": "AISLE" },
        { "seat_number": 5, "type": "SEAT", "side": "RIGHT" },
        { "seat_number": 6, "type": "SEAT", "side": "RIGHT" }
      ]
    }
  ]
}
```

---

### 5.6 `vehicle` — Транспортные средства

```sql
CREATE TABLE scheduling.vehicle (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    carrier_id      UUID NOT NULL REFERENCES scheduling.carrier(id),
    model           VARCHAR(255) NOT NULL,      -- Марка и модель: "ЛиАЗ 52292"
    plate_number    VARCHAR(20)  NOT NULL UNIQUE,
    seat_layout_id  UUID NOT NULL REFERENCES scheduling.seat_layout(id),
    total_seats     SMALLINT NOT NULL,
    year            SMALLINT,
    notes           TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vehicle_carrier ON scheduling.vehicle(carrier_id);

COMMENT ON TABLE scheduling.vehicle IS
    'Транспортные средства. Данные поступают от перевозчика.';
```

---

### 5.7 `driver` — Водители

```sql
CREATE TABLE scheduling.driver (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    carrier_id   UUID NOT NULL REFERENCES scheduling.carrier(id),
    full_name    VARCHAR(255) NOT NULL,
    license_no   VARCHAR(50)  NOT NULL,
    phone        VARCHAR(20),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_driver_carrier ON scheduling.driver(carrier_id);
```

---

### 5.8 `schedule` — Шаблоны расписаний

```sql
CREATE TYPE scheduling.schedule_type AS ENUM ('PERMANENT', 'SEASONAL', 'EXCEPTION');

CREATE TABLE scheduling.schedule (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id        UUID NOT NULL REFERENCES scheduling.route(id),
    name            VARCHAR(255) NOT NULL,
    schedule_type   scheduling.schedule_type NOT NULL,
    valid_from      DATE,   -- NULL для PERMANENT
    valid_to        DATE,   -- NULL для PERMANENT
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      UUID NOT NULL,    -- Ссылка на пользователя (IAM)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Для SEASONAL и EXCEPTION даты обязательны
    CONSTRAINT chk_seasonal_dates CHECK (
        schedule_type = 'PERMANENT'
        OR (valid_from IS NOT NULL AND valid_to IS NOT NULL)
    ),
    -- Для EXCEPTION valid_from = valid_to
    CONSTRAINT chk_exception_single_date CHECK (
        schedule_type != 'EXCEPTION'
        OR valid_from = valid_to
    ),
    -- valid_to >= valid_from
    CONSTRAINT chk_date_order CHECK (
        valid_from IS NULL OR valid_to IS NULL
        OR valid_to >= valid_from
    )
);

CREATE INDEX idx_schedule_route    ON scheduling.schedule(route_id);
CREATE INDEX idx_schedule_type     ON scheduling.schedule(schedule_type);
CREATE INDEX idx_schedule_active   ON scheduling.schedule(is_active);
```

---

### 5.9 `schedule_entry` — Записи расписания (конкретные рейсы по шаблону)

```sql
CREATE TABLE scheduling.schedule_entry (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id     UUID NOT NULL REFERENCES scheduling.schedule(id),
    trip_number     VARCHAR(20) NOT NULL,    -- Номер рейса: "123", "7А"
    departure_time  TIME NOT NULL,           -- Нормативное время отправления с 1-й остановки
    days_of_week    SMALLINT[] NOT NULL,     -- [1,2,3,4,5] для буднев; [6,7] для выходных
    -- Предпочтительное ТС (может быть NULL, назначается позже)
    default_vehicle_id UUID REFERENCES scheduling.vehicle(id),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_schedule_entry_number UNIQUE (schedule_id, trip_number),
    CONSTRAINT chk_days_of_week CHECK (
        array_length(days_of_week, 1) > 0
        AND days_of_week <@ ARRAY[1,2,3,4,5,6,7]::SMALLINT[]
    )
);

CREATE INDEX idx_entry_schedule ON scheduling.schedule_entry(schedule_id);
```

---

### 5.10 `trip` — Рейсы (конкретная дата)

```sql
CREATE TYPE scheduling.trip_status AS ENUM (
    'PLANNED',      -- Запланирован, продажи ещё не открыты
    'OPEN',         -- Продажи открыты
    'DEPARTED',     -- Отправился с начальной точки
    'IN_TRANSIT',   -- В пути
    'ARRIVED',      -- Прибыл на конечную точку
    'COMPLETED',    -- Завершён, документы сформированы
    'CANCELLED'     -- Отменён
);

CREATE TABLE scheduling.trip (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_entry_id     UUID REFERENCES scheduling.schedule_entry(id),  -- NULL для ручных
    route_id              UUID NOT NULL REFERENCES scheduling.route(id),
    trip_date             DATE NOT NULL,
    trip_number           VARCHAR(20) NOT NULL,
    vehicle_id            UUID REFERENCES scheduling.vehicle(id),
    driver_id             UUID REFERENCES scheduling.driver(id),
    status                scheduling.trip_status NOT NULL DEFAULT 'PLANNED',
    auto_generated        BOOLEAN NOT NULL DEFAULT FALSE,
    delay_minutes         INTEGER NOT NULL DEFAULT 0,
    cancellation_reason   TEXT,
    notes                 TEXT,
    created_by            UUID,    -- NULL если auto_generated
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_trip_entry_date UNIQUE (schedule_entry_id, trip_date),
    CONSTRAINT chk_trip_date_not_past CHECK (
        trip_date >= CURRENT_DATE - INTERVAL '1 day'  -- допуск на часовые пояса
    )
);

CREATE INDEX idx_trip_date         ON scheduling.trip(trip_date);
CREATE INDEX idx_trip_route        ON scheduling.trip(route_id);
CREATE INDEX idx_trip_status       ON scheduling.trip(status);
CREATE INDEX idx_trip_vehicle      ON scheduling.trip(vehicle_id);
CREATE INDEX idx_trip_driver       ON scheduling.trip(driver_id);
CREATE INDEX idx_trip_date_status  ON scheduling.trip(trip_date, status);

COMMENT ON COLUMN scheduling.trip.delay_minutes IS
    'Текущая задержка в минутах. Обновляется диспетчером. Положительное = опоздание.';
```

---

### 5.11 `trip_stop` — Остановки конкретного рейса

```sql
CREATE TYPE scheduling.stop_status AS ENUM (
    'UPCOMING',     -- Остановка ещё не достигнута
    'ARRIVED',      -- Прибыл на остановку
    'DEPARTED',     -- Отправился с остановки
    'SKIPPED'       -- Пропущена (например, при изменении маршрута)
);

CREATE TABLE scheduling.trip_stop (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id              UUID NOT NULL REFERENCES scheduling.trip(id) ON DELETE CASCADE,
    route_stop_id        UUID NOT NULL REFERENCES scheduling.route_stop(id),
    stop_order           SMALLINT NOT NULL,
    stop_name            VARCHAR(255) NOT NULL,     -- Денормализовано для истории
    station_id           UUID REFERENCES scheduling.service_station(id),
    is_external          BOOLEAN NOT NULL DEFAULT FALSE,

    -- Плановые времена (рассчитываются при создании рейса)
    scheduled_arrival    TIMESTAMPTZ,               -- NULL для первой остановки
    scheduled_departure  TIMESTAMPTZ NOT NULL,

    -- Ожидаемые времена (обновляются при задержках)
    estimated_arrival    TIMESTAMPTZ,
    estimated_departure  TIMESTAMPTZ,

    -- Фактические времена (заполняются диспетчером)
    actual_arrival       TIMESTAMPTZ,
    actual_departure     TIMESTAMPTZ,

    stop_status          scheduling.stop_status NOT NULL DEFAULT 'UPCOMING',
    updated_by           UUID,    -- Кто последний обновил
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_trip_stop_order UNIQUE (trip_id, stop_order)
);

CREATE INDEX idx_trip_stop_trip    ON scheduling.trip_stop(trip_id);
CREATE INDEX idx_trip_stop_station ON scheduling.trip_stop(station_id);
```

---

### 5.12 `trip_audit_log` — Журнал изменений рейсов

```sql
CREATE TABLE scheduling.trip_audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id       UUID NOT NULL REFERENCES scheduling.trip(id),
    event_type    VARCHAR(50) NOT NULL,   -- STATUS_CHANGED, DELAY_UPDATED, VEHICLE_REPLACED, etc.
    old_value     JSONB,
    new_value     JSONB,
    changed_by    UUID NOT NULL,          -- ID пользователя
    station_id    UUID,                   -- С какого вокзала внесено изменение
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_trip    ON scheduling.trip_audit_log(trip_id);
CREATE INDEX idx_audit_created ON scheduling.trip_audit_log(created_at DESC);

COMMENT ON TABLE scheduling.trip_audit_log IS
    'Неизменяемый журнал всех изменений рейсов. Только INSERT, без UPDATE/DELETE.';
```

---

## 6. Примеры данных

### 6.1 Перевозчик

```json
{
  "id": "a1b2c3d4-0000-0000-0000-000000000001",
  "name": "АвтоЭкспресс",
  "legal_name": "ООО «АвтоЭкспресс Сибирь»",
  "inn": "2460123456",
  "contract_type": "SERVICE_FEE",
  "is_active": true
}
```

### 6.2 Маршрут с остановками

```json
{
  "id": "b2c3d4e5-0000-0000-0000-000000000001",
  "carrier_id": "a1b2c3d4-...",
  "name": "Красноярск — Канск",
  "code": "KRS-KSK-001",
  "stops": [
    {
      "stop_order": 1,
      "stop_name": "Красноярск (Центральный)",
      "station_id": "st-001",
      "is_external": false,
      "scheduled_duration_min": null,
      "dwell_time_min": 10
    },
    {
      "stop_order": 2,
      "stop_name": "Зеленогорск",
      "station_id": null,
      "is_external": true,
      "scheduled_duration_min": 85,
      "dwell_time_min": 5
    },
    {
      "stop_order": 3,
      "stop_name": "Канск (Автовокзал)",
      "station_id": "st-002",
      "is_external": false,
      "scheduled_duration_min": 60,
      "dwell_time_min": 15
    }
  ]
}
```

### 6.3 Расписание (постоянное, по будням)

```json
{
  "id": "c3d4e5f6-0000-0000-0000-000000000001",
  "route_id": "b2c3d4e5-...",
  "name": "Основное расписание (будни)",
  "schedule_type": "PERMANENT",
  "valid_from": null,
  "valid_to": null,
  "is_active": true,
  "entries": [
    {
      "trip_number": "101",
      "departure_time": "07:30",
      "days_of_week": [1, 2, 3, 4, 5]
    },
    {
      "trip_number": "103",
      "departure_time": "14:00",
      "days_of_week": [1, 2, 3, 4, 5]
    }
  ]
}
```

### 6.4 Сезонное расписание (летнее, ежедневно)

```json
{
  "id": "d4e5f6a7-0000-0000-0000-000000000001",
  "route_id": "b2c3d4e5-...",
  "name": "Летнее расписание 2025",
  "schedule_type": "SEASONAL",
  "valid_from": "2025-06-01",
  "valid_to": "2025-08-31",
  "is_active": true,
  "entries": [
    {
      "trip_number": "105",
      "departure_time": "10:00",
      "days_of_week": [1, 2, 3, 4, 5, 6, 7]
    }
  ]
}
```

### 6.5 Конкретный рейс

```json
{
  "id": "e5f6a7b8-0000-0000-0000-000000000001",
  "schedule_entry_id": "...",
  "route_id": "b2c3d4e5-...",
  "trip_date": "2025-07-15",
  "trip_number": "101",
  "vehicle": {
    "id": "veh-001",
    "model": "ЛиАЗ 52292",
    "plate_number": "А123БВ124"
  },
  "driver": {
    "id": "drv-001",
    "full_name": "Иванов Сергей Петрович"
  },
  "status": "OPEN",
  "delay_minutes": 0,
  "auto_generated": true,
  "stops": [
    {
      "stop_order": 1,
      "stop_name": "Красноярск (Центральный)",
      "scheduled_departure": "2025-07-15T07:30:00+07:00",
      "estimated_departure": "2025-07-15T07:30:00+07:00",
      "actual_departure": null,
      "stop_status": "UPCOMING"
    },
    {
      "stop_order": 2,
      "stop_name": "Зеленогорск",
      "scheduled_arrival": "2025-07-15T08:55:00+07:00",
      "scheduled_departure": "2025-07-15T09:00:00+07:00",
      "actual_arrival": null,
      "actual_departure": null,
      "stop_status": "UPCOMING"
    },
    {
      "stop_order": 3,
      "stop_name": "Канск (Автовокзал)",
      "scheduled_arrival": "2025-07-15T10:00:00+07:00",
      "scheduled_departure": "2025-07-15T10:15:00+07:00",
      "actual_arrival": null,
      "stop_status": "UPCOMING"
    }
  ]
}
```

### 6.6 Пример записи аудит-лога (задержка рейса)

```json
{
  "id": "log-001",
  "trip_id": "e5f6a7b8-...",
  "event_type": "DELAY_UPDATED",
  "old_value": { "delay_minutes": 0 },
  "new_value":  { "delay_minutes": 25, "reason": "Пробки на выезде из Красноярска" },
  "changed_by": "user-dispatcher-001",
  "station_id": "st-001",
  "created_at": "2025-07-15T07:28:00+07:00"
}
```

---

## 7. Жизненный цикл рейса

```
                    ┌─────────────────────────────────────────────────────┐
                    │  Генератор (авто) или диспетчер/admin (вручную)      │
                    └───────────────────────┬─────────────────────────────┘
                                            │ Создание рейса
                                            ▼
                                       ┌─────────┐
                                       │ PLANNED │ ◄── ТС не назначено или
                                       └────┬────┘     продажи не открыты
                                            │
                              Назначено ТС + открыты продажи
                                            │
                                            ▼
                                        ┌──────┐
                                        │ OPEN │ ◄── Продажи активны,
                                        └──┬───┘     инвентарь доступен
                                           │
                            Диспетчер фиксирует фактическое
                            отправление с начальной точки
                                           │
                                           ▼
                                      ┌──────────┐
                                      │ DEPARTED │
                                      └────┬─────┘
                                           │
                            Рейс движется по маршруту,
                            диспетчеры фиксируют прибытие/
                            отправление по остановкам
                                           │
                                           ▼
                                     ┌───────────┐
                                     │ IN_TRANSIT│
                                     └─────┬─────┘
                                           │
                            Прибытие на конечную остановку
                                           │
                                           ▼
                                      ┌─────────┐
                                      │ ARRIVED │
                                      └────┬────┘
                                           │
                            Формирование документов,
                            закрытие рейса
                                           │
                                           ▼
                                     ┌───────────┐
                                     │ COMPLETED │
                                     └───────────┘

            В любом статусе до DEPARTED возможен переход:
                                           │
                                           ▼
                                     ┌───────────┐
                                     │ CANCELLED │ ◄── Только при отсутствии
                                     └───────────┘     активных билетов
```

---

## 8. События (NATS JetStream)

Сервис публикует следующие события. Все события публикуются в stream `transora.scheduling`.

| Subject | Триггер | Потребители |
|---------|---------|-------------|
| `scheduling.trip.created` | Создание рейса | inventory-service |
| `scheduling.trip.status_changed` | Изменение статуса рейса | inventory-service, notification-service, display-service |
| `scheduling.trip.delay_updated` | Изменение задержки | notification-service, display-service |
| `scheduling.trip.vehicle_changed` | Замена ТС | inventory-service |
| `scheduling.trip.cancelled` | Отмена рейса | inventory-service, notification-service, sales-service |
| `scheduling.trip.departed` | Фактическое отправление | notification-service, display-service |
| `scheduling.trip.arrived` | Фактическое прибытие | notification-service, display-service |
| `scheduling.schedule.updated` | Изменение шаблона расписания | scheduling-service (генератор) |

### Пример payload события `scheduling.trip.delay_updated`

```json
{
  "event_id": "evt-001",
  "event_type": "scheduling.trip.delay_updated",
  "occurred_at": "2025-07-15T07:28:00Z",
  "source_station_id": "st-001",
  "payload": {
    "trip_id": "e5f6a7b8-...",
    "trip_number": "101",
    "trip_date": "2025-07-15",
    "route_id": "b2c3d4e5-...",
    "delay_minutes": 25,
    "affected_stations": ["st-001", "st-002"],
    "updated_stops": [
      {
        "stop_order": 1,
        "stop_name": "Красноярск (Центральный)",
        "estimated_departure": "2025-07-15T07:55:00+07:00"
      },
      {
        "stop_order": 2,
        "stop_name": "Зеленогорск",
        "estimated_arrival": "2025-07-15T09:20:00+07:00",
        "estimated_departure": "2025-07-15T09:25:00+07:00"
      },
      {
        "stop_order": 3,
        "stop_name": "Канск (Автовокзал)",
        "estimated_arrival": "2025-07-15T10:25:00+07:00"
      }
    ]
  }
}
```

---

## 9. Зависимости и взаимодействие

```
                    ┌──────────────────────────────┐
                    │      scheduling-service       │
                    └──────────────┬───────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
   Публикует события         Отвечает на              Читает из
   в NATS JetStream          REST-запросы             PostgreSQL
              │                    │
   ┌──────────▼──────┐    ┌────────▼──────────────────────────┐
   │ inventory-svc   │    │  Потребители REST API:            │
   │ notification-svc│    │  · Кассовый интерфейс (поиск      │
   │ display-svc     │    │    рейсов, расписание)            │
   │ sales-svc       │    │  · Рабочее место диспетчера       │
   └─────────────────┘    │  · Табло (первичная загрузка)     │
                          │  · ТСД-приложение (список рейсов) │
                          └───────────────────────────────────┘
```

**Зависимости scheduling-service:**
- `IAM Service` — для получения данных пользователя при аудите (`changed_by`)
- `PostgreSQL` — основное хранилище
- `NATS JetStream` — публикация событий
- Никаких синхронных вызовов к другим бизнес-сервисам — зависимости только входящие

---

*Следующий документ: `transora-inventory-service.md` — Сервис управления инвентарём мест*
