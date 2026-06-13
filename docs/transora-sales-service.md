# Transora — Sales Service
## Сервис продаж и возвратов билетов: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `sales-service`

---

## Содержание

1. [Назначение сервиса](#1-назначение-сервиса)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Бизнес-правила и ограничения](#3-бизнес-правила-и-ограничения)
4. [Модель данных (ERD)](#4-модель-данных-erd)
5. [Схемы таблиц БД](#5-схемы-таблиц-бд)
6. [Процессы продажи и возврата](#6-процессы-продажи-и-возврата)
7. [Система тарифов и штрафов](#7-система-тарифов-и-штрафов)
8. [Фискализация](#8-фискализация)
9. [Управление кассовой сменой](#9-управление-кассовой-сменой)
10. [Примеры данных](#10-примеры-данных)
11. [События (NATS JetStream)](#11-события-nats-jetstream)
12. [Зависимости и взаимодействие](#12-зависимости-и-взаимодействие)

---

## 1. Назначение сервиса

`sales-service` отвечает за весь коммерческий цикл операций с билетами: продажу, возврат, а также управление кассовыми сменами и тарифами.

Основные обязанности:

- Оформление продажи билета с привязкой к зарезервированному месту
- Расчёт стоимости по тарифной сетке
- Обработка возвратов с расчётом удержаний согласно законодательству
- Управление кассовыми сменами (открытие, закрытие, Z-отчёт)
- Координация фискализации через Hardware Agent
- Формирование чеков и передача данных в ОФД
- Хранение полной истории транзакций для отчётности

Сервис **не управляет местами напрямую** — он координирует операции с `inventory-service` через подтверждение и освобождение резервирований. Это принципиально важно для соблюдения разделения ответственности.

**Стек:** Kotlin + Spring Boot + PostgreSQL + NATS JetStream

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **Ticket** (Билет) | Документ, подтверждающий право пассажира на проезд на конкретном рейсе, месте и сегменте маршрута |
| **Order** (Заказ) | Одна транзакция продажи, может включать несколько билетов (групповая продажа) |
| **Payment** (Платёж) | Факт оплаты заказа. Тип: наличные или банковская карта |
| **Refund** (Возврат) | Операция возврата билета с расчётом удержания |
| **Tariff** (Тариф) | Правило расчёта стоимости билета для маршрута или сегмента |
| **RefundPolicy** (Политика возврата) | Набор правил удержания штрафов в зависимости от срока до отправления |
| **CashierShift** (Кассовая смена) | Рабочая смена кассира на конкретной кассе с фиксацией выручки |
| **FiscalReceipt** (Фискальный чек) | Данные фискализированного чека, полученные от ККТ |

---

## 3. Бизнес-правила и ограничения

### 3.1 Продажа билетов

- **BR-SAL-001:** Продажа возможна только при наличии активного резервирования места в `inventory-service`. Без резервирования — отказ.
- **BR-SAL-002:** Резервирование должно быть активным на момент подтверждения оплаты (не истёкшим). Если TTL истёк — продажа отклоняется, кассиру предлагается повторить резервирование.
- **BR-SAL-003:** Продажа выполняется в рамках открытой кассовой смены. Без открытой смены продажа невозможна.
- **BR-SAL-004:** Один заказ (`Order`) может содержать от 1 до 10 билетов (ограничение на групповую продажу).
- **BR-SAL-005:** Данные пассажира обязательны для каждого билета: ФИО, тип документа, номер документа.
- **BR-SAL-006:** Тип документа — одно из допустимых значений: `PASSPORT_RF`, `PASSPORT_FOREIGN`, `BIRTH_CERTIFICATE`, `MILITARY_ID`, `OTHER`.
- **BR-SAL-007:** Стоимость билета фиксируется в момент продажи и не может быть изменена постфактум.
- **BR-SAL-008:** После успешной фискализации билет переходит в статус `ISSUED` и готов к печати. Если фискализация не удалась — транзакция откатывается, резервирование сохраняется активным.
- **BR-SAL-009:** Номер билета генерируется системой по маске: `{год}{код_вокзала}{порядковый_номер}`. Порядковый номер — сквозной в рамках вокзала за сутки.

### 3.2 Возврат билетов

- **BR-SAL-020:** Возврат возможен только для билетов в статусе `ISSUED`.
- **BR-SAL-021:** Возврат невозможен после фактического отправления рейса с остановки посадки пассажира (`actual_departure` заполнен).
- **BR-SAL-022:** Сумма удержания рассчитывается автоматически на основе активной `RefundPolicy` и времени до плановой отправки рейса на момент обращения.
- **BR-SAL-023:** Возврат выполняется тем же способом оплаты, которым был куплен билет. Наличные — выдаются из кассы. Карта — возврат через банковский терминал по номеру исходной транзакции.
- **BR-SAL-024:** При возврате обязательно формируется фискальный чек возврата через ККТ.
- **BR-SAL-025:** После успешного возврата сервис публикует событие для `inventory-service`, который освобождает место.
- **BR-SAL-026:** Частичный возврат (возврат одного билета из группового заказа) допускается.

### 3.3 Тарифы

- **BR-SAL-030:** Для каждого маршрута или пары остановок задаётся базовый тариф.
- **BR-SAL-031:** Тариф может быть задан на уровне: маршрут целиком, конкретный сегмент (пара остановок).
- **BR-SAL-032:** Приоритет тарифов: сегментный > маршрутный.
- **BR-SAL-033:** Тариф имеет дату начала и окончания действия. Применяется тариф, актуальный на дату рейса.
- **BR-SAL-034:** Изменение тарифа не влияет на уже оформленные билеты.
- **BR-SAL-035:** Тарифы настраиваются администратором системы. Диспетчер не может изменять тарифы.

### 3.4 Политика возврата

- **BR-SAL-040:** Политика возврата настраивается на уровне системы или перевозчика.
- **BR-SAL-041:** Базовая политика согласно ПП РФ №112:
  - Более 24 часов до отправления — удержание 0%
  - От 2 до 24 часов — удержание 10%
  - Менее 2 часов — удержание 25%
  - После отправления — возврат не производится
- **BR-SAL-042:** Дополнительно к проценту удержания может быть задан фиксированный сервисный сбор за оформление возврата.
- **BR-SAL-043:** Одновременно может быть активна только одна политика возврата для перевозчика. Смена политики не затрагивает билеты, купленные до смены.

### 3.5 Кассовая смена

- **BR-SAL-050:** Одновременно на одной кассе может быть только одна открытая смена.
- **BR-SAL-051:** Смена привязана к кассиру и физическому рабочему месту (`pos_id`).
- **BR-SAL-052:** При закрытии смены автоматически формируется Z-отчёт через ККТ.
- **BR-SAL-053:** Все незакрытые резервирования сессии кассира освобождаются при закрытии смены.
- **BR-SAL-054:** Смена не может быть закрыта, если есть незавершённые транзакции (оплата в процессе).

---

## 4. Модель данных (ERD)

```
┌──────────────────┐        ┌──────────────────────┐
│  cashier_shift   │        │        order         │
│──────────────────│        │──────────────────────│
│ id (PK)          │   1:N  │ id (PK)              │
│ cashier_id       │◄───────│ shift_id (FK)        │
│ pos_id           │        │ station_id           │
│ station_id       │        │ status               │
│ status           │        │ total_amount         │
│ opened_at        │        │ created_at           │
│ closed_at        │        └──────────┬───────────┘
│ opening_balance  │                   │ 1:N
│ closing_balance  │        ┌──────────▼───────────┐      ┌──────────────────┐
│ fiscal_shift_no  │        │       ticket         │      │     payment      │
└──────────────────┘        │──────────────────────│      │──────────────────│
                            │ id (PK)              │      │ id (PK)          │
                            │ order_id (FK)        │ 1:1  │ order_id (FK)    │
                            │ ticket_number        │      │ payment_type     │
                            │ trip_id              │      │ amount           │
                            │ seat_number          │      │ card_transaction │
                            │ reservation_id       │      │ terminal_receipt │
                            │ from_stop_order      │      │ status           │
                            │ to_stop_order        │      │ processed_at     │
                            │ passenger_name       │      └──────────────────┘
                            │ doc_type             │
                            │ doc_number           │      ┌──────────────────┐
                            │ route_id             │      │  fiscal_receipt  │
                            │ from_stop_name       │      │──────────────────│
                            │ to_stop_name         │      │ id (PK)          │
                            │ tariff_id (FK)       │ 1:1  │ ticket_id (FK)   │
                            │ base_price           │      │ receipt_type     │
                            │ status               │──────│ fiscal_sign      │
                            │ issued_at            │      │ fiscal_doc_no    │
                            │ fiscal_receipt_id    │      │ ofd_status       │
                            └──────────┬───────────┘      │ kkm_serial       │
                                       │                   │ created_at       │
                                       │ 0:1               └──────────────────┘
                            ┌──────────▼───────────┐
                            │       refund         │
                            │──────────────────────│
                            │ id (PK)              │
                            │ ticket_id (FK)       │
                            │ policy_id (FK)       │
                            │ original_price       │
                            │ penalty_percent      │
                            │ penalty_amount       │
                            │ service_fee          │
                            │ refund_amount        │
                            │ refund_type          │
                            │ processed_by         │
                            │ processed_at         │
                            │ fiscal_receipt_id    │
                            └──────────────────────┘

┌──────────────────────┐    ┌──────────────────────────────┐
│       tariff         │    │        refund_policy         │
│──────────────────────│    │──────────────────────────────│
│ id (PK)              │    │ id (PK)                      │
│ carrier_id           │    │ carrier_id                   │
│ route_id             │    │ name                         │
│ from_stop_order(NULL)│    │ is_active                    │
│ to_stop_order (NULL) │    │ service_fee_fixed            │
│ price                │    │ valid_from                   │
│ currency             │    └──────────────┬───────────────┘
│ valid_from           │                   │ 1:N
│ valid_to             │    ┌──────────────▼───────────────┐
│ is_active            │    │     refund_policy_tier       │
└──────────────────────┘    │──────────────────────────────│
                            │ id (PK)                      │
                            │ policy_id (FK)               │
                            │ hours_before_min (NULL)      │
                            │ hours_before_max (NULL)      │
                            │ penalty_percent              │
                            │ refund_allowed               │
                            └──────────────────────────────┘
```

---

## 5. Схемы таблиц БД

Все таблицы в схеме `sales`. СУБД: PostgreSQL 15+.

---

### 5.1 `cashier_shift` — Кассовые смены

```sql
CREATE TYPE sales.shift_status AS ENUM (
    'OPEN',     -- Смена открыта
    'CLOSED'    -- Смена закрыта
);

CREATE TABLE sales.cashier_shift (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cashier_id        UUID NOT NULL,               -- ID кассира (из IAM)
    pos_id            VARCHAR(64) NOT NULL,         -- ID рабочего места / кассы
    station_id        UUID NOT NULL,
    status            sales.shift_status NOT NULL DEFAULT 'OPEN',
    opened_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at         TIMESTAMPTZ,
    opening_balance   NUMERIC(12,2) NOT NULL DEFAULT 0, -- Остаток наличных на открытие
    closing_balance   NUMERIC(12,2),                    -- Фактический остаток на закрытие
    cash_sales_total  NUMERIC(12,2) NOT NULL DEFAULT 0, -- Итог наличных продаж за смену
    card_sales_total  NUMERIC(12,2) NOT NULL DEFAULT 0, -- Итог карточных продаж за смену
    refunds_total     NUMERIC(12,2) NOT NULL DEFAULT 0, -- Итог возвратов за смену
    fiscal_shift_no   INTEGER,                           -- Номер смены из ККТ
    notes             TEXT,

    -- Только одна открытая смена на pos
    CONSTRAINT uq_open_shift_pos UNIQUE (pos_id, status)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_shift_cashier ON sales.cashier_shift(cashier_id);
CREATE INDEX idx_shift_station ON sales.cashier_shift(station_id);
CREATE INDEX idx_shift_open    ON sales.cashier_shift(pos_id, status)
    WHERE status = 'OPEN';
```

---

### 5.2 `order` — Заказы

```sql
CREATE TYPE sales.order_status AS ENUM (
    'PENDING',    -- Создан, ожидает оплаты
    'PAID',       -- Оплачен, фискализирован
    'PARTIALLY_REFUNDED', -- Часть билетов возвращена
    'REFUNDED',   -- Все билеты возвращены
    'CANCELLED'   -- Отменён (оплата не прошла или истёк таймаут)
);

CREATE TABLE sales.order (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shift_id      UUID NOT NULL REFERENCES sales.cashier_shift(id),
    station_id    UUID NOT NULL,
    cashier_id    UUID NOT NULL,
    status        sales.order_status NOT NULL DEFAULT 'PENDING',
    total_amount  NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    currency      CHAR(3) NOT NULL DEFAULT 'RUB',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ NOT NULL,  -- Таймаут заказа (= TTL резервирований + буфер)
    notes         TEXT
);

CREATE INDEX idx_order_shift   ON sales.order(shift_id);
CREATE INDEX idx_order_station ON sales.order(station_id);
CREATE INDEX idx_order_status  ON sales.order(status);
CREATE INDEX idx_order_expires ON sales.order(expires_at)
    WHERE status = 'PENDING';

COMMENT ON COLUMN sales.order.expires_at IS
    'Время жизни заказа. Если оплата не поступила до этого момента,
     заказ автоматически отменяется, резервирования освобождаются.';
```

---

### 5.3 `ticket` — Билеты

```sql
CREATE TYPE sales.ticket_status AS ENUM (
    'RESERVED',  -- Место зарезервировано, заказ не оплачен
    'ISSUED',    -- Выдан (оплачен и фискализирован)
    'USED',      -- Использован (пассажир прошёл посадку)
    'REFUNDED',  -- Возвращён
    'EXPIRED'    -- Истёк (заказ отменён)
);

CREATE TYPE sales.doc_type AS ENUM (
    'PASSPORT_RF',
    'PASSPORT_FOREIGN',
    'BIRTH_CERTIFICATE',
    'MILITARY_ID',
    'OTHER'
);

CREATE TABLE sales.ticket (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id         UUID NOT NULL REFERENCES sales.order(id),
    ticket_number    VARCHAR(30) NOT NULL UNIQUE,  -- Генерируется системой
    trip_id          UUID NOT NULL,
    seat_number      SMALLINT NOT NULL,
    reservation_id   UUID NOT NULL,               -- Ссылка на резервирование в inventory-service
    from_stop_order  SMALLINT NOT NULL,
    to_stop_order    SMALLINT NOT NULL,
    -- Денормализованные данные остановок для истории
    from_stop_name   VARCHAR(255) NOT NULL,
    to_stop_name     VARCHAR(255) NOT NULL,
    -- Данные пассажира
    passenger_name   VARCHAR(255) NOT NULL,
    doc_type         sales.doc_type NOT NULL,
    doc_number       VARCHAR(50) NOT NULL,
    -- Финансы
    tariff_id        UUID REFERENCES sales.tariff(id),
    base_price       NUMERIC(12,2) NOT NULL CHECK (base_price >= 0),
    -- Денормализованные данные рейса для истории
    trip_number      VARCHAR(20) NOT NULL,
    trip_date        DATE NOT NULL,
    route_name       VARCHAR(500) NOT NULL,
    carrier_name     VARCHAR(255) NOT NULL,
    vehicle_plate    VARCHAR(20),
    status           sales.ticket_status NOT NULL DEFAULT 'RESERVED',
    issued_at        TIMESTAMPTZ,
    fiscal_receipt_id UUID,
    qr_code          TEXT,   -- Base64 QR-код для посадки

    CONSTRAINT chk_ticket_stops CHECK (to_stop_order > from_stop_order)
);

CREATE INDEX idx_ticket_order     ON sales.ticket(order_id);
CREATE INDEX idx_ticket_trip      ON sales.ticket(trip_id);
CREATE INDEX idx_ticket_number    ON sales.ticket(ticket_number);
CREATE INDEX idx_ticket_status    ON sales.ticket(status);
CREATE INDEX idx_ticket_passenger ON sales.ticket(doc_type, doc_number);

COMMENT ON COLUMN sales.ticket.ticket_number IS
    'Формат: {ГГ}{КОД_ВОКЗАЛА}{ННННН}, например: 250012300042.
     Уникален в рамках системы.';

COMMENT ON COLUMN sales.ticket.qr_code IS
    'QR-код содержит: ticket_id + trip_id + seat_number + контрольная сумма.
     Используется для сканирования при посадке (ТСД-приложение).';
```

---

### 5.4 `payment` — Платежи

```sql
CREATE TYPE sales.payment_type AS ENUM (
    'CASH',         -- Наличные
    'CARD'          -- Банковская карта
);

CREATE TYPE sales.payment_status AS ENUM (
    'PENDING',      -- Инициирован
    'COMPLETED',    -- Успешно завершён
    'FAILED',       -- Ошибка
    'REFUNDED'      -- Средства возвращены
);

CREATE TABLE sales.payment (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID NOT NULL UNIQUE REFERENCES sales.order(id),
    payment_type          sales.payment_type NOT NULL,
    amount                NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency              CHAR(3) NOT NULL DEFAULT 'RUB',
    status                sales.payment_status NOT NULL DEFAULT 'PENDING',
    -- Данные для карточного платежа
    card_transaction_id   VARCHAR(128),   -- ID транзакции из банковского терминала
    card_auth_code        VARCHAR(20),    -- Код авторизации
    card_last_four        CHAR(4),        -- Последние 4 цифры карты
    terminal_receipt_no   VARCHAR(50),    -- Номер чека терминала
    terminal_id           VARCHAR(50),    -- ID банковского терминала
    -- Данные для наличных
    cash_received         NUMERIC(12,2),  -- Сумма принята от пассажира
    cash_change           NUMERIC(12,2),  -- Сдача
    processed_at          TIMESTAMPTZ,
    error_message         TEXT
);

CREATE INDEX idx_payment_order ON sales.payment(order_id);

COMMENT ON COLUMN sales.payment.card_transaction_id IS
    'Сохраняется для возможности возврата через банковский терминал
     по оригинальной транзакции (без повторного ввода карты).';
```

---

### 5.5 `refund` — Возвраты

```sql
CREATE TYPE sales.refund_type AS ENUM (
    'CASH',   -- Возврат наличными
    'CARD'    -- Возврат на карту
);

CREATE TABLE sales.refund (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id        UUID NOT NULL UNIQUE REFERENCES sales.ticket(id),
    policy_id        UUID REFERENCES sales.refund_policy(id),
    original_price   NUMERIC(12,2) NOT NULL,
    penalty_percent  NUMERIC(5,2) NOT NULL DEFAULT 0
                     CHECK (penalty_percent BETWEEN 0 AND 100),
    penalty_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
    service_fee      NUMERIC(12,2) NOT NULL DEFAULT 0,
    refund_amount    NUMERIC(12,2) NOT NULL CHECK (refund_amount >= 0),
    refund_type      sales.refund_type NOT NULL,
    -- Для возврата на карту
    card_transaction_id VARCHAR(128),
    terminal_receipt_no VARCHAR(50),
    processed_by     UUID NOT NULL,
    processed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fiscal_receipt_id UUID,

    CONSTRAINT chk_refund_amount CHECK (
        refund_amount = original_price - penalty_amount - service_fee
    )
);

CREATE INDEX idx_refund_ticket ON sales.refund(ticket_id);

COMMENT ON COLUMN sales.refund.refund_amount IS
    'Итоговая сумма к возврату = original_price - penalty_amount - service_fee.
     Контролируется CHECK-constraint.';
```

---

### 5.6 `fiscal_receipt` — Фискальные чеки

```sql
CREATE TYPE sales.receipt_type AS ENUM (
    'SALE',         -- Чек продажи
    'REFUND'        -- Чек возврата
);

CREATE TYPE sales.ofd_status AS ENUM (
    'PENDING',      -- Ожидает передачи в ОФД
    'SENT',         -- Передан в ОФД
    'CONFIRMED',    -- Подтверждён ОФД
    'ERROR'         -- Ошибка передачи
);

CREATE TABLE sales.fiscal_receipt (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_type     sales.receipt_type NOT NULL,
    -- Фискальные реквизиты
    fiscal_sign      VARCHAR(20) NOT NULL,    -- Фискальный признак (ФП)
    fiscal_doc_no    INTEGER NOT NULL,         -- Номер фискального документа (ФД)
    fiscal_drive_no  VARCHAR(20) NOT NULL,     -- Номер фискального накопителя (ФН)
    kkm_serial       VARCHAR(30) NOT NULL,     -- Заводской номер ККТ
    kkm_reg_no       VARCHAR(20) NOT NULL,     -- Регистрационный номер ККТ
    ofd_status       sales.ofd_status NOT NULL DEFAULT 'PENDING',
    ofd_confirmed_at TIMESTAMPTZ,
    raw_response     JSONB,   -- Сырой ответ от ККТ SDK (для отладки)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fiscal_ofd_status ON sales.fiscal_receipt(ofd_status)
    WHERE ofd_status IN ('PENDING', 'ERROR');
```

---

### 5.7 `tariff` — Тарифы

```sql
CREATE TABLE sales.tariff (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    carrier_id       UUID NOT NULL,
    route_id         UUID NOT NULL,
    -- NULL = тариф на весь маршрут; NOT NULL = тариф на сегмент
    from_stop_order  SMALLINT,
    to_stop_order    SMALLINT,
    price            NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    currency         CHAR(3) NOT NULL DEFAULT 'RUB',
    valid_from       DATE NOT NULL,
    valid_to         DATE,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_by       UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_tariff_segment CHECK (
        (from_stop_order IS NULL AND to_stop_order IS NULL)
        OR
        (from_stop_order IS NOT NULL AND to_stop_order IS NOT NULL
         AND to_stop_order > from_stop_order)
    ),
    CONSTRAINT chk_tariff_dates CHECK (
        valid_to IS NULL OR valid_to >= valid_from
    )
);

CREATE INDEX idx_tariff_route   ON sales.tariff(route_id, is_active);
CREATE INDEX idx_tariff_carrier ON sales.tariff(carrier_id);

COMMENT ON COLUMN sales.tariff.from_stop_order IS
    'NULL означает тариф на весь маршрут.
     Сегментный тариф (NOT NULL) имеет приоритет над маршрутным.';
```

---

### 5.8 `refund_policy` и `refund_policy_tier` — Политика возвратов

```sql
CREATE TABLE sales.refund_policy (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    carrier_id        UUID,   -- NULL = системная политика по умолчанию
    name              VARCHAR(255) NOT NULL,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    service_fee_fixed NUMERIC(12,2) NOT NULL DEFAULT 0,  -- Фиксированный сбор за возврат
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    created_by        UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sales.refund_policy_tier (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id         UUID NOT NULL REFERENCES sales.refund_policy(id),
    -- Диапазон часов до отправления: [hours_before_min, hours_before_max)
    -- NULL в hours_before_max = нет верхней границы (т.е. "более N часов")
    hours_before_min  INTEGER,             -- NULL = после отправления
    hours_before_max  INTEGER,             -- NULL = без верхней границы
    penalty_percent   NUMERIC(5,2) NOT NULL DEFAULT 0
                      CHECK (penalty_percent BETWEEN 0 AND 100),
    refund_allowed    BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order        SMALLINT NOT NULL,

    CONSTRAINT uq_tier_policy_order UNIQUE (policy_id, sort_order)
);

COMMENT ON TABLE sales.refund_policy_tier IS
    'Уровни штрафов политики возврата. Пример базовой политики (ПП РФ №112):
     Tier 1: hours_before_min=24, hours_before_max=NULL → penalty=0%
     Tier 2: hours_before_min=2,  hours_before_max=24   → penalty=10%
     Tier 3: hours_before_min=0,  hours_before_max=2    → penalty=25%
     Tier 4: hours_before_min=NULL (после отправления)  → refund_allowed=false';
```

---

## 6. Процессы продажи и возврата

### 6.1 Полный процесс продажи

```
КАССИР                    SALES-SERVICE              INVENTORY-SERVICE    ККТ/ТЕРМИНАЛ
   │                           │                            │                   │
   │ 1. Выбрать рейс и место   │                            │                   │
   │──────────────────────────►│                            │                   │
   │                           │ POST /reserve              │                   │
   │                           │───────────────────────────►│                   │
   │                           │ 200 { reservationId,       │                   │
   │◄──────────────────────────│       expiresAt }          │                   │
   │                           │◄───────────────────────────│                   │
   │ 2. Ввести данные          │                            │                   │
   │    пассажира и            │                            │                   │
   │    подтвердить заказ      │                            │                   │
   │──────────────────────────►│                            │                   │
   │                           │ Создать Order (PENDING)    │                   │
   │                           │ Создать Ticket (RESERVED)  │                   │
   │                           │ Рассчитать стоимость       │                   │
   │◄──────────────────────────│                            │                   │
   │ { orderId, amount }       │                            │                   │
   │                           │                            │                   │
   │ 3. Принять оплату         │                            │                   │
   │    (карта/наличные)       │                            │                   │
   │──────────────────────────►│                            │                   │
   │                           │                            │ Провести платёж   │
   │                           │───────────────────────────────────────────────►│
   │                           │◄───────────────────────────────────────────────│
   │                           │ { transactionId, authCode }                   │
   │                           │                            │                   │
   │                           │ 4. Фискализация            │                   │
   │                           │───────────────────────────────────────────────►│
   │                           │◄───────────────────────────────────────────────│
   │                           │ { fiscalSign, docNo, driveNo }                │
   │                           │                            │                   │
   │                           │ 5. Подтвердить резервирование                  │
   │                           │ POST /reservations/{id}/confirm                │
   │                           │───────────────────────────►│                   │
   │                           │◄───────────────────────────│                   │
   │                           │                            │                   │
   │                           │ 6. Обновить Order → PAID   │                   │
   │                           │    Обновить Ticket → ISSUED│                   │
   │                           │    Сформировать QR-код     │                   │
   │                           │    Опубликовать событие    │                   │
   │                           │                            │                   │
   │ 7. Печать билета          │                            │                   │
   │◄──────────────────────────│                            │                   │
   │ { ticket, fiscalReceipt } │                            │                   │
```

### 6.2 Обработка сбоев при продаже (Saga)

Продажа — это распределённая транзакция. При сбое на любом шаге выполняются компенсирующие операции:

```
Шаг 1: Резервирование места
  Сбой → Сообщить кассиру, место остаётся свободным

Шаг 2: Создание заказа
  Сбой → Освободить резервирование (POST /reservations/{id}/release)

Шаг 3: Проведение платежа картой
  Сбой → Отменить заказ (CANCELLED), освободить резервирование
  Таймаут → Запросить статус транзакции у терминала повторно

Шаг 4: Фискализация
  Сбой → Выполнить возврат платежа на терминале,
          отменить заказ, освободить резервирование
  Примечание: ККТ-SDK должен вернуть конкретный код ошибки.
              Если ответ неизвестен — запрос повтора у ККТ перед отменой.

Шаг 5: Подтверждение резервирования в inventory-service
  Сбой → Попытка повтора (3 раза с экспоненциальной задержкой)
          Если все попытки неудачны → алерт оператору (критическая ситуация:
          деньги взяты, фискализировано, но место не помечено как SOLD)
```

### 6.3 Полный процесс возврата

```
КАССИР                    SALES-SERVICE              INVENTORY-SERVICE    ККТ/ТЕРМИНАЛ
   │                           │                            │                   │
   │ 1. Найти билет по         │                            │                   │
   │    номеру или QR-коду     │                            │                   │
   │──────────────────────────►│                            │                   │
   │◄──────────────────────────│                            │                   │
   │ { ticket, refundPreview } │                            │                   │
   │   refundPreview:          │                            │                   │
   │   - originalPrice: 1200₽ │                            │                   │
   │   - penaltyPercent: 10%  │                            │                   │
   │   - penaltyAmount: 120₽  │                            │                   │
   │   - serviceFee: 0₽       │                            │                   │
   │   - refundAmount: 1080₽  │                            │                   │
   │                           │                            │                   │
   │ 2. Подтвердить возврат    │                            │                   │
   │──────────────────────────►│                            │                   │
   │                           │ 3. Фискальный чек возврата │                   │
   │                           │───────────────────────────────────────────────►│
   │                           │◄───────────────────────────────────────────────│
   │                           │                            │                   │
   │                           │ 4. Возврат денег           │                   │
   │                           │    (карта: по orig. txn)   │                   │
   │                           │───────────────────────────────────────────────►│
   │                           │◄───────────────────────────────────────────────│
   │                           │                            │                   │
   │                           │ 5. Освободить место        │                   │
   │                           │ POST /seats/release        │                   │
   │                           │───────────────────────────►│                   │
   │                           │                            │                   │
   │                           │ 6. Обновить Ticket → REFUNDED                  │
   │                           │    Создать Refund запись                       │
   │                           │    Обновить итоги смены                        │
   │◄──────────────────────────│                            │                   │
   │ { refundReceipt }         │                            │                   │
```

---

## 7. Система тарифов и штрафов

### 7.1 Алгоритм расчёта стоимости билета

```
ФУНКЦИЯ calculatePrice(routeId, fromStop, toStop, tripDate, carrierId):

  1. Найти активный сегментный тариф:
     SELECT * FROM tariff
     WHERE route_id = routeId
       AND from_stop_order = fromStop
       AND to_stop_order = toStop
       AND valid_from <= tripDate
       AND (valid_to IS NULL OR valid_to >= tripDate)
       AND is_active = TRUE
     ORDER BY valid_from DESC
     LIMIT 1

  2. Если сегментный тариф найден → вернуть его цену

  3. Иначе найти маршрутный тариф:
     SELECT * FROM tariff
     WHERE route_id = routeId
       AND from_stop_order IS NULL
       AND to_stop_order IS NULL
       AND valid_from <= tripDate
       AND (valid_to IS NULL OR valid_to >= tripDate)
       AND is_active = TRUE
     ORDER BY valid_from DESC
     LIMIT 1

  4. Если маршрутный тариф найден → вернуть его цену

  5. Иначе → ошибка TARIFF_NOT_FOUND
```

### 7.2 Алгоритм расчёта штрафа при возврате

```
ФУНКЦИЯ calculateRefund(ticketId, refundRequestedAt):

  1. Получить ticket: originalPrice, tripId
  2. Получить рейс: scheduled_departure (время отправления с остановки пассажира)
  3. hoursUntilDeparture = (scheduled_departure - refundRequestedAt) / 3600

  4. Получить активную политику возврата:
     - Сначала для carrier_id рейса
     - Если нет — системную (carrier_id IS NULL)

  5. Найти подходящий tier политики:
     WHERE (hours_before_min IS NULL OR hoursUntilDeparture >= hours_before_min)
       AND (hours_before_max IS NULL OR hoursUntilDeparture < hours_before_max)
     ORDER BY sort_order ASC
     LIMIT 1

  6. Если tier.refund_allowed = FALSE → ошибка REFUND_NOT_ALLOWED

  7. penaltyAmount = ROUND(originalPrice * tier.penalty_percent / 100, 2)
     serviceFee    = policy.service_fee_fixed
     refundAmount  = originalPrice - penaltyAmount - serviceFee

  8. Вернуть:
     { penaltyPercent, penaltyAmount, serviceFee, refundAmount }
```

### 7.3 Примеры расчёта штрафов (базовая политика ПП РФ №112)

| Время до отправления | Цена билета | Штраф | Сервисный сбор | К возврату |
|----------------------|-------------|-------|----------------|------------|
| 30 часов | 1 200 ₽ | 0% = 0 ₽ | 0 ₽ | **1 200 ₽** |
| 12 часов | 1 200 ₽ | 10% = 120 ₽ | 0 ₽ | **1 080 ₽** |
| 1 час | 1 200 ₽ | 25% = 300 ₽ | 0 ₽ | **900 ₽** |
| После отправления | 1 200 ₽ | — | — | **Возврат запрещён** |

---

## 8. Фискализация

### 8.1 Схема взаимодействия с ККТ

```
sales-service                hardware-agent (Go)              ККТ (АТОЛ/Штрих-М)
      │                              │                               │
      │ POST /fiscal/receipt         │                               │
      │ { items, amount, type }      │                               │
      │─────────────────────────────►│                               │
      │                              │ SDK вызов: PrintReceipt()    │
      │                              │──────────────────────────────►│
      │                              │◄──────────────────────────────│
      │                              │ { fiscalSign, docNo,          │
      │                              │   driveNo, dateTime }         │
      │◄─────────────────────────────│                               │
      │ { fiscalReceiptId,           │                               │
      │   fiscalSign, docNo }        │                               │
```

### 8.2 Состав фискального чека продажи билета

```
ООО «АвтоЭкспресс Сибирь»
ИНН: 2460123456
Регистрационный номер ККТ: 0000000000012345

КАССОВЫЙ ЧЕК (ПРИХОД)
──────────────────────────────────
Билет №250012300042
Маршрут: Красноярск — Канск
Рейс №101 от 15.07.2025 07:30
Место: 42
Пассажир: Иванов Иван Иванович
Паспорт: 04 12 123456
──────────────────────────────────
Итого:                    1 200,00 ₽
НДС не облагается
Способ расчёта: ПОЛНЫЙ РАСЧЁТ
Признак расчёта: ПРИХОД
──────────────────────────────────
Кассир: Петрова А.В.
Смена №: 15
ФД №: 000042    ФП: 1234567890
ФН: 9999078900012345
```

### 8.3 Обработка ошибок фискализации

| Код ошибки ККТ | Действие |
|----------------|----------|
| `KKT_OFFLINE` | Ожидание восстановления связи, повтор через 10 сек (до 3 раз) |
| `SHIFT_EXPIRED` | Закрыть смену автоматически, открыть новую, повторить фискализацию |
| `PAPER_EMPTY` | Уведомить кассира, ожидать подтверждения заправки бумаги |
| `KKT_ERROR` | Откатить платёж, уведомить кассира, алерт администратору |

---

## 9. Управление кассовой сменой

### 9.1 Жизненный цикл смены

```
Кассир открывает смену
  │
  ├── POST /shifts/open
  │   { cashierId, posId, stationId, openingBalance }
  │
  ├── sales-service создаёт CashierShift (OPEN)
  ├── Запрос к hardware-agent: открыть смену на ККТ
  │   → Получить fiscal_shift_no
  │
  ▼
[OPEN] — все продажи и возвраты смены привязываются к этой записи
  │
  ├── Автоматически обновляются счётчики:
  │   cash_sales_total, card_sales_total, refunds_total
  │
Кассир закрывает смену
  │
  ├── POST /shifts/{id}/close
  │   { closingBalance }
  │
  ├── Проверка: нет незавершённых транзакций
  ├── Запрос к hardware-agent: Z-отчёт на ККТ
  ├── Освобождение всех резервирований сессии
  ├── Обновление CashierShift (CLOSED)
  │
  ▼
[CLOSED] — смена закрыта, Z-отчёт сформирован
```

### 9.2 Итоги смены (сводка при закрытии)

```json
{
  "shift_id": "shift-001",
  "cashier_name": "Петрова Анна Викторовна",
  "pos_id": "KASSA-1",
  "station_name": "Красноярск (Центральный)",
  "opened_at": "2025-07-15T08:00:00+07:00",
  "closed_at": "2025-07-15T20:00:00+07:00",
  "summary": {
    "tickets_sold": 47,
    "tickets_refunded": 3,
    "cash_sales_total": 28400.00,
    "card_sales_total": 31600.00,
    "refunds_total": 3240.00,
    "net_cash_total": 25160.00,
    "opening_balance": 5000.00,
    "expected_closing_balance": 30160.00,
    "actual_closing_balance": 30160.00,
    "discrepancy": 0.00
  },
  "fiscal_shift_no": 15
}
```

---

## 10. Примеры данных

### 10.1 Заказ с двумя билетами

```json
{
  "id": "order-001",
  "shift_id": "shift-001",
  "station_id": "st-001",
  "cashier_id": "cashier-003",
  "status": "PAID",
  "total_amount": 2400.00,
  "currency": "RUB",
  "created_at": "2025-07-15T09:05:00+07:00",
  "tickets": [
    {
      "id": "ticket-001",
      "ticket_number": "250010000042",
      "trip_id": "trip-e5f6a7b8",
      "trip_number": "101",
      "trip_date": "2025-07-15",
      "seat_number": 12,
      "passenger_name": "Иванов Иван Иванович",
      "doc_type": "PASSPORT_RF",
      "doc_number": "04 12 123456",
      "from_stop_name": "Красноярск (Центральный)",
      "to_stop_name": "Канск (Автовокзал)",
      "base_price": 1200.00,
      "status": "ISSUED",
      "issued_at": "2025-07-15T09:07:00+07:00"
    },
    {
      "id": "ticket-002",
      "ticket_number": "250010000043",
      "seat_number": 13,
      "passenger_name": "Иванова Мария Петровна",
      "doc_type": "PASSPORT_RF",
      "doc_number": "04 12 654321",
      "base_price": 1200.00,
      "status": "ISSUED"
    }
  ],
  "payment": {
    "payment_type": "CARD",
    "amount": 2400.00,
    "card_transaction_id": "TXN-20250715-00412",
    "card_auth_code": "123456",
    "card_last_four": "4242",
    "status": "COMPLETED"
  }
}
```

### 10.2 Возврат билета

```json
{
  "id": "refund-001",
  "ticket_id": "ticket-001",
  "ticket_number": "250010000042",
  "original_price": 1200.00,
  "refund_requested_at": "2025-07-15T19:30:00+07:00",
  "scheduled_departure": "2025-07-16T07:30:00+07:00",
  "hours_until_departure": 12.0,
  "policy_applied": "ПП РФ №112 (базовая)",
  "penalty_percent": 10.0,
  "penalty_amount": 120.00,
  "service_fee": 0.00,
  "refund_amount": 1080.00,
  "refund_type": "CARD",
  "card_transaction_id": "TXN-REFUND-20250715-00001",
  "processed_by": "cashier-003",
  "processed_at": "2025-07-15T19:32:00+07:00"
}
```

### 10.3 Политика возврата (базовая)

```json
{
  "id": "policy-default",
  "carrier_id": null,
  "name": "Базовая политика (ПП РФ №112)",
  "is_active": true,
  "service_fee_fixed": 0.00,
  "valid_from": "2024-01-01",
  "tiers": [
    {
      "sort_order": 1,
      "hours_before_min": 24,
      "hours_before_max": null,
      "penalty_percent": 0.0,
      "refund_allowed": true,
      "description": "Более 24 часов до отправления — без штрафа"
    },
    {
      "sort_order": 2,
      "hours_before_min": 2,
      "hours_before_max": 24,
      "penalty_percent": 10.0,
      "refund_allowed": true,
      "description": "От 2 до 24 часов — штраф 10%"
    },
    {
      "sort_order": 3,
      "hours_before_min": 0,
      "hours_before_max": 2,
      "penalty_percent": 25.0,
      "refund_allowed": true,
      "description": "Менее 2 часов — штраф 25%"
    },
    {
      "sort_order": 4,
      "hours_before_min": null,
      "hours_before_max": 0,
      "penalty_percent": 100.0,
      "refund_allowed": false,
      "description": "После отправления — возврат запрещён"
    }
  ]
}
```

---

## 11. События (NATS JetStream)

### События, которые сервис **потребляет**

| Subject | Источник | Действие |
|---------|----------|---------|
| `scheduling.trip.cancelled` | scheduling-service | Инициировать массовый возврат по всем билетам рейса |
| `inventory.reaccommodation.required` | inventory-service | Уведомить о билетах, требующих пересадки |

### События, которые сервис **публикует** (stream: `transora.sales`)

| Subject | Триггер | Потребители |
|---------|---------|------------|
| `sales.ticket.issued` | Успешная продажа | document-service, notification-service |
| `sales.ticket.refunded` | Успешный возврат | inventory-service |
| `sales.order.cancelled` | Отмена заказа (таймаут/сбой) | inventory-service |
| `sales.shift.opened` | Открытие кассовой смены | — |
| `sales.shift.closed` | Закрытие кассовой смены | — |

### Пример payload `sales.ticket.issued`

```json
{
  "event_id": "evt-sal-001",
  "event_type": "sales.ticket.issued",
  "occurred_at": "2025-07-15T09:07:00Z",
  "payload": {
    "ticket_id": "ticket-001",
    "ticket_number": "250010000042",
    "order_id": "order-001",
    "trip_id": "trip-e5f6a7b8",
    "trip_number": "101",
    "trip_date": "2025-07-15",
    "seat_number": 12,
    "passenger_name": "Иванов Иван Иванович",
    "station_id": "st-001",
    "cashier_id": "cashier-003"
  }
}
```

---

## 12. Зависимости и взаимодействие

```
                   ┌──────────────────────────┐
                   │       sales-service       │
                   └────────────┬─────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
  Синхронные вызовы       Потребляет             Публикует
  (REST)                  события NATS           события NATS
        │                       │                       │
┌───────▼──────────┐   ┌────────▼────────┐   ┌─────────▼───────┐
│inventory-service │   │scheduling-svc   │   │document-service │
│· reserve         │   │(trip.cancelled) │   │notification-svc │
│· confirm         │   └─────────────────┘   │inventory-svc    │
│· release         │                         └─────────────────┘
└──────────────────┘
        │
┌───────▼──────────┐
│ hardware-agent   │   ← Синхронные вызовы для фискализации
│· fiscal receipt  │     и проведения платежей
│· payment charge  │
│· payment refund  │
│· shift open/close│
└──────────────────┘

REST API (входящие):
┌──────────────────┐
│  cashier-app     │   ← Основной потребитель API
│  (Tauri)         │
└──────────────────┘
```

**Ключевой принцип:** `sales-service` является единственным оркестратором бизнес-транзакции продажи. Он координирует `inventory-service` и `hardware-agent` синхронно, а результат транслирует в события для остальных сервисов.

---

*Следующий документ: `transora-notification-service.md` — Сервис уведомлений, табло и озвучивания*
