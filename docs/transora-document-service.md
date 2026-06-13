# Transora — Document Service
## Сервис формирования поездной документации: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `document-service`

---

## Содержание

1. [Назначение сервиса](#1-назначение-сервиса)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Бизнес-правила и ограничения](#3-бизнес-правила-и-ограничения)
4. [Типы документов](#4-типы-документов)
5. [Модель данных (ERD)](#5-модель-данных-erd)
6. [Схемы таблиц БД](#6-схемы-таблиц-бд)
7. [Процессы генерации документов](#7-процессы-генерации-документов)
8. [Структура и содержание документов](#8-структура-и-содержание-документов)
9. [Шаблоны документов](#9-шаблоны-документов)
10. [Примеры данных](#10-примеры-данных)
11. [События (NATS JetStream)](#11-события-nats-jetstream)
12. [Зависимости и взаимодействие](#12-зависимости-и-взаимодействие)

---

## 1. Назначение сервиса

`document-service` отвечает за генерацию, хранение и выдачу всей поездной документации системы Transora.

Основные обязанности:

- Генерация поездной документации по событиям из других сервисов (автоматически) или по запросу (вручную)
- Хранение сформированных документов и управление их жизненным циклом
- Предоставление документов для скачивания и печати диспетчерам и кассирам
- Генерация и хранение QR-кодов и штрихкодов на билетах для ТСД-приложения
- Формирование отчётности для перевозчиков

Сервис является **потребителем данных** — он не владеет источниками и не изменяет состояние других сервисов. Все данные для формирования документов получаются через синхронные API-запросы к `sales-service` и `scheduling-service` в момент генерации.

**Стек:** Kotlin + Spring Boot + PostgreSQL + JasperReports + MinIO (объектное хранилище)

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **TripManifest** (Манифест рейса) | Полный список пассажиров рейса с персональными данными и номерами мест |
| **BoardingSheet** (Посадочная ведомость) | Список пассажиров с местами и штрихкодами для сверки при посадке. Передаётся водителю. |
| **TicketDocument** (Билет для печати) | Печатная форма билета с QR-кодом, выдаётся пассажиру |
| **CarrierReport** (Отчёт перевозчика) | Финансово-статистический отчёт по рейсу для перевозчика |
| **DocumentTemplate** (Шаблон документа) | JasperReports-шаблон (.jrxml), определяющий визуальное оформление |
| **GeneratedDocument** (Сгенерированный документ) | Готовый файл (PDF), сохранённый в объектном хранилище |
| **DocumentRequest** (Запрос на генерацию) | Задание на создание документа: тип, параметры, статус выполнения |

---

## 3. Бизнес-правила и ограничения

### 3.1 Общие правила

- **BR-DOC-001:** Все документы генерируются в формате PDF.
- **BR-DOC-002:** Сгенерированные документы хранятся в объектном хранилище (MinIO). В БД хранятся только метаданные и ссылка на файл.
- **BR-DOC-003:** Документы доступны для скачивания в течение 90 дней после даты рейса. После — архивируются или удаляются согласно политике хранения.
- **BR-DOC-004:** Доступ к документам ограничен: манифест и посадочная ведомость — только диспетчер и администратор. Билет для печати — кассир, выдавший билет.
- **BR-DOC-005:** Каждый запрос на генерацию фиксируется в `document_request` с результатом и временем выполнения.
- **BR-DOC-006:** При повторном запросе одного и того же документа (тип + параметры + версия данных) возвращается кешированный файл без повторной генерации, если данные не изменились.

### 3.2 Манифест рейса

- **BR-DOC-010:** Манифест генерируется автоматически при событии начала посадки (`sales.boarding.started`) или вручную по запросу диспетчера.
- **BR-DOC-011:** Манифест включает всех пассажиров с билетами в статусе `ISSUED` и `USED` на момент генерации.
- **BR-DOC-012:** При генерации манифеста после его первого создания создаётся новая версия. Старые версии не удаляются — сохраняется история.
- **BR-DOC-013:** Манифест содержит итоговую строку: общее число пассажиров, занятых мест, свободных мест.

### 3.3 Посадочная ведомость

- **BR-DOC-020:** Посадочная ведомость генерируется одновременно с манифестом.
- **BR-DOC-021:** Каждая строка ведомости содержит штрихкод (Code 128) с `ticket_id` для сканирования ТСД.
- **BR-DOC-022:** Ведомость сортируется по номеру места.
- **BR-DOC-023:** Ведомость предназначена для передачи водителю — должна быть компактной, умещаться на минимальное число листов A4.

### 3.4 Билет для печати

- **BR-DOC-030:** Билет генерируется немедленно после успешной продажи (событие `sales.ticket.issued`).
- **BR-DOC-031:** Билет содержит QR-код с данными для сканирования: `ticket_id`, `trip_id`, `seat_number`, контрольная сумма.
- **BR-DOC-032:** Формат билета — термопечать на рулонной бумаге 80 мм. Высота — не более 150 мм.
- **BR-DOC-033:** После возврата билета генерируется версия с водяным знаком «АННУЛИРОВАН».
- **BR-DOC-034:** Повторная печать билета допускается. Каждый факт печати логируется.

### 3.5 Отчёт перевозчика

- **BR-DOC-040:** Отчёт генерируется автоматически после перехода рейса в статус `COMPLETED`.
- **BR-DOC-041:** Отчёт содержит: список всех билетов рейса, итоговую выручку, количество пассажиров, список возвратов.
- **BR-DOC-042:** Отчёт доступен перевозчику и администратору системы.

---

## 4. Типы документов

| Код | Название | Триггер генерации | Формат | Получатель |
|-----|----------|-------------------|--------|------------|
| `TICKET` | Билет для печати | `sales.ticket.issued` | PDF (80мм термо) | Пассажир |
| `TICKET_VOID` | Аннулированный билет | `sales.ticket.refunded` | PDF (80мм термо) | Архив |
| `TRIP_MANIFEST` | Манифест рейса | `sales.boarding.started` / ручной | PDF A4 | Диспетчер |
| `BOARDING_SHEET` | Посадочная ведомость | `sales.boarding.started` / ручной | PDF A4 | Водитель |
| `CARRIER_REPORT` | Отчёт перевозчика | `scheduling.trip.completed` | PDF A4 | Перевозчик |
| `SHIFT_REPORT` | Отчёт кассовой смены | `sales.shift.closed` | PDF A4 | Кассир / Бухгалтер |

---

## 5. Модель данных (ERD)

```
┌──────────────────────┐
│  document_template   │
│──────────────────────│
│ id (PK)              │
│ doc_type             │
│ name                 │
│ version              │◄── Версионирование шаблонов
│ jrxml_path           │    (MinIO)
│ is_active            │
│ created_at           │
└──────────┬───────────┘
           │ 1:N
           │ (шаблон используется при генерации)
           │
┌──────────▼───────────┐        ┌──────────────────────┐
│   document_request   │        │  generated_document  │
│──────────────────────│        │──────────────────────│
│ id (PK)              │  1:1   │ id (PK)              │
│ doc_type             │───────►│ request_id (FK)      │
│ template_id (FK)     │        │ doc_type             │
│ status               │        │ file_path            │◄── MinIO path
│ requested_by         │        │ file_size_bytes      │
│ params_json          │        │ checksum_md5         │
│ error_message        │        │ version              │
│ processing_ms        │        │ created_at           │
│ created_at           │        │ expires_at           │
│ completed_at         │        └──────────────────────┘
└──────────────────────┘

Группировки по сущностям:

┌───────────────────────────────────────────────────┐
│               trip_document_set                   │
│───────────────────────────────────────────────────│
│ trip_id (PK часть)                                │
│ manifest_doc_id (FK → generated_document, NULL)   │
│ boarding_sheet_doc_id (FK → generated_document)   │
│ carrier_report_doc_id (FK → generated_document)   │
│ manifest_version                                  │
│ last_generated_at                                 │
└───────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────┐
│              ticket_document                      │
│───────────────────────────────────────────────────│
│ ticket_id (PK)                                    │
│ doc_id (FK → generated_document)                  │
│ void_doc_id (FK → generated_document, NULL)       │
│ print_count                                       │
│ last_printed_at                                   │
└───────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────┐
│               print_log                           │
│───────────────────────────────────────────────────│
│ id (PK)                                           │
│ document_id (FK → generated_document)             │
│ printed_by                                        │
│ station_id                                        │
│ pos_id                                            │
│ printed_at                                        │
└───────────────────────────────────────────────────┘
```

---

## 6. Схемы таблиц БД

Все таблицы в схеме `documents`. СУБД: PostgreSQL 15+.

---

### 6.1 `document_template` — Шаблоны документов

```sql
CREATE TABLE documents.document_template (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type    VARCHAR(30) NOT NULL,
                -- TICKET | TICKET_VOID | TRIP_MANIFEST | BOARDING_SHEET |
                -- CARRIER_REPORT | SHIFT_REPORT
    name        VARCHAR(255) NOT NULL,
    version     SMALLINT NOT NULL DEFAULT 1,
    -- Путь к .jrxml файлу в MinIO: templates/{doc_type}/v{version}.jrxml
    jrxml_path  VARCHAR(500) NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_by  UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_template_type_version UNIQUE (doc_type, version)
);

CREATE INDEX idx_template_type_active ON documents.document_template(doc_type, is_active);

COMMENT ON TABLE documents.document_template IS
    'Шаблоны JasperReports для каждого типа документа.
     Для каждого типа активен только один шаблон одновременно.
     Версионирование позволяет откатиться при необходимости.';
```

---

### 6.2 `document_request` — Запросы на генерацию

```sql
CREATE TYPE documents.request_status AS ENUM (
    'PENDING',      -- В очереди
    'PROCESSING',   -- Генерируется
    'COMPLETED',    -- Успешно сформирован
    'FAILED',       -- Ошибка генерации
    'CACHED'        -- Возвращён из кеша без повторной генерации
);

CREATE TABLE documents.document_request (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type        VARCHAR(30) NOT NULL,
    template_id     UUID REFERENCES documents.document_template(id),
    status          documents.request_status NOT NULL DEFAULT 'PENDING',
    requested_by    UUID NOT NULL,        -- ID пользователя или 'system'
    -- JSON с параметрами генерации (trip_id, ticket_id и т.д.)
    params_json     JSONB NOT NULL,
    -- Хеш параметров для определения кеш-попадания
    params_hash     VARCHAR(64) NOT NULL,
    error_message   TEXT,
    processing_ms   INTEGER,              -- Время генерации в мс
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_request_type_hash ON documents.document_request(doc_type, params_hash)
    WHERE status = 'COMPLETED';
CREATE INDEX idx_request_status    ON documents.document_request(status)
    WHERE status IN ('PENDING', 'PROCESSING');

COMMENT ON COLUMN documents.document_request.params_hash IS
    'MD5-хеш от (doc_type + params_json + template_version).
     Используется для поиска актуального кешированного документа.';
```

---

### 6.3 `generated_document` — Сформированные документы

```sql
CREATE TABLE documents.generated_document (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id       UUID NOT NULL UNIQUE REFERENCES documents.document_request(id),
    doc_type         VARCHAR(30) NOT NULL,
    -- Путь в MinIO: documents/{year}/{month}/{doc_type}/{id}.pdf
    file_path        VARCHAR(500) NOT NULL,
    file_size_bytes  INTEGER NOT NULL CHECK (file_size_bytes > 0),
    checksum_md5     VARCHAR(32) NOT NULL,
    version          SMALLINT NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ NOT NULL  -- created_at + 90 дней по умолчанию
);

CREATE INDEX idx_gendoc_type    ON documents.generated_document(doc_type);
CREATE INDEX idx_gendoc_expires ON documents.generated_document(expires_at);
```

---

### 6.4 `trip_document_set` — Комплект документов рейса

```sql
CREATE TABLE documents.trip_document_set (
    trip_id                UUID PRIMARY KEY,
    manifest_doc_id        UUID REFERENCES documents.generated_document(id),
    boarding_sheet_doc_id  UUID REFERENCES documents.generated_document(id),
    carrier_report_doc_id  UUID REFERENCES documents.generated_document(id),
    manifest_version       SMALLINT NOT NULL DEFAULT 0,
    last_generated_at      TIMESTAMPTZ,
    -- Флаги для отслеживания актуальности
    manifest_stale         BOOLEAN NOT NULL DEFAULT FALSE,
    -- TRUE если после последней генерации были новые продажи/возвраты
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN documents.trip_document_set.manifest_stale IS
    'Устанавливается в TRUE при получении событий sales.ticket.issued
     или sales.ticket.refunded после последней генерации манифеста.
     Диспетчер видит предупреждение о необходимости перегенерации.';
```

---

### 6.5 `ticket_document` — Документы билетов

```sql
CREATE TABLE documents.ticket_document (
    ticket_id       UUID PRIMARY KEY,
    doc_id          UUID NOT NULL REFERENCES documents.generated_document(id),
    void_doc_id     UUID REFERENCES documents.generated_document(id),  -- NULL до возврата
    print_count     INTEGER NOT NULL DEFAULT 0,
    last_printed_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

### 6.6 `print_log` — Журнал печати

```sql
CREATE TABLE documents.print_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID NOT NULL REFERENCES documents.generated_document(id),
    ticket_id    UUID,          -- Заполнено для билетов
    trip_id      UUID,          -- Заполнено для рейсовых документов
    printed_by   UUID NOT NULL,
    station_id   UUID NOT NULL,
    pos_id       VARCHAR(64),   -- ID рабочего места (для билетов)
    print_type   VARCHAR(20) NOT NULL,
    -- TICKET_PRINT | TICKET_REPRINT | MANIFEST | BOARDING_SHEET | REPORT
    printed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_print_log_ticket ON documents.print_log(ticket_id)
    WHERE ticket_id IS NOT NULL;
CREATE INDEX idx_print_log_trip   ON documents.print_log(trip_id)
    WHERE trip_id IS NOT NULL;
```

---

## 7. Процессы генерации документов

### 7.1 Автоматическая генерация при продаже билета

```
NATS: sales.ticket.issued
        │
        ▼
┌───────────────────────────────────────────────┐
│              document-service                 │
│                                               │
│  1. Получить полные данные билета:            │
│     GET sales-service /tickets/{ticketId}     │
│                                               │
│  2. Получить данные рейса:                    │
│     GET scheduling-service /trips/{tripId}    │
│                                               │
│  3. Получить активный шаблон TICKET           │
│                                               │
│  4. Вычислить params_hash                     │
│     Проверить кеш → обычно MISS (новый билет) │
│                                               │
│  5. Сформировать данные для шаблона           │
│     (TicketReportData)                        │
│                                               │
│  6. JasperReports: fillReport() → JasperPrint │
│     JasperExportManager → PDF bytes           │
│                                               │
│  7. Сохранить в MinIO                         │
│     documents/2025/07/TICKET/{ticketId}.pdf   │
│                                               │
│  8. INSERT generated_document                 │
│     INSERT ticket_document                    │
│     UPDATE document_request → COMPLETED       │
│                                               │
│  9. Опубликовать событие                      │
│     documents.ticket.ready                    │
└───────────────────────────────────────────────┘
        │
        ▼
hardware-agent получает сигнал печати
(через cashier-app WebSocket)
```

### 7.2 Генерация манифеста и посадочной ведомости

```
Триггер: sales.boarding.started ИЛИ ручной запрос диспетчера
        │
        ▼
┌───────────────────────────────────────────────────────┐
│                   document-service                    │
│                                                       │
│  1. GET sales-service /trips/{tripId}/tickets         │
│     (все билеты статуса ISSUED + USED)                │
│                                                       │
│  2. GET scheduling-service /trips/{tripId}            │
│     (данные рейса: маршрут, ТС, водитель, времена)   │
│                                                       │
│  3. Параллельная генерация двух документов:           │
│                                                       │
│   ┌─────────────────────┐  ┌────────────────────────┐ │
│   │   TRIP_MANIFEST     │  │    BOARDING_SHEET      │ │
│   │                     │  │                        │ │
│   │ - Сортировка по     │  │ - Сортировка по        │ │
│   │   фамилии пассажира │  │   номеру места         │ │
│   │ - Полные паспортные │  │ - Штрихкод Code128     │ │
│   │   данные            │  │   на каждую строку     │ │
│   │ - Итоговая строка   │  │ - Компактный формат    │ │
│   └─────────────────────┘  └────────────────────────┘ │
│                                                       │
│  4. Сохранить оба файла в MinIO                       │
│                                                       │
│  5. Обновить trip_document_set                        │
│     (manifest_doc_id, boarding_sheet_doc_id,          │
│      manifest_version++, manifest_stale=FALSE)        │
│                                                       │
│  6. Опубликовать documents.trip_docs.ready            │
└───────────────────────────────────────────────────────┘
```

### 7.3 Отслеживание актуальности манифеста

```
После первой генерации манифеста:

NATS: sales.ticket.issued  ──► UPDATE trip_document_set
NATS: sales.ticket.refunded ──►    SET manifest_stale = TRUE
                                   WHERE trip_id = ?

Диспетчер запрашивает манифест:
  GET /documents/trips/{tripId}/manifest

  IF manifest_stale = TRUE:
    → Вернуть документ с заголовком:
      X-Document-Stale: true
      X-Stale-Since: {timestamp последнего изменения}
    → В интерфейсе: предупреждение «Данные изменились.
      Рекомендуется перегенерировать манифест.»
    → Кнопка «Обновить»
```

### 7.4 Аннулирование билета при возврате

```
NATS: sales.ticket.refunded
        │
        ▼
  1. Получить ticket_document по ticket_id
  2. Скачать оригинальный PDF из MinIO
  3. Наложить водяной знак «АННУЛИРОВАН» + дата/время возврата
  4. Сохранить как новый файл: {ticketId}_void.pdf
  5. Обновить ticket_document.void_doc_id
  6. Пометить trip_document_set.manifest_stale = TRUE
```

---

## 8. Структура и содержание документов

### 8.1 Билет для термопечати (80мм)

```
┌──────────────────────────────────┐  ← 80мм
│          TRANSORA                │
│   ООО «АвтоЭкспресс Сибирь»     │
├──────────────────────────────────┤
│  БИЛЕТ №250010000042             │
├──────────────────────────────────┤
│  Красноярск (Центральный)        │
│  → Канск (Автовокзал)            │
├──────────────────────────────────┤
│  Рейс: №101                      │
│  Дата: 15.07.2025                │
│  Отправление: 07:30              │
│  Прибытие:    10:00              │
├──────────────────────────────────┤
│  Место: 12     Вагон/Автобус: 1  │
├──────────────────────────────────┤
│  Пассажир:                       │
│  ИВАНОВ ИВАН ИВАНОВИЧ            │
│  Паспорт РФ: 04 12 123456        │
├──────────────────────────────────┤
│  Стоимость: 1 200,00 ₽           │
│  Оплата: Карта ****4242          │
├──────────────────────────────────┤
│  ┌──────────────────────────┐    │
│  │                          │    │
│  │    [QR-КОД 35x35мм]      │    │
│  │                          │    │
│  └──────────────────────────┘    │
├──────────────────────────────────┤
│  ФД: 000042  ФП: 1234567890      │
│  ФН: 9999078900012345            │
│  Кассир: Петрова А.В. Касса №1   │
│  15.07.2025 09:07                │
└──────────────────────────────────┘
         ≤ 150мм высота
```

### 8.2 Манифест рейса (A4)

```
╔══════════════════════════════════════════════════════════════════╗
║                    TRANSORA — МАНИФЕСТ РЕЙСА                    ║
╠══════════════════════════════════════════════════════════════════╣
║  Маршрут:    Красноярск (Центральный) — Канск (Автовокзал)     ║
║  Рейс №:     101          Дата: 15.07.2025                      ║
║  Перевозчик: ООО «АвтоЭкспресс Сибирь»                        ║
║  ТС:         ЛиАЗ 52292, А123БВ124                             ║
║  Водитель:   Иванов Сергей Петрович                             ║
║  Отправление (план): 07:30  Отправление (факт): __:__          ║
╠═══╦════════╦══════════════════════════════╦═══════════╦═══════╣
║ № ║ Место  ║ Пассажир                     ║ Документ  ║ Откуда║
╠═══╬════════╬══════════════════════════════╬═══════════╬═══════╣
║ 1 ║   12   ║ Иванов Иван Иванович         ║ ПА 0412   ║ Кр-ск ║
║   ║        ║                              ║ 123456    ║       ║
╠═══╬════════╬══════════════════════════════╬═══════════╬═══════╣
║ 2 ║   13   ║ Иванова Мария Петровна       ║ ПА 0412   ║ Кр-ск ║
║   ║        ║                              ║ 654321    ║       ║
╠═══╬════════╬══════════════════════════════╬═══════════╬═══════╣
║ … ║  ...   ║ ...                          ║ ...       ║ ...   ║
╠═══╩════════╩══════════════════════════════╩═══════════╩═══════╣
║  ИТОГО ПАССАЖИРОВ: 38 / СВОБОДНЫХ МЕСТ: 7 / ВСЕГО МЕСТ: 45   ║
╠══════════════════════════════════════════════════════════════════╣
║  Сформирован: 15.07.2025 07:15  Версия: 3                      ║
║  Диспетчер: Смирнова О.И.   Вокзал: Красноярск (Центральный)  ║
╚══════════════════════════════════════════════════════════════════╝

Подпись водителя: _______________  Дата: ________
```

### 8.3 Посадочная ведомость (A4, компактная)

```
ПОСАДОЧНАЯ ВЕДОМОСТЬ
Рейс №101 / 15.07.2025 / Красноярск → Канск
Водитель: Иванов С.П. | ТС: А123БВ124
──────────────────────────────────────────────────────────
Место │ Пассажир              │ Документ   │  ✓  │ Штрихкод
──────┼───────────────────────┼────────────┼─────┼─────────
  12  │ Иванов И.И.           │ ПА 0412123 │ [ ] │ ║║║║║ █
  13  │ Иванова М.П.          │ ПА 0412654 │ [ ] │ ║║║║║ █
  15  │ Петров А.С.           │ ПА 0511789 │ [ ] │ ║║║║║ █
  ...
──────────────────────────────────────────────────────────
Итого: 38 пассажиров

Водитель: _______________    Диспетчер: _______________
```

### 8.4 Отчёт перевозчика (A4)

```
ОТЧЁТ ПО РЕЙСУ
══════════════════════════════════════════════════════
Перевозчик:   ООО «АвтоЭкспресс Сибирь»
Маршрут:      Красноярск (Центральный) — Канск
Рейс №:       101 от 15.07.2025
ТС:           ЛиАЗ 52292, А123БВ124
Водитель:     Иванов Сергей Петрович
──────────────────────────────────────────────────────
СТАТИСТИКА ПАССАЖИРОВ
  Продано билетов:          38
  Возвращено билетов:        3
  Посажено пассажиров:      36
  Не явилось:                2
──────────────────────────────────────────────────────
ФИНАНСОВЫЙ ИТОГ
  Выручка от продаж:   45 600,00 ₽
  Возвраты (сумма):     3 240,00 ₽
  Удержания (штрафы):     360,00 ₽
  Чистая выручка:      42 720,00 ₽
──────────────────────────────────────────────────────
ПРОДАЖИ ПО ВОКЗАЛАМ
  Красноярск (Центральный):  32 билета / 38 400,00 ₽
  Канск (транзит):            6 билетов / 7 200,00 ₽
──────────────────────────────────────────────────────
ДЕТАЛИЗАЦИЯ ВОЗВРАТОВ
  №250010000018 / Сидоров В.А. / 1200₽ / штраф 120₽
  №250010000025 / Кузнецова Е.В. / 1200₽ / штраф 0₽
  №250010000031 / Морозов П.И. / 1200₽ / штраф 300₽
──────────────────────────────────────────────────────
Сформирован: 15.07.2025 10:45
Система: Transora v1.0
══════════════════════════════════════════════════════
```

---

## 9. Шаблоны документов

### 9.1 Технология генерации

Все документы генерируются с использованием **JasperReports** (библиотека для Kotlin/JVM):

```kotlin
// Пример генерации в сервисе
fun generateDocument(template: DocumentTemplate, data: Any): ByteArray {
    // Загрузить скомпилированный шаблон из кеша
    val jasperReport: JasperReport = templateCache.get(template.id)
        ?: compileAndCache(template)

    // Преобразовать данные в JRBeanCollectionDataSource
    val dataSource = JRBeanCollectionDataSource(listOf(data))

    // Параметры для шаблона
    val params = mapOf(
        "REPORT_LOCALE" to Locale("ru", "RU"),
        "GENERATED_AT" to LocalDateTime.now()
    )

    // Заполнить шаблон данными
    val jasperPrint: JasperPrint = JasperFillManager.fillReport(
        jasperReport, params, dataSource
    )

    // Экспортировать в PDF
    return JasperExportManager.exportReportToPdf(jasperPrint)
}
```

### 9.2 Структура хранения шаблонов в MinIO

```
minio/
└── transora-templates/
    ├── TICKET/
    │   ├── v1.jrxml        ← исходник
    │   └── v1.jasper       ← скомпилированный (кеш)
    ├── TICKET_VOID/
    │   └── v1.jrxml
    ├── TRIP_MANIFEST/
    │   └── v1.jrxml
    ├── BOARDING_SHEET/
    │   └── v1.jrxml
    ├── CARRIER_REPORT/
    │   └── v1.jrxml
    └── SHIFT_REPORT/
        └── v1.jrxml
```

### 9.3 Структура хранения документов в MinIO

```
minio/
└── transora-documents/
    ├── 2025/
    │   ├── 07/
    │   │   ├── TICKET/
    │   │   │   ├── {ticket_id}.pdf
    │   │   │   └── {ticket_id}_void.pdf
    │   │   ├── TRIP_MANIFEST/
    │   │   │   ├── {trip_id}_v1.pdf
    │   │   │   ├── {trip_id}_v2.pdf   ← перегенерация
    │   │   │   └── {trip_id}_v3.pdf
    │   │   ├── BOARDING_SHEET/
    │   │   │   └── {trip_id}_v1.pdf
    │   │   └── CARRIER_REPORT/
    │   │       └── {trip_id}.pdf
```

### 9.4 Data Transfer Objects для шаблонов

```kotlin
// Данные для шаблона билета
data class TicketReportData(
    val ticketNumber: String,
    val routeName: String,
    val fromStopName: String,
    val toStopName: String,
    val tripNumber: String,
    val tripDate: String,             // "15.07.2025"
    val scheduledDeparture: String,   // "07:30"
    val scheduledArrival: String,     // "10:00"
    val seatNumber: Int,
    val passengerName: String,
    val docType: String,              // "Паспорт РФ"
    val docNumber: String,
    val price: String,                // "1 200,00 ₽"
    val paymentType: String,          // "Карта ****4242" | "Наличные"
    val qrCodeBase64: String,         // Base64 PNG QR-кода
    val fiscalDocNo: String,
    val fiscalSign: String,
    val fiscalDriveNo: String,
    val cashierName: String,
    val issuedAt: String,             // "15.07.2025 09:07"
    val carrierName: String,
    val isVoid: Boolean = false,
    val voidedAt: String? = null
)

// Данные для манифеста
data class ManifestReportData(
    val trip: TripInfo,
    val passengers: List<ManifestPassengerRow>,
    val totalPassengers: Int,
    val totalSeats: Int,
    val freeSeats: Int,
    val generatedAt: String,
    val generatedBy: String,
    val stationName: String,
    val version: Int
)

data class ManifestPassengerRow(
    val rowNumber: Int,
    val seatNumber: Int,
    val passengerName: String,
    val docType: String,
    val docNumber: String,
    val fromStopName: String,
    val toStopName: String,
    val ticketNumber: String
)

// Данные для посадочной ведомости
data class BoardingSheetReportData(
    val trip: TripInfo,
    val rows: List<BoardingSheetRow>,
    val totalPassengers: Int,
    val generatedAt: String
)

data class BoardingSheetRow(
    val seatNumber: Int,
    val passengerName: String,
    val docNumber: String,           // Укороченный формат для компактности
    val barcodeValue: String,        // ticket_id для Code128
    val fromStopName: String
)
```

---

## 10. Примеры данных

### 10.1 Запрос на генерацию манифеста

```json
{
  "id": "req-001",
  "doc_type": "TRIP_MANIFEST",
  "template_id": "tmpl-manifest-v1",
  "status": "COMPLETED",
  "requested_by": "dispatcher-002",
  "params_json": {
    "trip_id": "trip-e5f6a7b8",
    "station_id": "st-001",
    "include_transit_passengers": true
  },
  "params_hash": "a3f5c9d2e1b847f6...",
  "processing_ms": 843,
  "created_at": "2025-07-15T07:15:00+07:00",
  "completed_at": "2025-07-15T07:15:00+07:00"
}
```

### 10.2 Сгенерированный документ (метаданные)

```json
{
  "id": "doc-001",
  "request_id": "req-001",
  "doc_type": "TRIP_MANIFEST",
  "file_path": "transora-documents/2025/07/TRIP_MANIFEST/trip-e5f6a7b8_v3.pdf",
  "file_size_bytes": 187432,
  "checksum_md5": "d41d8cd98f00b204e9800998ecf8427e",
  "version": 3,
  "created_at": "2025-07-15T07:15:01+07:00",
  "expires_at": "2025-10-13T07:15:01+07:00"
}
```

### 10.3 Набор документов рейса

```json
{
  "trip_id": "trip-e5f6a7b8",
  "manifest_doc_id": "doc-001",
  "boarding_sheet_doc_id": "doc-002",
  "carrier_report_doc_id": null,
  "manifest_version": 3,
  "manifest_stale": false,
  "last_generated_at": "2025-07-15T07:15:01+07:00"
}
```

### 10.4 Документ билета

```json
{
  "ticket_id": "ticket-001",
  "doc_id": "doc-010",
  "void_doc_id": null,
  "print_count": 1,
  "last_printed_at": "2025-07-15T09:07:15+07:00"
}
```

### 10.5 Запись журнала печати

```json
{
  "id": "print-001",
  "document_id": "doc-010",
  "ticket_id": "ticket-001",
  "trip_id": null,
  "printed_by": "cashier-003",
  "station_id": "st-001",
  "pos_id": "KASSA-1",
  "print_type": "TICKET_PRINT",
  "printed_at": "2025-07-15T09:07:15+07:00"
}
```

---

## 11. События (NATS JetStream)

### События, которые сервис **потребляет**

| Subject | Источник | Действие |
|---------|----------|---------|
| `sales.ticket.issued` | sales-service | Сгенерировать `TICKET`, уведомить кассу о готовности к печати |
| `sales.ticket.refunded` | sales-service | Сгенерировать `TICKET_VOID`, пометить `manifest_stale` |
| `sales.boarding.started` | sales-service | Сгенерировать `TRIP_MANIFEST` + `BOARDING_SHEET` |
| `sales.shift.closed` | sales-service | Сгенерировать `SHIFT_REPORT` |
| `scheduling.trip.completed` | scheduling-service | Сгенерировать `CARRIER_REPORT` |

### События, которые сервис **публикует** (stream: `transora.documents`)

| Subject | Триггер | Потребители |
|---------|---------|------------|
| `documents.ticket.ready` | Билет сформирован | cashier-app (сигнал на печать) |
| `documents.trip_docs.ready` | Манифест и ведомость готовы | dispatcher-app (уведомление) |
| `documents.carrier_report.ready` | Отчёт перевозчика готов | — |

### Пример payload `documents.ticket.ready`

```json
{
  "event_id": "evt-doc-001",
  "event_type": "documents.ticket.ready",
  "occurred_at": "2025-07-15T09:07:01Z",
  "payload": {
    "ticket_id": "ticket-001",
    "ticket_number": "250010000042",
    "document_id": "doc-010",
    "download_url": "/documents/download/doc-010",
    "cashier_session_id": "sess-abc123",
    "pos_id": "KASSA-1"
  }
}
```

---

## 12. Зависимости и взаимодействие

```
                   ┌──────────────────────────┐
                   │      document-service     │
                   └────────────┬─────────────┘
                                │
        ┌───────────────────────┼──────────────────────┐
        │                       │                      │
  Потребляет               Синхронные              Публикует
  события NATS             REST-запросы            события NATS
        │                  (данные для PDF)              │
        │                       │                      │
┌───────▼──────────┐  ┌─────────▼──────────┐  ┌───────▼──────────┐
│  sales-service   │  │  sales-service     │  │  cashier-app     │
│  · ticket.issued │  │  · /tickets/{id}   │  │  dispatcher-app  │
│  · refunded      │  │  · /trips/{id}/    │  └──────────────────┘
│  · boarding      │  │    tickets         │
│  · shift.closed  │  ├────────────────────┤
│                  │  │  scheduling-svc    │
│  scheduling-svc  │  │  · /trips/{id}     │
│  · trip.completed│  └────────────────────┘
└──────────────────┘
        │
  Хранилища:
┌───────▼──────────┐  ┌────────────────────┐
│   PostgreSQL     │  │      MinIO         │
│  (метаданные,    │  │  (PDF-файлы,       │
│   запросы,       │  │   jrxml-шаблоны)   │
│   журналы)       │  └────────────────────┘
└──────────────────┘
```

**Ключевой принцип:** `document-service` является полностью реактивным — он не инициирует бизнес-операции, только реагирует на события и предоставляет готовые документы по запросу. Это делает его независимым и легко заменяемым.

---

*Следующий документ: `transora-notification-service.md` — Сервис уведомлений, табло и системы озвучивания*
