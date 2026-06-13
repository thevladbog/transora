# Transora — Правила для AI-агентов
## Обязательное руководство по работе с кодовой базой

> Версия: 2.0 | Аудитория: AI-агенты (Claude Code, Cursor, Copilot, Aider и др.)
> Читать перед любой задачей, затрагивающей код Transora.

---

## Как пользоваться этим документом

Этот документ — единый источник правил, общих для всех модулей.
Он не заменяет спецификации модулей, а дополняет их.

**Порядок работы с любой задачей:**
1. Прочитать этот документ (или убедиться, что он прочитан в текущей сессии)
2. Прочитать спецификацию затрагиваемого модуля (`transora-{module}.md`)
3. Только после этого писать код

**Если правило этого документа противоречит спецификации модуля** —
спецификация модуля имеет приоритет для своего модуля.
Этот документ описывает общесистемные законы.

---

## Содержание

1. [Карта системы](#1-карта-системы)
2. [Абсолютные запреты](#2-абсолютные-запреты)
3. [Правила межсервисного взаимодействия](#3-правила-межсервисного-взаимодействия)
4. [Правила работы с базами данных](#4-правила-работы-с-базами-данных)
5. [Правила NATS JetStream](#5-правила-nats-jetstream)
6. [Правила авторизации и безопасности](#6-правила-авторизации-и-безопасности)
7. [Правила фискальных операций](#7-правила-фискальных-операций)
8. [Правила офлайн-устойчивости](#8-правила-офлайн-устойчивости)
9. [Правила написания кода](#9-правила-написания-кода)
10. [Правила схем БД и миграций](#10-правила-схем-бд-и-миграций)
11. [Правила тестирования](#11-правила-тестирования)
12. [Доменный глоссарий](#12-доменный-глоссарий)
13. [Справочник: куда смотреть при ошибке](#13-справочник-куда-смотреть-при-ошибке)

---

## 1. Карта системы

### 1.1 Архитектура одним взглядом

```
                         ┌────────────────────────────────┐
                         │       Kong API Gateway         │
                         │  JWT verify · Rate limit · CORS│
                         └──────────────┬─────────────────┘
                                        │
            ┌───────────────────────────┼────────────────────────────┐
            │                           │                            │
     ┌──────▼──────┐           ┌────────▼───────┐           ┌───────▼───────┐
     │ iam-service │           │  scheduling-   │           │  inventory-   │
     │  (Kotlin)   │           │   service      │           │   service     │
     │             │           │  (Kotlin)      │           │  (Kotlin)     │
     │ JWT · Users │           │                │           │               │
     │ Roles · JWKS│           │ Routes · Trips │           │ Seats · Locks │
     └─────────────┘           │ Schedules      │           │ Transit gates │
                               └────────┬───────┘           └───────┬───────┘
                                        │                            │
                                        └──────────┬─────────────────┘
                                                   │ REST (sync)
                                           ┌───────▼───────┐
                                           │ sales-service │
                                           │  (Kotlin)     │
                                           │               │
                                           │ Orders·Tickets│
                                           │ Shifts·Refunds│
                                           └───────┬───────┘
                                                   │ NATS events
                              ┌────────────────────┼───────────────────┐
                              │                    │                   │
                     ┌────────▼───────┐   ┌────────▼────────┐         │
                     │   document-    │   │ notification-   │         │
                     │   service      │   │   service       │         │
                     │  (Kotlin)      │   │    (Go)         │         │
                     │                │   │                 │         │
                     │ PDF·JasperRep. │   │ Boards·Audio    │         │
                     │ MinIO·Cache    │   │ TTS·WebSocket   │         │
                     └────────────────┘   └─────────────────┘         │
                                                                       │
══════════════════════════════ WAN / VPN ═══════════════════════════════
                                                                       │
                                                        ┌──────────────▼──────┐
                                                        │   station-agent     │
                                                        │      (Go)           │
                                                        │                     │
                                                        │ WSS↔Core · Cache    │
                                                        │ Proxy · AudioPlay   │
                                                        └──────┬──────────────┘
                                                               │ LAN (вокзал)
                          ┌────────────────────────────────────┼─────────────┐
                          │                    │               │             │
                 ┌────────▼──────┐   ┌─────────▼────┐  ┌──────▼──────┐  ┌──▼──────────┐
                 │  cashier-app  │   │    табло      │  │ boarding-   │  │  hardware-  │
                 │   (Tauri)     │   │  (браузер)    │  │   app       │  │   agent     │
                 │               │   │               │  │ (Android)   │  │    (Go)     │
                 └───────┬───────┘   └───────────────┘  └─────────────┘  └─────────────┘
                         │ localhost:9090
                 ┌───────▼───────┐
                 │ hardware-agent│
                 │ ККТ·Термин.  │
                 │ Принтер      │
                 └───────────────┘
```

### 1.2 Единственный источник правды по сущностям

| Сущность | Владелец | Никто другой не может |
|----------|---------|----------------------|
| Маршруты, расписания, рейсы | `scheduling-service` | Изменять данные рейсов |
| Статус и доступность мест | `inventory-service` | Менять `seat_status` напрямую |
| Билеты, заказы, смены, платежи | `sales-service` | Создавать или аннулировать билеты |
| Пользователи, роли, токены | `iam-service` | Верифицировать или выдавать JWT |
| PDF-документы, шаблоны | `document-service` | Генерировать документы |
| Состояние табло (live) | `notification-service` (Redis) | Писать в BoardState Redis-ключи |
| Кеш расписания вокзала | `station-agent` (SQLite) | Писать в кеш (только чтение!) |

### 1.3 Стеки по модулям

| Модуль | Язык | Хранилища | Особенности |
|--------|------|----------|-------------|
| `iam-service` | Kotlin + Spring Boot 3 | PostgreSQL · Redis | RS256, bcrypt cost 12 |
| `scheduling-service` | Kotlin + Spring Boot 3 | PostgreSQL | Publisher событий |
| `inventory-service` | Kotlin + Spring Boot 3 | PostgreSQL · Redis | Distributed lock, TTL |
| `sales-service` | Kotlin + Spring Boot 3 | PostgreSQL | Saga, оркестратор |
| `document-service` | Kotlin + Spring Boot 3 | PostgreSQL · MinIO | JasperReports, реактивный |
| `notification-service` | Go 1.22 | PostgreSQL · Redis · MinIO | WebSocket hub, TTS |
| `station-agent` | Go 1.22 | SQLite | Offline-first, reverse proxy |
| `hardware-agent` | Go 1.22 | SQLite | localhost:9090, DLL wrapper |
| `boarding-app` | Android / Kotlin | Room (SQLite) | Offline-first, BroadcastReceiver |

### 1.4 Схемы PostgreSQL по сервисам

```
transora (единая БД)
├── iam           ← iam-service
├── scheduling    ← scheduling-service
├── inventory     ← inventory-service
├── sales         ← sales-service
├── documents     ← document-service
└── notifications ← notification-service
```

---

## 2. Абсолютные запреты

Нарушение любого из этих правил — архитектурная ошибка.
При генерации кода проверяй каждое из них.

---

### ❌ ЗАПРЕТ-01: Не делай прямые JOIN через схемы разных сервисов

```sql
-- ❌ ЗАПРЕЩЕНО — inventory читает таблицу sales напрямую
SELECT s.ticket_id, i.seat_number
FROM sales.ticket s
JOIN inventory.seat_record i ON i.id = s.seat_id;

-- ✅ ПРАВИЛЬНО — каждый сервис хранит только foreign-key ссылки
-- и получает данные другого сервиса через REST или NATS
SELECT * FROM inventory.seat_record WHERE id = :seatId;
-- Данные билета — через REST к sales-service при необходимости
```

---

### ❌ ЗАПРЕТ-02: Не вызывай сервисы в обратном направлении синхронно

Допустимые синхронные REST-вызовы:

```
✅ sales-service      → inventory-service   (резервирование мест)
✅ sales-service      → scheduling-service  (данные рейса)
✅ document-service   → sales-service       (данные билетов)
✅ document-service   → scheduling-service  (данные рейса)

❌ inventory-service  → sales-service
❌ scheduling-service → inventory-service
❌ scheduling-service → sales-service
❌ notification-service → sales-service     (только через NATS)
❌ iam-service        → любой бизнес-сервис
❌ document-service   → изменение данных в sales/inventory/scheduling
```

Если нужна информация «вверх по цепочке» — используй события NATS, не обратный вызов.

---

### ❌ ЗАПРЕТ-03: Не продавай место без активного резервирования

Продажа без предварительного резервирования в `inventory-service` — архитектурная ошибка.
Единственный законный порядок:

```
1. POST /inventory/seats/{id}/reserve  → reservationId (TTL 10 минут)
2. (оплата + ввод данных пассажира)
3. POST /sales/orders { reservationId, ... }
4. sales-service → POST /inventory/reservations/{id}/confirm
```

Код, создающий `ticket` без `reservation_id`, — неверен.

---

### ❌ ЗАПРЕТ-04: Не меняй статус места напрямую из sales-service

`seat_record.status` меняется **только внутри `inventory-service`**.
`sales-service` вызывает API, не пишет в чужие таблицы.

---

### ❌ ЗАПРЕТ-05: Не резервируй без распределённой блокировки

Любой код резервирования или продажи места обязан сначала взять Redis-lock:

```kotlin
// ✅ Правильная последовательность в inventory-service
val lockKey = "lock:seat:${inventoryId}:${seatNumber}"
val lock = redis.set(lockKey, reservationId, SetArgs().nx().ex(10))
    ?: throw SeatAlreadyLockedException(seatNumber)

try {
    db.transaction {
        val seat = seatRepo.findForUpdate(inventoryId, seatNumber) // FOR UPDATE NOWAIT
        check(seat.status == SeatStatus.FREE) { "Место уже занято" }
        // ... создать резервирование
    }
} finally {
    // Удалить lock только если он наш (Lua-скрипт для атомарности)
    redisDeleteIfOwner(lockKey, reservationId)
}
```

---

### ❌ ЗАПРЕТ-06: Не открывай hardware-agent наружу

`hardware-agent` слушает **только** `127.0.0.1:9090`. Никогда не `0.0.0.0`:

```yaml
# ❌ ЗАПРЕЩЕНО
agent:
  listen: "0.0.0.0:9090"

# ✅ ПРАВИЛЬНО
agent:
  listen: "127.0.0.1:9090"
```

---

### ❌ ЗАПРЕТ-07: Не откатывай деньги после успешной фискализации

После того как ККТ распечатала чек (`STEP 2` Saga), деньги не откатываются
даже при ошибке принтера билетов. Чек выдан — это фискальный факт.
Если принтер сломался — ждём устранения и повторяем печать:

```
STEP 1: payment_terminal.charge()
  ошибка → PAYMENT_FAILED (деньги не списаны, конец)

STEP 2: fiscal_registrar.printSaleReceipt()
  ошибка → КОМПЕНСАЦИЯ: payment_terminal.refund()
    успех → FISCAL_ERROR (деньги возвращены)
    ошибка → CRITICAL_ALERT + journal.needsManualResolution = true

STEP 3: ticket_printer.printTicket()
  ошибка → НЕ ОТКАТЫВАТЬ — уведомить кассира, повторить печать
```

---

### ❌ ЗАПРЕТ-08: Не продавай без открытой кассовой смены

Каждый эндпоинт продажи или возврата проверяет активную `cashier_shift`:

```kotlin
// ✅ Обязательная проверка в начале sellTicket() и refundTicket()
val shift = shiftRepo.findActive(posId = request.posId)
    ?: throw NoActiveShiftException("Нет открытой смены на кассе ${request.posId}")
```

---

### ❌ ЗАПРЕТ-09: Не отменяй рейс с активными билетами

`trip.status = CANCELLED` недопустим при наличии билетов в статусе `ISSUED`:

```kotlin
// ✅ Проверка в scheduling-service перед отменой рейса
val activeCount = salesClient.countActiveTickets(tripId)
if (activeCount > 0) {
    throw TripCancellationForbiddenException(
        "Рейс содержит $activeCount активных билетов. " +
        "Оформи возвраты или трансфер перед отменой."
    )
}
```

---

### ❌ ЗАПРЕТ-10: Не храни секреты в коде и конфигах репозитория

```kotlin
// ❌ ЗАПРЕЩЕНО
val password = "hardcoded_db_pass"
val apiKey = "ya-speechkit-key-1234"
val privateKey = "-----BEGIN RSA PRIVATE KEY-----\n..."

// ✅ ПРАВИЛЬНО — только через переменные окружения или Secrets
val password = System.getenv("DB_PASSWORD")
val apiKey   = System.getenv("YANDEX_TTS_API_KEY")
// RSA ключи монтируются из K8s Secret как файлы
```

---

### ❌ ЗАПРЕТ-11: Не публикуй NATS-события внутри транзакции БД

```kotlin
// ❌ НЕПРАВИЛЬНО — при откате транзакции событие уже улетело
db.transaction {
    ticketRepo.save(ticket)
    nats.publish("sales.ticket.issued", payload)  // внутри транзакции!
}

// ✅ ПРАВИЛЬНО — публикация только после commit
db.transaction {
    ticketRepo.save(ticket)
}
nats.publish("sales.ticket.issued", payload)  // после commit
```

---

### ❌ ЗАПРЕТ-12: Не давай document-service менять чужие данные

`document-service` — реактивный сервис: только слушает события и генерирует PDF.
Он никогда не пишет в таблицы `sales`, `inventory`, `scheduling`:

```kotlin
// ❌ ЗАПРЕЩЕНО в document-service
salesClient.updateTicketStatus(ticketId, "PRINTED")

// ✅ ПРАВИЛЬНО — document-service только генерирует и сохраняет документ
val pdf = jasperService.generate(template, data)
minioClient.put(filePath, pdf)
documentRepo.save(GeneratedDocument(...))
nats.publish("documents.ticket.ready", ...)
```

---

## 3. Правила межсервисного взаимодействия

### 3.1 REST-клиенты: таймауты обязательны

Любой HTTP-клиент к внешнему сервису обязан иметь явные таймауты:

```kotlin
// ✅ Kotlin / Spring Boot — WebClient с таймаутом
val client = WebClient.builder()
    .baseUrl(inventoryUrl)
    .clientConnector(ReactorClientHttpConnector(
        HttpClient.create()
            .responseTimeout(Duration.ofSeconds(5))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
    ))
    .build()
```

```go
// ✅ Go — http.Client с таймаутом
client := &http.Client{Timeout: 5 * time.Second}

// ✅ Или через context
ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
defer cancel()
resp, err := client.Do(req.WithContext(ctx))
```

### 3.2 Retry только для идемпотентных операций

```kotlin
// ✅ Retry допустим для GET и идемпотентных запросов
retryWhen { it.take(3).delayElements(Duration.ofMillis(500)) }

// ❌ Retry запрещён для POST продажи/резервирования — дублирование!
// Вместо retry — идемпотентный ключ запроса (idempotency key)
```

### 3.3 Idempotency-key для мутирующих операций

Запросы создания билета, резервирования и возврата обязаны поддерживать
idempotency-key, чтобы повтор запроса при таймауте не создавал дубликат:

```kotlin
// ✅ Заголовок Idempotency-Key проверяется перед выполнением операции
@PostMapping("/tickets")
fun sellTicket(
    @RequestHeader("Idempotency-Key") idempotencyKey: String,
    @RequestBody request: SellTicketRequest
): ResponseEntity<TicketResponse> {
    val cached = idempotencyCache.get(idempotencyKey)
    if (cached != null) return ResponseEntity.ok(cached)

    val result = salesService.sellTicket(request)
    idempotencyCache.set(idempotencyKey, result, ttl = Duration.ofMinutes(30))
    return ResponseEntity.ok(result)
}
```

### 3.4 Заголовки от Kong — доверяй, не переопределяй

Заголовки `X-User-ID`, `X-User-Login`, `X-User-Perms`, `X-User-Stations`, `X-Superuser`
устанавливаются Kong после верификации JWT. Никогда не принимай их от клиента напрямую
и не позволяй клиенту их переопределить:

```kotlin
// ✅ Читаем из заголовка, установленного Kong
val userId = request.getHeader("X-User-ID")
    ?: throw UnauthorizedException()

// ❌ НИКОГДА не читай userId из тела запроса или query-параметра
val userId = request.getParameter("userId")  // можно подделать!
```

---

## 4. Правила работы с базами данных

### 4.1 Схемы — строго по сервисам

Каждый сервис работает только со своей схемой. Подключение настраивается с `search_path`:

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/transora?currentSchema=inventory
```

При написании DDL — всегда с явным именем схемы:

```sql
-- ✅ Явная схема
CREATE TABLE inventory.seat_record (...);
CREATE INDEX idx_seat_status ON inventory.seat_record(status);

-- ❌ Без схемы — упадёт или создастся в public
CREATE TABLE seat_record (...);
```

### 4.2 Обязательные соглашения по именованию

| Объект | Стиль | Пример |
|--------|-------|--------|
| Таблицы | `snake_case`, единственное число | `seat_record`, `trip_inventory` |
| Колонки | `snake_case` | `created_at`, `delay_minutes` |
| PK | всегда `id UUID PRIMARY KEY DEFAULT gen_random_uuid()` | |
| Временные метки | `TIMESTAMPTZ` (никогда `TIMESTAMP`) | `created_at`, `expires_at` |
| Enum-типы | `{schema}.{name}` | `inventory.seat_status` |
| Индексы | `idx_{table}_{columns}` | `idx_seat_record_status` |
| Unique constraints | `uq_{table}_{columns}` | `uq_seat_inventory` |
| Check constraints | `chk_{table}_{condition}` | `chk_stop_order_positive` |
| FK constraints | `fk_{table}_{ref_table}` | `fk_ticket_order` |

### 4.3 Обязательные колонки каждой таблицы сущности

```sql
-- Минимум для любой таблицы-сущности:
id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
created_at TIMESTAMPTZ NOT NULL    DEFAULT NOW()

-- Таблицы, которые изменяются:
updated_at TIMESTAMPTZ NOT NULL    DEFAULT NOW()

-- Таблицы с мягким удалением:
is_active  BOOLEAN     NOT NULL    DEFAULT TRUE
```

### 4.4 Аудит-таблицы — только INSERT, никогда UPDATE/DELETE

```sql
-- ✅ Только вставка в журнал
INSERT INTO inventory.inventory_event_log (inventory_id, event_type, actor_id, payload)
VALUES (:inventoryId, :eventType, :actorId, :payload::jsonb);

-- ❌ ЗАПРЕЩЕНО — журнал неизменяем
UPDATE inventory.inventory_event_log SET event_type = 'CORRECTED' WHERE id = ?;
DELETE FROM inventory.inventory_event_log WHERE created_at < NOW() - INTERVAL '1 year';
```

### 4.5 Мягкое удаление — не физическое

```kotlin
// ✅ Деактивация
userRepo.setActive(userId, false)  // UPDATE ... SET is_active = FALSE

// ❌ Физическое удаление бизнес-сущностей
userRepo.deleteById(userId)
```

Исключения: записи с явным TTL (резервирования, истёкшие токены) удаляются фоновым процессом.

### 4.6 SELECT FOR UPDATE NOWAIT — не просто FOR UPDATE

```kotlin
// ✅ NOWAIT — немедленная ошибка вместо зависания
@Query("SELECT * FROM inventory.seat_record WHERE id = :id FOR UPDATE NOWAIT")
fun findForUpdate(id: UUID): SeatRecord

// ❌ Без NOWAIT — поток может висеть секундами при конкурентном доступе
@Query("SELECT * FROM inventory.seat_record WHERE id = :id FOR UPDATE")
fun findForUpdate(id: UUID): SeatRecord
```

### 4.7 Индексы — обязательны для FK и полей WHERE

Каждый внешний ключ и каждое часто используемое поле в `WHERE` обязаны иметь индекс.
Частичные индексы — предпочтительны для статусных полей:

```sql
-- ✅ Частичный индекс — только активные записи
CREATE INDEX idx_reservation_active
    ON inventory.seat_reservation(trip_inventory_id, seat_number)
    WHERE status = 'ACTIVE';

-- ✅ Индекс для фоновой задачи очистки TTL
CREATE INDEX idx_reservation_expires
    ON inventory.seat_reservation(expires_at)
    WHERE status = 'ACTIVE';

-- ✅ Индекс для синхронизации буфера посадки
CREATE INDEX idx_scan_buffer_synced
    ON scan_record(synced)
    WHERE synced = 0;  -- SQLite
```

### 4.8 SQLite в агентах — режимы надёжности

```go
// Некритичные данные (кеш расписания, аудиокеш метаданные)
db, _ = sql.Open("sqlite3",
    path+"?_journal_mode=WAL&_synchronous=NORMAL&_busy_timeout=5000")

// Критичные данные (буфер посадки, журнал операций ККТ)
db, _ = sql.Open("sqlite3",
    path+"?_journal_mode=WAL&_synchronous=FULL&_busy_timeout=5000")
```

---

## 5. Правила NATS JetStream

### 5.1 Карта стримов и их владельцы

| Stream | Subject-маска | Только этот сервис публикует |
|--------|--------------|------------------------------|
| `transora.scheduling` | `scheduling.>` | `scheduling-service` |
| `transora.inventory` | `inventory.>` | `inventory-service` |
| `transora.sales` | `sales.>` | `sales-service` |
| `transora.documents` | `documents.>` | `document-service` |
| `transora.notifications` | `notifications.>` | `notification-service` |
| `transora.iam` | `iam.>` | `iam-service` |

Никакой другой сервис не публикует в чужой стрим.

### 5.2 Обязательный формат конверта события

```json
{
  "event_id":    "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "event_type":  "sales.ticket.issued",
  "occurred_at": "2025-07-15T07:30:00Z",
  "payload": { }
}
```

- `event_id` — UUID v4, уникален глобально
- `occurred_at` — **UTC**, ISO 8601 с `Z` на конце
- `payload` — всегда объект, никогда массив или примитив
- Дополнительные поля: `version` (если меняется схема), `source` (имя сервиса)

### 5.3 Идемпотентность потребителей — обязательна

Любой обработчик события **обязан** быть идемпотентным:

```kotlin
// ✅ Проверка дублей через Redis SET с TTL
@NatsListener(subject = "sales.ticket.issued")
fun onTicketIssued(event: TicketIssuedEvent) {
    // Вариант 1: Redis SET с TTL (дешевле)
    val added = redis.set(
        "processed:${event.eventId}", "1",
        SetArgs().nx().ex(86400)  // 24 часа
    )
    if (added == null) return  // уже обработано

    // Вариант 2: таблица processed_events (для долгого хранения)
    if (processedEvents.exists(event.eventId)) return

    // ... основная логика
}
```

### 5.4 Порядок: commit БД → publish NATS

```kotlin
// ✅ ПРАВИЛЬНО
@Transactional
fun sellTicket(request: SellTicketRequest): Ticket {
    val ticket = ticketRepo.save(buildTicket(request))
    shiftRepo.incrementSales(request.posId)
    return ticket
    // Транзакция здесь закрывается (commit)
}

// Публикация — ВНЕ транзакции, после commit
fun sellTicketAndNotify(request: SellTicketRequest): Ticket {
    val ticket = sellTicket(request)        // <- commit здесь
    nats.publish("sales.ticket.issued", TicketIssuedPayload(ticket))
    return ticket
}
```

### 5.5 Ключевые события системы (справочник)

```
scheduling-service публикует:
  scheduling.trip.created          → inventory (создать инвентарь)
  scheduling.trip.status_changed   → notification (обновить табло)
  scheduling.trip.delay_updated    → notification (объявление о задержке)
  scheduling.trip.cancelled        → inventory, notification

inventory-service публикует:
  inventory.transit_gate.opened    → notification (объявление о транзите)
  inventory.transit_gate.closed    → notification
  inventory.seat.blocked           → (аудит)
  inventory.seat.released          → (аудит)

sales-service публикует:
  sales.ticket.issued              → document (генерация PDF)
  sales.ticket.refunded            → document (void PDF), inventory (освободить)
  sales.boarding.started           → document (манифест + ведомость)
  sales.shift.closed               → document (отчёт смены)

document-service публикует:
  documents.ticket.ready           → cashier-app (печать)
  documents.trip_docs.ready        → dispatcher-app

notification-service публикует:
  notifications.announcement.played  → (аудит)
  notifications.announcement.failed  → (алерты)

iam-service публикует:
  iam.user.deactivated             → (все: инвалидация сессий)
  iam.token.revoked                → Kong (blacklist)
```

---

## 6. Правила авторизации и безопасности

### 6.1 Проверка разрешений — на каждом мутирующем эндпоинте

```kotlin
// ✅ Через аннотацию (предпочтительно)
@PostMapping("/tickets")
@RequirePermission("tickets:sell")
fun sellTicket(...): ResponseEntity<TicketResponse>

// ✅ Или явно, если аннотация недостаточна
val perms = request.getHeader("X-User-Perms")?.split(",") ?: emptyList()
if ("tickets:sell" !in perms) throw AccessDeniedException("Требуется: tickets:sell")
```

Матрица разрешений — в `transora-iam-service.md`, раздел 4.2.

### 6.2 Проверка принадлежности к вокзалу

```kotlin
// ✅ Обязательная проверка для любой операции на конкретном вокзале
val userStations = request.getHeader("X-User-Stations")?.split(",") ?: emptyList()
val isSuperuser  = request.getHeader("X-Superuser") == "true"

if (!isSuperuser && requestedStationId !in userStations) {
    throw StationAccessDeniedException(
        "Нет доступа к вокзалу $requestedStationId"
    )
}
```

### 6.3 Пароли — bcrypt, cost 12, ничего другого

```kotlin
// ✅ ЕДИНСТВЕННЫЙ допустимый способ хранения паролей
val hash = BCrypt.hashpw(password, BCrypt.gensalt(12))
val ok   = BCrypt.checkpw(inputPassword, storedHash)

// ❌ ЗАПРЕЩЕНО — любые другие алгоритмы
MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
password.hashCode()
```

### 6.4 JWT — только RS256, не HS256

```kotlin
// ✅ iam-service: подписывает приватным ключом
val algorithm = Algorithm.RSA256(rsaPublicKey, rsaPrivateKey)

// ✅ Все остальные сервисы: только верифицируют публичным ключом
val algorithm = Algorithm.RSA256(rsaPublicKey, null)
// Публичный ключ получается с GET /auth/jwks.json при старте

// ❌ ЗАПРЕЩЕНО — HS256 позволяет любому сервису выпускать токены
val algorithm = Algorithm.HMAC256("shared-secret")
```

### 6.5 Blacklist токенов — через Redis, не БД

```kotlin
// ✅ Отзыв — в Redis с TTL до истечения токена
val remainingTtl = token.expiresAt.epochSecond - Instant.now().epochSecond
redis.set("token_blacklist:${token.jti}", "1", SetArgs().ex(remainingTtl))

// ✅ Проверка — в Kong (не в сервисах), Kong читает Redis
// Сервисы доверяют Kong — если запрос прошёл Kong, токен не отозван
```

### 6.6 Brute-force защита — Redis-счётчик с TTL

```kotlin
// ✅ Инкремент + TTL, не в PostgreSQL
val key = "login_attempts:${login}"
val attempts = redis.incr(key).also { redis.expire(key, 900) }

if (attempts >= 5) throw AccountLockedException(retryAfterSec = 900)
```

### 6.7 ServiceToken — показывать значение только при создании

```kotlin
// ✅ Значение токена показывается один раз, в БД только хеш
val tokenValue = generateSecureToken()          // 32 случайных байта
val tokenHash  = sha256hex(tokenValue)          // хранить в БД

serviceTokenRepo.save(ServiceToken(hash = tokenHash, ...))
return ServiceTokenResponse(tokenId = id, tokenValue = tokenValue)
// tokenValue больше нигде не возвращается — даже в GET /service-tokens
```

---

## 7. Правила фискальных операций

### 7.1 Saga — полная схема с компенсацией

Весь код, выполняющий продажу или возврат через `hardware-agent`, должен
строго следовать Saga-паттерну. Никаких «а если упадёт — ничего страшного».

```go
// ✅ Полная Saga продажи в hardware-agent
func (h *HardwareAgent) ProcessSale(req SaleRequest) (*SaleResult, error) {

    // STEP 1: Банковский терминал
    payment, err := h.terminal.Charge(ctx, ChargeRequest{Amount: req.Amount})
    if err != nil {
        return nil, ErrPaymentFailed{Cause: err}
    }

    // STEP 2: Фискальный регистратор
    receipt, err := h.fiscal.PrintSaleReceipt(ctx, buildReceiptRequest(req, payment))
    if err != nil {
        // !! КОМПЕНСАЦИЯ: возвращаем деньги
        if refErr := h.terminal.Refund(ctx, RefundRequest{
            OriginalTransactionID: payment.TransactionID,
            Amount:                req.Amount,
        }); refErr != nil {
            // КРИТИЧНО: деньги списаны, чек не выдан, возврат не прошёл
            h.journal.LogCritical(JournalEntry{
                Operation:             "SALE_COMPENSATION_FAILED",
                NeedsManualResolution: true,
                PaymentTransactionID:  payment.TransactionID,
            })
            h.alerts.SendCriticalAlert(...)
            return nil, ErrCompensationFailed{}
        }
        return nil, ErrFiscalFailed{RefundDone: true}
    }

    // STEP 3: Принтер билетов
    if err := h.printer.PrintTicket(ctx, req.TicketData); err != nil {
        // НЕ откатываем деньги — чек выдан, это фискальный факт
        return &SaleResult{
            FiscalReceipt: receipt,
            PrintError:    err,
            NeedReprint:   true,
        }, nil
    }

    return &SaleResult{FiscalReceipt: receipt}, nil
}
```

### 7.2 Незавершённые транзакции — восстановление при старте

```go
// ✅ При каждом запуске hardware-agent проверяет незавершённые операции
func (a *Agent) RecoverOnStart(ctx context.Context) {
    pending := a.journal.FindPendingTransactions()
    for _, entry := range pending {
        switch entry.Operation {
        case "PAYMENT_CHARGE":
            result, err := a.terminal.CheckLastTransaction(ctx)
            if err == nil && result.Status == "APPROVED" {
                a.journal.MarkResolved(entry.ID, result)
            } else {
                a.journal.MarkNeedsManual(entry.ID, "Статус неизвестен")
            }
        }
    }
}
```

### 7.3 Смена ФР — автоматическое закрытие

```go
// ✅ Периодическая проверка срока смены
func (d *ATOLDriver) CheckShiftExpiry(ctx context.Context) {
    shift, _ := d.GetShiftInfo(ctx)
    if shift.Status != "OPEN" {
        return
    }
    elapsed   := time.Since(shift.OpenedAt)
    remaining := 24*time.Hour - elapsed

    if remaining < 30*time.Minute {
        d.notifyShiftExpiringSoon(remaining)  // предупреждение кассиру
    }
    if remaining <= 0 {
        d.CloseShift(ctx)  // принудительное закрытие — требование ФЗ
    }
}
```

---

## 8. Правила офлайн-устойчивости

### 8.1 Три режима station-agent — проверяй всегда

```go
// ✅ Каждый обработчик запроса проверяет режим
func (r *Router) handleSalesRequest(w http.ResponseWriter, req *http.Request) {
    switch r.mode.Current() {
    case ModeOnline:
        r.proxy.ServeHTTP(w, req)
    case ModeDegraded:
        time.Sleep(5 * time.Second)
        if r.mode.Current() == ModeOnline {
            r.proxy.ServeHTTP(w, req)
        } else {
            writeError(w, "STATION_DEGRADED", 503)
        }
    case ModeOffline:
        writeError(w, "STATION_OFFLINE", 503)
    }
}
```

### 8.2 Матрица доступности при потере связи

| Операция | ONLINE | DEGRADED | OFFLINE |
|----------|----|---|----|
| Расписание (чтение) | ✅ ядро | ✅ кеш | ✅ кеш |
| Табло | ✅ актуально | ✅ кеш | ✅ кеш + баннер |
| Аудио-объявления | ✅ | ✅ MP3-кеш | ✅ MP3-кеш |
| Сканирование ТСД | ✅ онлайн | ✅ буфер | ✅ буфер |
| Продажа билетов | ✅ | ⏳ 5 сек → 503 | ❌ 503 |
| Возврат билетов | ✅ | ⏳ 5 сек → 503 | ❌ 503 |
| Управление рейсами | ✅ | ⏳ 5 сек → 503 | ❌ 503 |

### 8.3 Буферизация посадки — критичные данные

```go
// ✅ SQLite с FULL synchronous — критичные данные не теряются
db, _ = sql.Open("sqlite3",
    boardingBufferPath+"?_journal_mode=WAL&_synchronous=FULL")

// ✅ Flush пакетами, markSynced только после успеха
func (b *BoardingBuffer) FlushToCore(ctx context.Context, client CoreClient) error {
    batch, ids := b.getPendingBatch(100)
    if len(batch) == 0 {
        return nil
    }
    if err := client.SendBoardingEvents(ctx, batch); err != nil {
        return err  // НЕ помечаем synced, если ошибка
    }
    b.markSynced(ids)
    return nil
}
```

### 8.4 Reconnect с exponential backoff + jitter

```go
// ✅ Обязательный шаблон для любого соединения с удалённым сервисом
func reconnectLoop(ctx context.Context, connect func() error, run func()) {
    backoff := 1 * time.Second
    const maxBackoff = 60 * time.Second

    for {
        if ctx.Err() != nil {
            return
        }
        if err := connect(); err != nil {
            jitter := time.Duration(rand.Int63n(int64(backoff / 4)))
            time.Sleep(backoff + jitter)
            backoff = min(backoff*2, maxBackoff)
            continue
        }
        backoff = 1 * time.Second  // Сброс при успехе
        run()                       // Блокирует до потери соединения
    }
}
```

### 8.5 Аудиокеш — детерминированный ключ

```go
// ✅ Ключ = sha256(text + "|" + voiceID + "|" + format)
func audioKey(text, voiceID, format string) string {
    h := sha256.Sum256([]byte(text + "|" + voiceID + "|" + format))
    return hex.EncodeToString(h[:])
}

// ❌ Никогда не добавляй в ключ время или случайные данные
func audioKeyWrong(text string) string {
    return fmt.Sprintf("%s_%d", text, time.Now().Unix())  // кеш не работает
}
```

### 8.6 TTS Fallback — логировать явно

```go
// ✅ Fallback с явным логом и флагом is_fallback в кеше
audio, err := yandexTTS.Synthesize(ctx, text, voiceID)
if err != nil {
    log.Warn("Yandex SpeechKit недоступен, переключаемся на RHVoice", "error", err)
    audio, err = rhvoice.Synthesize(text, voiceID)
    if err != nil {
        return nil, fmt.Errorf("все TTS провайдеры недоступны: %w", err)
    }
    saveToCache(audio, isFallback: true)
    return audio, nil
}
saveToCache(audio, isFallback: false)
```

---

## 9. Правила написания кода

### 9.1 Kotlin / Spring Boot — обязательные соглашения

**Структура пакетов:**
```
com.transora.{service}/
├── api/              ← REST контроллеры, DTO запросов/ответов
├── domain/           ← Бизнес-логика, use-case, domain objects
├── infrastructure/
│   ├── db/           ← JPA entities, репозитории
│   ├── nats/         ← Publisher, Consumer, message types
│   └── external/     ← HTTP клиенты к другим сервисам
└── config/           ← Spring конфигурация, beans
```

**Транзакции — только на сервисном слое:**
```kotlin
// ✅ @Transactional на сервисе, не на контроллере
@Service
class SalesService(private val ticketRepo: TicketRepo) {
    @Transactional
    fun sellTicket(request: SellTicketRequest): Ticket {
        return ticketRepo.save(buildTicket(request))
        // commit при выходе из метода
    }
}

// ❌ @Transactional на контроллере — антипаттерн
@RestController
class SalesController {
    @Transactional  // ❌
    @PostMapping("/tickets")
    fun sellTicket(...) { ... }
}
```

**Ошибки — специфичные исключения с HTTP-кодом:**
```kotlin
// ✅ Специфичное исключение — понятно из кода что произошло
class SeatAlreadyReservedException(seatNumber: Int) :
    ResponseStatusException(HttpStatus.CONFLICT,
        "Место $seatNumber уже зарезервировано")

class NoActiveShiftException(posId: String) :
    ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Нет открытой кассовой смены на кассе $posId")

// ❌ Общие исключения без контекста
throw RuntimeException("error")
```

**Health checks — обязательно для K8s:**
```yaml
# application.yml — в каждом Kotlin-сервисе
management:
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.probes.enabled: true
  # Откроет: /actuator/health/liveness и /actuator/health/readiness
```

### 9.2 Go — обязательные соглашения

**Структура:**
```
cmd/{service}/main.go     ← только инициализация и запуск
internal/
├── api/                  ← HTTP handlers, middleware
├── core/                 ← бизнес-логика (без зависимости от фреймворка)
├── storage/              ← БД, SQLite, Redis
└── config/               ← загрузка конфигурации
```

**Ошибки — всегда оборачивай с контекстом:**
```go
// ✅ Контекст помогает при отладке
if err := db.QueryRow(q, id).Scan(&result); err != nil {
    return nil, fmt.Errorf("getPassenger(id=%s): %w", id, err)
}

// ❌ Потеря контекста или игнорирование
db.QueryRow(q, id).Scan(&result)  // потеря ошибки
```

**Горутины — всегда через context:**
```go
// ✅ errgroup для группы горутин с единым контекстом
func (a *Agent) Start(ctx context.Context) {
    g, ctx := errgroup.WithContext(ctx)
    g.Go(func() error { return a.runWebSocket(ctx) })
    g.Go(func() error { return a.processQueue(ctx) })
    g.Go(func() error { return a.healthCheckLoop(ctx) })
    g.Wait()
}
```

**Mutex — минимальный scope:**
```go
// ✅ Освобождаем lock до вызова внешних функций
func (q *Queue) Enqueue(cmd *PlayCommand) {
    q.mu.Lock()
    q.items = append(q.items, cmd)
    q.mu.Unlock()        // НЕ defer — чтобы не держать lock во время triggerProcess

    q.triggerProcess()   // вызов без lock
}
```

**HTTP сервер — graceful shutdown:**
```go
// ✅ Graceful shutdown при SIGTERM/SIGINT (для K8s)
srv := &http.Server{Addr: cfg.Listen, Handler: router}
go func() {
    if err := srv.ListenAndServe(); err != http.ErrServerClosed {
        log.Fatal("HTTP server error", "error", err)
    }
}()
<-ctx.Done()
shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
defer cancel()
srv.Shutdown(shutdownCtx)
```

### 9.3 Android / Kotlin (boarding-app) — обязательные соглашения

- Вся работа с сетью и БД — в `Dispatchers.IO`, не в `Main`
- ViewModel не знает об Android-фреймворке: нет `Context`, нет `Activity`
- `ScanStatus` проверяется **только локально по Room** — никогда не ждать сети
- `SyncService` использует `debounce(500)` чтобы не спамить при серии сканирований
- `markSynced` только после успешного ответа сервера

**Сканер — поддержка обоих режимов:**
```kotlin
// ✅ Аппаратный сканер ТСД через BroadcastReceiver
class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val barcode = when (intent.action) {
            "com.symbol.datawedge.api.RESULT_ACTION" ->  // Zebra
                intent.getStringExtra("com.symbol.datawedge.data_string")
            "com.honeywell.aidc.action.ACTION_DECODE" ->  // Honeywell
                intent.getStringExtra("version1")
            "transora.SCAN_RESULT" ->  // Универсальный
                intent.getStringExtra("barcode_data")
            else -> null
        }
        barcode?.let { onBarcodeReceived(it) }
    }
}
// ✅ ML Kit для камеры смартфона (резервный режим)
```

---

## 10. Правила схем БД и миграций

### 10.1 Только через Flyway — никогда вручную

```kotlin
// application.yml
spring.flyway:
  enabled: true
  locations: classpath:db/migration
  schemas: ${DB_SCHEMA}
  validate-on-migrate: true
  # ❌ НИКОГДА:
  # spring.jpa.hibernate.ddl-auto: create-drop
  # spring.jpa.hibernate.ddl-auto: update
```

Имена файлов: `V{N}__{описание}.sql` (два подчёркивания):
```
V1__initial_schema.sql
V2__add_delay_minutes_to_trip.sql
V3__add_seat_block_table.sql
```

### 10.2 Безопасные vs опасные изменения

```sql
-- ✅ БЕЗОПАСНО — добавить колонку с DEFAULT
ALTER TABLE scheduling.trip ADD COLUMN delay_minutes INTEGER NOT NULL DEFAULT 0;

-- ✅ БЕЗОПАСНО — добавить индекс без блокировки
CREATE INDEX CONCURRENTLY idx_trip_delay
    ON scheduling.trip(delay_minutes) WHERE delay_minutes > 0;

-- ✅ БЕЗОПАСНО — добавить новую таблицу
CREATE TABLE inventory.seat_block (...);

-- ✅ БЕЗОПАСНО — добавить значение в enum
ALTER TYPE inventory.seat_status ADD VALUE 'PENDING_TRANSFER';

-- ⚠️ ОСТОРОЖНО — переименование колонки: two-phase deploy
-- ⚠️ ОСТОРОЖНО — удаление колонки: сначала убедись что код не читает её

-- ❌ ОПАСНО — изменение типа колонки без явного CAST
```

### 10.3 Enum — только добавление, не удаление

```sql
-- ✅ Добавление — безопасно
ALTER TYPE inventory.seat_status ADD VALUE 'PENDING_TRANSFER';

-- ❌ Удаление — невозможно без пересоздания типа
-- Устаревшее значение помечай комментарием, не удаляй
```

### 10.4 Foreign keys — без CASCADE DELETE по умолчанию

```sql
-- ✅ По умолчанию — без CASCADE
-- При попытке удалить родителя получим явную ошибку
FOREIGN KEY (trip_id) REFERENCES scheduling.trip(id)

-- ⚠️ CASCADE только при явном бизнес-требовании "удалить вместе с родителем"
```

---

## 11. Правила тестирования

### 11.1 Обязательные тест-сценарии по модулям

| Модуль | Что обязательно протестировать |
|--------|-------------------------------|
| `inventory-service` | Двойное резервирование → 409 CONFLICT |
| `inventory-service` | Резервирование после истечения TTL → ошибка |
| `inventory-service` | Продажа при закрытом TransitGate → ошибка |
| `inventory-service` | Блокировка снимается автоматически по TTL |
| `sales-service` | Продажа без активной смены → UNPROCESSABLE_ENTITY |
| `sales-service` | Возврат после отправления рейса → ошибка |
| `sales-service` | Saga: ошибка ФР → возврат платежа выполнен |
| `sales-service` | Saga: ошибка компенсации → journal.needsManualResolution = true |
| `iam-service` | 5 неудачных входов → 429 ACCOUNT_LOCKED |
| `iam-service` | Повторное использование RefreshToken → 401 |
| `iam-service` | Деактивация пользователя → все токены в blacklist |
| `scheduling-service` | Отмена рейса с ISSUED-билетами → ошибка |
| `boarding-app` | Повторное сканирование → ALREADY_USED (из Room, без сети) |
| `station-agent` | Переход в OFFLINE → продажи блокируются (503) |
| `station-agent` | Восстановление связи → буфер посадки синхронизируется |
| `notification-service` | Дублирование объявления рейса → игнорируется |

### 11.2 Интеграционные тесты — Testcontainers

```kotlin
// ✅ Реальные зависимости в тестах, не моки для БД и Redis
@SpringBootTest
@Testcontainers
class InventoryServiceIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("transora")
            .withInitScript("db/init-schemas.sql")

        @Container
        val redis = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)
    }

    @DynamicPropertySource
    @JvmStatic
    fun configure(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.redis.host") { redis.host }
        registry.add("spring.redis.port") { redis.getMappedPort(6379) }
    }
}
```

### 11.3 Тест на идемпотентность NATS-потребителей

```kotlin
@Test
fun `повторная обработка события не создаёт дубликаты инвентаря`() {
    val event = TripCreatedEvent(eventId = UUID.randomUUID().toString(), tripId = "trip-001")

    handler.handle(event)
    handler.handle(event)  // второй раз — то же событие

    val inventories = inventoryRepo.findByTripId("trip-001")
    assertThat(inventories).hasSize(1)  // не два!
}
```

### 11.4 Тест на конкурентное резервирование

```kotlin
@Test
fun `конкурентное резервирование одного места — только одна транзакция успешна`() {
    val results = (1..10).map { i ->
        async(Dispatchers.IO) {
            runCatching {
                inventoryService.reserveSeat(inventoryId = "inv-001", seatNumber = 5)
            }
        }
    }.awaitAll()

    assertThat(results.count { it.isSuccess }).isEqualTo(1)
    assertThat(results.count { it.isFailure }).isEqualTo(9)
}
```

### 11.5 Тест на TTL и автоочистку

```kotlin
@Test
fun `истёкшее резервирование автоматически освобождает место`() {
    inventoryService.reserveSeat(inventoryId = "inv-001", seatNumber = 7, ttlSeconds = 1)
    assertThat(seatRepo.getStatus(7)).isEqualTo(SeatStatus.RESERVED)

    Thread.sleep(2000)
    cleanupJob.runNow()  // явный вызов фонового процесса

    assertThat(seatRepo.getStatus(7)).isEqualTo(SeatStatus.FREE)
}
```

---

## 12. Доменный глоссарий

### 12.1 Ключевые термины — не путай

| Термин | Определение | Не путать с |
|--------|------------|-------------|
| `trip` | Конкретный рейс на конкретную дату | `schedule_entry` — шаблон расписания |
| `route` | Маршрут с набором остановок | `trip` — конкретная поездка по маршруту |
| `route_stop` | Остановка на маршруте (с порядком и временем) | `service_station` — вокзал системы |
| `service_station` | Вокзал, подключённый к Transora | `route_stop` — остановка на маршруте |
| `segment` | Участок маршрута между двумя остановками | `route` — весь маршрут |
| `reservation` | Временная блокировка места (TTL 10 мин) | `seat_block` — постоянная ручная блокировка |
| `trip_inventory` | Инвентарь мест для конкретного рейса | `reservation` — резервирование одного места |
| `transit_gate` | Управление продажами транзитного рейса | `boarding_session` — физическая посадка |
| `order` | Один заказ, содержит 1–10 билетов | `ticket` — один билет одного пассажира |
| `cashier_shift` | Кассовая смена (по `pos_id`) | `boarding_session` — сессия посадки на ТСД |
| `pos_id` | Физическое кассовое место | `cashier_id` / `user_id` — кассир |
| `station_assignment` | Привязка пользователя к вокзалу с ролью | `role` — сам набор разрешений |
| `service_token` | Токен для системных компонентов (агентов) | `refresh_token` — токен человека |

### 12.2 Статусы билета и допустимые переходы

```
PENDING ──► ISSUED ──► USED
                  └──► VOIDED
```

| Статус | Когда | Может перейти в |
|--------|-------|----------------|
| `PENDING` | Оплата в процессе | `ISSUED` (успех), `VOIDED` (отмена) |
| `ISSUED` | Чек выдан, билет действителен | `USED` (посадка), `VOIDED` (возврат) |
| `USED` | Пассажир прошёл посадку | Финальный |
| `VOIDED` | Возврат оформлен | Финальный |

Манифест рейса включает: `ISSUED` + `USED`. Возврат возможен только из `ISSUED`.

### 12.3 Статусы рейса и допустимые переходы

```
SCHEDULED ──► OPEN (продажи) ──► BOARDING (посадка) ──► DEPARTED ──► COMPLETED
     │
     └──► CANCELLED (только без ISSUED-билетов)

DELAYED — атрибут (delay_minutes > 0), не отдельный статус
```

### 12.4 Статусы места

```
FREE ──► RESERVED (TTL 10 мин) ──► SOLD
  ▲           │
  └───────────┘  (истёк TTL — автоосвобождение)

FREE ──► BLOCKED (ручная блокировка) ──► FREE (разблокировка)
```

### 12.5 Когда использовать события, а когда REST

**Событие NATS** — когда несколько получателей реагируют на одно изменение,
источник не зависит от результата обработки, или нужна асинхронная обработка.

**Синхронный REST** — когда нужен ответ для продолжения операции
(резервирование мест перед продажей), затрагивает только два сервиса,
нужна строгая согласованность прямо сейчас.

---

## 13. Справочник: куда смотреть при ошибке

### 13.1 По типу проблемы

| Ситуация | Где искать |
|---------|-----------|
| Двойная продажа мест | `transora-inventory-service.md` → §6 (distributed lock) |
| Ошибка фискализации / компенсации | `transora-hardware-agent.md` → §7 (Saga) |
| Деньги списаны, билет не выдан | `transora-hardware-agent.md` → §12 (journal.db) |
| Ошибка авторизации 401/403 | `transora-iam-service.md` → §7 (токены) |
| Вокзал потерял связь | `transora-station-agent.md` → §8 (offline-режим) |
| Объявления не воспроизводятся | `transora-notification-service.md` → §7 (PlaybackAgent) |
| Манифест не обновился | `transora-document-service.md` → §6 (manifest_stale) |
| Кассир не может открыть смену | `transora-sales-service.md` → §5 (cashier_shift) |
| Табло не обновляется | `transora-notification-service.md` → §6 (WebSocket) |
| ТСД принимает использованный билет | `transora-boarding-app.md` → §7 (локальный кеш) |
| K8s под не стартует | `transora-deployment.md` → §8 (порядок запуска) |

### 13.2 По модулю — ключевые разделы для чтения

| Модуль | Документ | Разделы |
|--------|----------|---------| 
| Продажа билетов | `transora-sales-service.md` | §6 Процесс продажи · §7 Возврат |
| Инвентарь и блокировки | `transora-inventory-service.md` | §6 Резервирование · §7 TransitGate |
| Расписание и рейсы | `transora-scheduling-service.md` | §6 Рейсы · §8 События |
| ККТ, терминал, принтер | `transora-hardware-agent.md` | §6 ККТ · §7 Терминал · §7.3 Saga |
| Авторизация, роли | `transora-iam-service.md` | §4 Матрица ролей · §7 Токены |
| Генерация документов | `transora-document-service.md` | §6 Процессы · §8 Аннулирование |
| Табло и объявления | `transora-notification-service.md` | §6 Табло · §7 Аудио |
| Офлайн и кеш | `transora-station-agent.md` | §5 Кеш · §8 Офлайн |
| Посадка пассажиров | `transora-boarding-app.md` | §7 Сканирование · §8 Офлайн |
| Развёртывание | `transora-deployment.md` | §3 Docker Compose · §8 Порядок запуска |
