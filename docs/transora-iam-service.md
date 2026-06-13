# Transora — IAM Service
## Сервис авторизации, ролей и управления доступом: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `iam-service`

---

## Содержание

1. [Назначение сервиса](#1-назначение-сервиса)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Бизнес-правила и ограничения](#3-бизнес-правила-и-ограничения)
4. [Модель ролей и разрешений](#4-модель-ролей-и-разрешений)
5. [Модель данных (ERD)](#5-модель-данных-erd)
6. [Схемы таблиц БД](#6-схемы-таблиц-бд)
7. [Аутентификация и токены](#7-аутентификация-и-токены)
8. [Авторизация запросов (Kong)](#8-авторизация-запросов-kong)
9. [HTTP API сервиса](#9-http-api-сервиса)
10. [Примеры данных](#10-примеры-данных)
11. [События (NATS JetStream)](#11-события-nats-jetstream)
12. [Зависимости и взаимодействие](#12-зависимости-и-взаимодействие)

---

## 1. Назначение сервиса

`iam-service` (Identity and Access Management) отвечает за управление
пользователями, ролями и разрешениями в системе Transora, а также за
аутентификацию и выдачу токенов доступа.

Основные обязанности:

- Регистрация и управление учётными записями пользователей
- Определение ролей и разрешений в системе
- Привязка пользователей к вокзалам и ролям
- Аутентификация: проверка учётных данных и выдача JWT-токенов
- Обновление и отзыв токенов
- Выдача токенов для системных компонентов (station-agent, hardware-agent)
- Аудит действий с учётными записями

Сервис является **точкой входа** для всех пользователей системы. Все остальные
сервисы доверяют JWT-токенам, выданным этим сервисом, и проверяют их через
Kong API Gateway или напрямую по публичному ключу.

**Стек:** Kotlin + Spring Boot + PostgreSQL + Redis + NATS JetStream

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **User** (Пользователь) | Учётная запись человека или системного компонента |
| **Role** (Роль) | Набор разрешений. Назначается пользователю в контексте вокзала. |
| **Permission** (Разрешение) | Атомарное право на выполнение действия: `resource:action` |
| **StationAssignment** | Привязка пользователя к вокзалу с конкретной ролью |
| **AccessToken** | Краткосрочный JWT-токен (15 минут) для аутентификации запросов |
| **RefreshToken** | Долгосрочный токен (30 дней) для обновления AccessToken |
| **ServiceToken** | Долгосрочный токен для системных компонентов (station-agent и т.д.) |
| **TokenBlacklist** | Список отозванных токенов до истечения срока (Redis) |
| **Scope** | Область действия токена: `user` или `service` |

---

## 3. Бизнес-правила и ограничения

### 3.1 Пользователи

- **BR-IAM-001:** Логин пользователя уникален в рамках всей системы.
  Минимальная длина — 3 символа, только латинские буквы, цифры и `_`.
- **BR-IAM-002:** Пароль хранится в виде bcrypt-хеша (cost factor 12).
  Минимальная длина пароля — 8 символов.
- **BR-IAM-003:** После 5 неудачных попыток входа подряд учётная запись
  блокируется на 15 минут. Счётчик хранится в Redis с TTL.
- **BR-IAM-004:** Пользователь может быть деактивирован (`is_active = false`)
  без удаления. Деактивация немедленно инвалидирует все активные токены.
- **BR-IAM-005:** Системные учётные записи (station-agent, hardware-agent)
  имеют тип `SERVICE` и не могут авторизоваться через форму логина.
- **BR-IAM-006:** Суперпользователь (`is_superuser = true`) имеет доступ ко всем
  ресурсам всех вокзалов без явного назначения. Такая учётная запись должна быть
  только одна и создаётся при инициализации системы.

### 3.2 Роли и разрешения

- **BR-IAM-010:** Роли бывают двух видов: системные (`SYSTEM_ROLE`) — встроены
  в систему и не изменяются, и пользовательские (`CUSTOM_ROLE`) — создаются
  администратором.
- **BR-IAM-011:** Разрешения задаются строкой вида `resource:action`.
  Например: `tickets:sell`, `schedule:edit`, `reports:view`.
- **BR-IAM-012:** Роль — это именованный набор разрешений. Пользователю
  назначается роль, а не разрешения напрямую.
- **BR-IAM-013:** Один пользователь может иметь разные роли на разных вокзалах.
  Например, кассир на вокзале A и диспетчер на вокзале B.
- **BR-IAM-014:** Пользователь с ролью `SYSTEM_ADMIN` имеет доступ
  ко всем вокзалам сети.

### 3.3 Токены

- **BR-IAM-020:** AccessToken живёт 15 минут. RefreshToken — 30 дней.
- **BR-IAM-021:** RefreshToken хранится в БД в хешированном виде.
  При использовании старый RefreshToken инвалидируется, выдаётся новый.
- **BR-IAM-022:** При выходе (`logout`) AccessToken помещается в blacklist
  в Redis на оставшееся время жизни. RefreshToken удаляется из БД.
- **BR-IAM-023:** ServiceToken не имеет срока истечения, но может быть
  отозван администратором. Факт отзыва хранится в Redis.
- **BR-IAM-024:** JWT подписывается алгоритмом RS256 (асимметричный).
  Публичный ключ доступен через `GET /auth/jwks.json` для верификации
  другими сервисами.
- **BR-IAM-025:** В payload JWT включаются: `sub` (user_id), `login`,
  `roles` (массив), `stations` (массив station_id), `scope`, `type`
  (ACCESS / REFRESH / SERVICE), `iat`, `exp`.

---

## 4. Модель ролей и разрешений

### 4.1 Системные роли

| Роль | Код | Описание |
|------|-----|---------|
| Системный администратор | `SYSTEM_ADMIN` | Полный доступ ко всей системе |
| Администратор вокзала | `STATION_ADMIN` | Управление пользователями и настройками своего вокзала |
| Диспетчер | `DISPATCHER` | Управление рейсами, объявлениями и транзитными операциями |
| Кассир | `CASHIER` | Продажа и возврат билетов |
| Контролёр | `INSPECTOR` | Учёт посадки (boarding-app) |
| Агент вокзала | `STATION_AGENT` | Системная роль для station-agent процесса |

### 4.2 Матрица разрешений по ролям

```
Разрешение                    │ SYS_ADMIN │ STN_ADMIN │ DISPATCHER │ CASHIER │ INSPECTOR │ AGENT
──────────────────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
── Пользователи ──────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
users:view                    │     ✅    │     ✅    │            │         │           │
users:create                  │     ✅    │     ✅    │            │         │           │
users:edit                    │     ✅    │     ✅    │            │         │           │
users:deactivate              │     ✅    │     ✅    │            │         │           │
── Расписание ────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
schedule:view                 │     ✅    │     ✅    │     ✅     │   ✅    │    ✅     │  ✅
schedule:create               │     ✅    │           │     ✅     │         │           │
schedule:edit                 │     ✅    │           │     ✅     │         │           │
schedule:cancel_trip          │     ✅    │           │     ✅     │         │           │
schedule:update_actual_times  │     ✅    │           │     ✅     │         │           │
── Инвентарь ─────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
inventory:view                │     ✅    │     ✅    │     ✅     │   ✅    │           │  ✅
inventory:manage_restrictions │     ✅    │           │            │         │           │
inventory:toggle_restriction  │     ✅    │           │     ✅     │         │           │
inventory:manual_block        │     ✅    │           │     ✅     │         │           │
inventory:open_transit_gate   │     ✅    │           │     ✅     │         │           │
inventory:close_transit_gate  │     ✅    │           │     ✅     │         │           │
── Продажи ───────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
tickets:sell                  │     ✅    │           │            │   ✅    │           │
tickets:refund                │     ✅    │           │            │   ✅    │           │
tickets:view                  │     ✅    │     ✅    │     ✅     │   ✅    │           │
shifts:manage                 │     ✅    │     ✅    │            │   ✅    │           │
── Документы ─────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
documents:view_manifest       │     ✅    │     ✅    │     ✅     │         │           │
documents:view_boarding_sheet │     ✅    │     ✅    │     ✅     │         │           │
documents:view_carrier_report │     ✅    │     ✅    │            │         │           │
documents:print               │     ✅    │           │     ✅     │   ✅    │           │
── Посадка ───────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
boarding:scan                 │     ✅    │           │            │         │    ✅     │
boarding:view_stats           │     ✅    │     ✅    │     ✅     │         │    ✅     │
── Объявления ────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
announcements:view_queue      │     ✅    │     ✅    │     ✅     │         │           │
announcements:manage_queue    │     ✅    │           │     ✅     │         │           │
── Отчётность ────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
reports:view_station          │     ✅    │     ✅    │     ✅     │         │           │
reports:view_network          │     ✅    │           │            │         │           │
── Настройки ─────────────────┼───────────┼───────────┼────────────┼─────────┼───────────┼──────
settings:manage_tariffs       │     ✅    │           │            │         │           │
settings:manage_stations      │     ✅    │           │            │         │           │
settings:manage_carriers      │     ✅    │           │            │         │           │
```

---

## 5. Модель данных (ERD)

```
┌─────────────────────┐
│        user         │
│─────────────────────│
│ id (PK)             │
│ login               │◄── UNIQUE
│ password_hash       │
│ full_name           │
│ email               │
│ phone               │
│ user_type           │  USER | SERVICE
│ is_active           │
│ is_superuser        │
│ last_login_at       │
│ created_at          │
│ updated_at          │
└──────────┬──────────┘
           │ 1:N
           │
┌──────────▼──────────┐        ┌─────────────────────┐
│  station_assignment  │        │        role         │
│─────────────────────│   N:1  │─────────────────────│
│ id (PK)              │───────►│ id (PK)             │
│ user_id (FK)         │        │ code                │◄── UNIQUE
│ station_id           │        │ name                │
│ role_id (FK)         │        │ role_type           │  SYSTEM | CUSTOM
│ is_active            │        │ description         │
│ assigned_by          │        │ is_active           │
│ assigned_at          │        │ created_at          │
│ revoked_at           │        └──────────┬──────────┘
└─────────────────────┘                    │ 1:N
                                           │
                                ┌──────────▼──────────┐
                                │   role_permission   │
                                │─────────────────────│
                                │ role_id (FK)        │
                                │ permission          │  "tickets:sell"
                                └─────────────────────┘

┌─────────────────────┐        ┌─────────────────────┐
│   refresh_token     │        │   service_token     │
│─────────────────────│        │─────────────────────│
│ id (PK)             │        │ id (PK)             │
│ user_id (FK)        │        │ user_id (FK)        │
│ token_hash          │        │ name                │
│ issued_at           │        │ token_hash          │
│ expires_at          │        │ last_used_at        │
│ revoked_at (NULL)   │        │ created_by          │
│ user_agent          │        │ revoked_at (NULL)   │
│ ip_address          │        │ created_at          │
└─────────────────────┘        └─────────────────────┘

┌─────────────────────────────────────────────────────┐
│                    auth_audit_log                   │
│─────────────────────────────────────────────────────│
│ id (PK)                                             │
│ user_id (FK, NULL)   ← NULL для неизвестных         │
│ event_type           LOGIN_SUCCESS | LOGIN_FAILED | │
│                      LOGOUT | TOKEN_REFRESHED |     │
│                      USER_CREATED | ROLE_ASSIGNED   │
│                      PASSWORD_CHANGED | DEACTIVATED │
│ ip_address                                          │
│ user_agent                                          │
│ details_json                                        │
│ created_at                                          │
└─────────────────────────────────────────────────────┘
```

---

## 6. Схемы таблиц БД

Все таблицы в схеме `iam`. СУБД: PostgreSQL 15+.

---

### 6.1 `user` — Пользователи

```sql
CREATE TYPE iam.user_type AS ENUM ('USER', 'SERVICE');

CREATE TABLE iam.user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    login           VARCHAR(50)  NOT NULL UNIQUE,
    password_hash   VARCHAR(72)  NOT NULL,   -- bcrypt, max 72 байта
    full_name       VARCHAR(255) NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(20),
    user_type       iam.user_type NOT NULL DEFAULT 'USER',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    is_superuser    BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at   TIMESTAMPTZ,
    created_by      UUID REFERENCES iam.user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_login_format CHECK (
        login ~ '^[a-z0-9_]{3,50}$'
    ),
    -- Суперпользователь только один
    CONSTRAINT uq_superuser UNIQUE (is_superuser)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_user_login    ON iam.user(login);
CREATE INDEX idx_user_active   ON iam.user(is_active);
CREATE INDEX idx_user_type     ON iam.user(user_type);

COMMENT ON COLUMN iam.user.password_hash IS
    'bcrypt hash с cost factor 12. Для SERVICE-пользователей: пустая строка,
     аутентификация через service_token.';
COMMENT ON CONSTRAINT uq_superuser ON iam.user IS
    'Гарантирует единственного суперпользователя через UNIQUE (TRUE).
     Любое количество пользователей с is_superuser=FALSE допустимо.';
```

---

### 6.2 `role` — Роли

```sql
CREATE TYPE iam.role_type AS ENUM ('SYSTEM', 'CUSTOM');

CREATE TABLE iam.role (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(50) NOT NULL UNIQUE,   -- SYSTEM_ADMIN, CASHIER и т.д.
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    role_type   iam.role_type NOT NULL DEFAULT 'CUSTOM',
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_by  UUID REFERENCES iam.user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Системные роли не удаляются и не деактивируются
    CONSTRAINT chk_system_role_active CHECK (
        role_type = 'CUSTOM' OR is_active = TRUE
    )
);

-- Системные роли инициализируются при старте сервиса (seed data)
COMMENT ON TABLE iam.role IS
    'Системные роли (role_type=SYSTEM) создаются при инициализации БД
     и не могут быть изменены или деактивированы.';
```

---

### 6.3 `role_permission` — Разрешения роли

```sql
CREATE TABLE iam.role_permission (
    role_id     UUID NOT NULL REFERENCES iam.role(id) ON DELETE CASCADE,
    permission  VARCHAR(100) NOT NULL,
    -- Формат: "resource:action", например "tickets:sell", "schedule:edit"

    PRIMARY KEY (role_id, permission)
);

CREATE INDEX idx_role_perm_role ON iam.role_permission(role_id);

-- Все системные разрешения
COMMENT ON COLUMN iam.role_permission.permission IS
    'Допустимые значения:
    users:view | users:create | users:edit | users:deactivate
    schedule:view | schedule:create | schedule:edit | schedule:cancel_trip |
        schedule:update_actual_times
    inventory:view | inventory:manage_restrictions | inventory:toggle_restriction |
        inventory:manual_block | inventory:open_transit_gate | inventory:close_transit_gate
    tickets:sell | tickets:refund | tickets:view
    shifts:manage
    documents:view_manifest | documents:view_boarding_sheet |
        documents:view_carrier_report | documents:print
    boarding:scan | boarding:view_stats
    announcements:view_queue | announcements:manage_queue
    reports:view_station | reports:view_network
    settings:manage_tariffs | settings:manage_stations | settings:manage_carriers';
```

---

### 6.4 `station_assignment` — Назначения пользователей на вокзалы

```sql
CREATE TABLE iam.station_assignment (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES iam.user(id),
    station_id   UUID NOT NULL,              -- Ссылка на service_station
    role_id      UUID NOT NULL REFERENCES iam.role(id),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_by  UUID NOT NULL REFERENCES iam.user(id),
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at   TIMESTAMPTZ,
    revoked_by   UUID REFERENCES iam.user(id),

    -- Один пользователь — одна активная роль на вокзале
    CONSTRAINT uq_active_assignment UNIQUE (user_id, station_id, is_active)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_assignment_user    ON iam.station_assignment(user_id, is_active);
CREATE INDEX idx_assignment_station ON iam.station_assignment(station_id, is_active);

COMMENT ON CONSTRAINT uq_active_assignment ON iam.station_assignment IS
    'Один пользователь может иметь только одну активную роль на конкретном вокзале.
     Несколько исторических (revoked) записей допустимы.';
```

---

### 6.5 `refresh_token` — Refresh-токены

```sql
CREATE TABLE iam.refresh_token (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES iam.user(id),
    -- SHA-256 хеш от значения токена
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    -- Метаданные сессии
    user_agent   TEXT,
    ip_address   INET
);

CREATE INDEX idx_refresh_user    ON iam.refresh_token(user_id)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_expires ON iam.refresh_token(expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON COLUMN iam.refresh_token.token_hash IS
    'Хранится SHA-256 хеш, не сам токен. Значение токена отправляется
     клиенту только при выдаче и не хранится в БД в открытом виде.';
```

---

### 6.6 `service_token` — Токены системных компонентов

```sql
CREATE TABLE iam.service_token (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES iam.user(id),
    name         VARCHAR(100) NOT NULL,        -- "Station Agent — Красноярск"
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    last_used_at TIMESTAMPTZ,
    created_by   UUID NOT NULL REFERENCES iam.user(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at   TIMESTAMPTZ,
    revoked_by   UUID REFERENCES iam.user(id)
);

CREATE INDEX idx_service_token_user ON iam.service_token(user_id)
    WHERE revoked_at IS NULL;
```

---

### 6.7 `auth_audit_log` — Журнал аудита аутентификации

```sql
CREATE TABLE iam.auth_audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID REFERENCES iam.user(id),   -- NULL если пользователь не найден
    event_type    VARCHAR(40) NOT NULL,
    ip_address    INET,
    user_agent    TEXT,
    details_json  JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_user    ON iam.auth_audit_log(user_id, created_at DESC)
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_audit_type    ON iam.auth_audit_log(event_type, created_at DESC);
CREATE INDEX idx_audit_created ON iam.auth_audit_log(created_at DESC);

COMMENT ON TABLE iam.auth_audit_log IS
    'Неизменяемый журнал. Только INSERT. Хранить минимум 1 год.';
```

---

## 7. Аутентификация и токены

### 7.1 Процесс входа

```
POST /auth/login
{ login, password }
        │
        ▼
┌──────────────────────────────────────────────────────────┐
│                    iam-service                           │
│                                                          │
│  1. Найти пользователя по login                         │
│     → Не найден → 401 INVALID_CREDENTIALS               │
│     → is_active = false → 403 ACCOUNT_DISABLED          │
│                                                          │
│  2. Проверить блокировку в Redis:                        │
│     GET login_attempts:{login}                           │
│     → Значение >= 5 → 429 ACCOUNT_LOCKED {retry_after}  │
│                                                          │
│  3. Проверить пароль:                                    │
│     bcrypt.CompareHashAndPassword(hash, password)        │
│     → Не совпадает:                                      │
│       INCR login_attempts:{login} (TTL 15 min)           │
│       → 401 INVALID_CREDENTIALS                          │
│                                                          │
│  4. Сбросить счётчик неудачных попыток:                  │
│     DEL login_attempts:{login}                           │
│                                                          │
│  5. Загрузить назначения пользователя:                   │
│     SELECT station_id, role.code, permissions[]          │
│     FROM station_assignment JOIN role JOIN role_permission│
│     WHERE user_id = ? AND is_active = TRUE               │
│                                                          │
│  6. Сформировать JWT AccessToken (RS256, TTL 15 мин)    │
│  7. Создать RefreshToken (random 32 байта)               │
│     Сохранить SHA-256(token) в refresh_token             │
│                                                          │
│  8. Записать LOGIN_SUCCESS в auth_audit_log              │
│  9. Обновить user.last_login_at                          │
└──────────────────────────────────────────────────────────┘
        │
        ▼
{
  "access_token":  "eyJhbGci...",
  "refresh_token": "a3f5c9d2e1b847f6...",
  "token_type":    "Bearer",
  "expires_in":    900
}
```

### 7.2 Структура JWT AccessToken

```json
// Header
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "transora-2025-01"
}

// Payload
{
  "sub":   "user-uuid-here",
  "login": "petrov_cashier",
  "name":  "Петров Иван Сергеевич",
  "type":  "ACCESS",
  "scope": "user",

  // Назначения: массив { station_id, role, permissions[] }
  "assignments": [
    {
      "sid":   "st-001",
      "role":  "CASHIER",
      "perms": ["tickets:sell", "tickets:refund", "tickets:view",
                "shifts:manage", "schedule:view", "inventory:view",
                "documents:print", "boarding:view_stats"]
    }
  ],

  // Для SYSTEM_ADMIN — пустой массив assignments, is_superuser=true
  "is_superuser": false,

  "iat": 1721012400,
  "exp": 1721013300,   // iat + 900 сек (15 мин)
  "jti": "unique-token-id"
}
```

### 7.3 Структура JWT ServiceToken

```json
{
  "sub":       "service-user-uuid",
  "login":     "station_agent_krs",
  "type":      "SERVICE",
  "scope":     "service",
  "station_id": "st-001",
  "assignments": [
    {
      "sid":   "st-001",
      "role":  "STATION_AGENT",
      "perms": ["schedule:view", "inventory:view", "boarding:scan",
                "boarding:view_stats", "announcements:view_queue"]
    }
  ],
  "iat": 1721012400,
  "exp": null    // ServiceToken не истекает
}
```

### 7.4 Обновление токена

```
POST /auth/refresh
{ refresh_token: "a3f5c9d2e1b847f6..." }
        │
        ▼
  1. Вычислить SHA-256(refresh_token)
  2. Найти в таблице refresh_token по token_hash
     → Не найден → 401 INVALID_TOKEN
     → revoked_at IS NOT NULL → 401 TOKEN_REVOKED
     → expires_at < NOW() → 401 TOKEN_EXPIRED
  3. Проверить user.is_active = TRUE
  4. Отозвать старый RefreshToken (UPDATE revoked_at)
  5. Выдать новые AccessToken + RefreshToken
  6. Записать TOKEN_REFRESHED в audit_log
        │
        ▼
  { access_token, refresh_token, expires_in }
```

### 7.5 Управление ключами (RS256)

```
Ключевая пара RSA-2048:
  · private.pem  — хранится в Kubernetes Secret, доступен только iam-service
  · public.pem   — публикуется через GET /auth/jwks.json (JWKS формат)

Ротация ключей:
  · Новая пара создаётся с новым kid
  · Оба ключа публикуются в JWKS одновременно
  · Старый ключ убирается через 24 часа (после истечения всех выданных токенов)

JWKS endpoint (публичный, без аутентификации):
GET /auth/jwks.json
→ {
    "keys": [{
      "kty": "RSA",
      "use": "sig",
      "kid": "transora-2025-01",
      "alg": "RS256",
      "n":   "...",
      "e":   "AQAB"
    }]
  }
```

### 7.6 Хранилище в Redis

```
# Счётчик неудачных попыток входа
Key:   login_attempts:{login}
Value: integer (инкрементируется)
TTL:   900 сек (15 минут)

# Blacklist отозванных AccessToken
Key:   token_blacklist:{jti}
Value: "1"
TTL:   оставшееся время жизни токена

# Blacklist отозванных ServiceToken
Key:   service_token_revoked:{token_hash[:16]}
Value: "1"
TTL:   none (постоянно)

# Кеш JWKS для быстрого ответа
Key:   jwks_cache
Value: JSON строка JWKS
TTL:   3600 сек (1 час)
```

---

## 8. Авторизация запросов (Kong)

### 8.1 Схема проверки токена в Kong

```
Клиент                  Kong Gateway              iam-service / JWT plugin
    │                       │                              │
    │ Request + Bearer token │                              │
    │───────────────────────►│                              │
    │                        │ Verify JWT signature         │
    │                        │ (публичный ключ из JWKS)     │
    │                        │─────────────────────────────►│
    │                        │◄─────────────────────────────│
    │                        │ { valid: true, payload }     │
    │                        │                              │
    │                        │ Проверить blacklist:         │
    │                        │ GET token_blacklist:{jti}    │
    │                        │─── Redis ───────────────────►│
    │                        │◄────────────────────────────│
    │                        │                              │
    │                        │ Добавить заголовки к запросу:│
    │                        │ X-User-ID: {sub}             │
    │                        │ X-User-Login: {login}        │
    │                        │ X-Station-ID: {station_id}   │
    │                        │ X-User-Role: {role}          │
    │                        │ X-User-Perms: {perms CSV}    │
    │                        │                              │
    │                        │──► Upstream Service          │
```

### 8.2 Kong JWT Plugin конфигурация

```yaml
# Kong declarative config (deck)
plugins:
  - name: jwt
    config:
      key_claim_name: kid
      claims_to_verify:
        - exp
      run_on_preflight: false

  - name: request-transformer
    config:
      add:
        headers:
          - "X-Gateway-Version: 1.0"

routes:
  # Публичные маршруты (без авторизации)
  - name: auth-public
    paths:
      - /auth/login
      - /auth/refresh
      - /auth/jwks.json
    plugins:
      - name: jwt
        enabled: false

  # Защищённые маршруты
  - name: api-protected
    paths:
      - /api
    plugins:
      - name: jwt
        enabled: true
```

### 8.3 Проверка разрешений на стороне сервисов

Каждый сервис самостоятельно проверяет разрешения из заголовка `X-User-Perms`:

```kotlin
// Spring Boot: аннотация для проверки разрешений
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequirePermission(val value: String)

// AOP Aspect
@Aspect
@Component
class PermissionAspect {

    @Before("@annotation(requirePermission)")
    fun checkPermission(requirePermission: RequirePermission) {
        val perms = RequestContextHolder
            .getRequestAttributes()
            ?.let { it as ServletRequestAttributes }
            ?.request
            ?.getHeader("X-User-Perms")
            ?.split(",")
            ?: emptyList()

        if (!perms.contains(requirePermission.value)) {
            throw AccessDeniedException(
                "Required permission: ${requirePermission.value}"
            )
        }
    }
}

// Использование в контроллере
@PostMapping("/tickets")
@RequirePermission("tickets:sell")
fun sellTicket(@RequestBody request: SellTicketRequest): ResponseEntity<TicketResponse> {
    // ...
}
```

### 8.4 Проверка принадлежности к вокзалу

```kotlin
// StationContextFilter.kt
@Component
class StationContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val userStations = request.getHeader("X-User-Stations")
            ?.split(",") ?: emptyList()

        val requestedStation = request.getHeader("X-Station-ID")
            ?: extractStationFromPath(request.requestURI)

        // Суперпользователь имеет доступ ко всем вокзалам
        val isSuperuser = request.getHeader("X-Superuser") == "true"

        if (!isSuperuser && requestedStation != null
            && !userStations.contains(requestedStation)) {
            response.sendError(403, "No access to station: $requestedStation")
            return
        }

        chain.doFilter(request, response)
    }
}
```

---

## 9. HTTP API сервиса

### 9.1 Аутентификация

```
── Публичные эндпоинты (без токена) ──────────────────────────────────

POST /auth/login
     Body: { login, password }
     → { access_token, refresh_token, token_type, expires_in }
     Ошибки: 401 INVALID_CREDENTIALS | 403 ACCOUNT_DISABLED |
             429 ACCOUNT_LOCKED { retry_after_sec }

POST /auth/refresh
     Body: { refresh_token }
     → { access_token, refresh_token, expires_in }
     Ошибки: 401 INVALID_TOKEN | TOKEN_REVOKED | TOKEN_EXPIRED

POST /auth/logout
     Header: Authorization: Bearer {access_token}
     Body: { refresh_token }
     → 200 OK
     Действие: AccessToken → blacklist, RefreshToken → revoked

GET  /auth/jwks.json
     → JWKS JSON (публичный ключ для верификации)

GET  /auth/me
     Header: Authorization: Bearer {access_token}
     → { user_id, login, full_name, assignments[], is_superuser }
```

### 9.2 Управление пользователями

```
── Требует: users:view ───────────────────────────────────────────────

GET  /users?station_id={id}&role={code}&active={bool}
     → { users: [UserSummary...], total }

GET  /users/{userId}
     → UserDetail (с assignments)

── Требует: users:create ─────────────────────────────────────────────

POST /users
     Body: {
       login, password, full_name, email?, phone?,
       assignments: [{ station_id, role_code }]
     }
     → UserDetail
     Ошибки: 409 LOGIN_ALREADY_EXISTS

── Требует: users:edit ───────────────────────────────────────────────

PUT  /users/{userId}
     Body: { full_name?, email?, phone? }
     → UserDetail

POST /users/{userId}/change-password
     Body: { new_password }
     → 200 OK

── Требует: users:deactivate ─────────────────────────────────────────

POST /users/{userId}/deactivate
     → 200 OK
     Действие: is_active=false, все токены → blacklist/revoked

POST /users/{userId}/activate
     → 200 OK
```

### 9.3 Управление назначениями

```
── Требует: users:edit (на своём вокзале) или SYSTEM_ADMIN ───────────

POST /users/{userId}/assignments
     Body: { station_id, role_code }
     → StationAssignment
     Ошибки: 409 ASSIGNMENT_ALREADY_EXISTS

DELETE /users/{userId}/assignments/{assignmentId}
     → 200 OK

GET  /stations/{stationId}/users
     Требует: users:view
     → { users: [UserWithRole...] }
```

### 9.4 Управление ролями

```
── Требует: SYSTEM_ADMIN ─────────────────────────────────────────────

GET  /roles
     → { roles: [RoleSummary...] }

GET  /roles/{roleId}
     → RoleDetail (с permissions)

POST /roles
     Body: { code, name, description, permissions: [] }
     → RoleDetail

PUT  /roles/{roleId}
     Body: { name?, description?, permissions? }
     → RoleDetail
     Ограничение: Системные роли (SYSTEM) не изменяются
```

### 9.5 Управление ServiceToken

```
── Требует: SYSTEM_ADMIN ─────────────────────────────────────────────

POST /service-tokens
     Body: { user_id, name }
     → { token_id, token_value, name }
     Внимание: token_value показывается только один раз

GET  /service-tokens
     → { tokens: [ServiceTokenSummary...] }  -- без token_value

DELETE /service-tokens/{tokenId}
     → 200 OK (revoke)
```

### 9.6 Коды ошибок

```json
// Стандартный формат ошибки
{
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "Неверный логин или пароль",
    "details": null
  }
}
```

| Код | HTTP | Описание |
|-----|------|---------|
| `INVALID_CREDENTIALS` | 401 | Неверный логин/пароль |
| `ACCOUNT_DISABLED` | 403 | Учётная запись деактивирована |
| `ACCOUNT_LOCKED` | 429 | Временная блокировка после 5 неудач |
| `INVALID_TOKEN` | 401 | Токен не найден или невалидный |
| `TOKEN_EXPIRED` | 401 | Токен истёк |
| `TOKEN_REVOKED` | 401 | Токен был отозван |
| `PERMISSION_DENIED` | 403 | Недостаточно прав |
| `STATION_ACCESS_DENIED` | 403 | Нет доступа к вокзалу |
| `LOGIN_ALREADY_EXISTS` | 409 | Логин занят |
| `ASSIGNMENT_ALREADY_EXISTS` | 409 | Назначение уже существует |

---

## 10. Примеры данных

### 10.1 Пользователи системы при инициализации

```sql
-- Суперпользователь (создаётся скриптом инициализации)
INSERT INTO iam.user (id, login, password_hash, full_name, user_type, is_superuser)
VALUES (
    gen_random_uuid(),
    'admin',
    '$2a$12$...',   -- bcrypt('changeme', 12)
    'Системный администратор',
    'USER',
    TRUE
);

-- Системный пользователь для station-agent
INSERT INTO iam.user (id, login, password_hash, full_name, user_type)
VALUES (
    gen_random_uuid(),
    'station_agent_krs',
    '',                    -- SERVICE тип — пароль не используется
    'Station Agent — Красноярск',
    'SERVICE'
);
```

### 10.2 Пример токена кассира (JWT payload)

```json
{
  "sub":   "cashier-uuid-001",
  "login": "petrova_cashier",
  "name":  "Петрова Анна Викторовна",
  "type":  "ACCESS",
  "scope": "user",
  "assignments": [
    {
      "sid": "st-001",
      "role": "CASHIER",
      "perms": [
        "tickets:sell",
        "tickets:refund",
        "tickets:view",
        "shifts:manage",
        "schedule:view",
        "inventory:view",
        "documents:print",
        "boarding:view_stats"
      ]
    }
  ],
  "is_superuser": false,
  "iat": 1721012400,
  "exp": 1721013300,
  "jti": "jti-a3f5c9d2"
}
```

### 10.3 Пример токена диспетчера двух вокзалов

```json
{
  "sub":   "dispatcher-uuid-001",
  "login": "smirnov_disp",
  "name":  "Смирнов Олег Иванович",
  "type":  "ACCESS",
  "scope": "user",
  "assignments": [
    {
      "sid": "st-001",
      "role": "DISPATCHER",
      "perms": [
        "schedule:view", "schedule:edit", "schedule:cancel_trip",
        "schedule:update_actual_times",
        "inventory:view", "inventory:toggle_restriction",
        "inventory:manual_block", "inventory:open_transit_gate",
        "inventory:close_transit_gate",
        "tickets:view", "documents:view_manifest",
        "documents:view_boarding_sheet", "documents:print",
        "boarding:view_stats", "announcements:view_queue",
        "announcements:manage_queue", "reports:view_station"
      ]
    },
    {
      "sid": "st-002",
      "role": "DISPATCHER",
      "perms": [ "...same permissions..." ]
    }
  ],
  "is_superuser": false,
  "iat": 1721012400,
  "exp": 1721013300,
  "jti": "jti-b7e2a1d4"
}
```

### 10.4 ServiceToken для station-agent

```json
{
  "sub":        "service-uuid-krs",
  "login":      "station_agent_krs",
  "type":       "SERVICE",
  "scope":      "service",
  "station_id": "st-001",
  "assignments": [
    {
      "sid": "st-001",
      "role": "STATION_AGENT",
      "perms": [
        "schedule:view",
        "inventory:view",
        "boarding:scan",
        "boarding:view_stats",
        "announcements:view_queue"
      ]
    }
  ],
  "iat": 1721012400,
  "exp": null,
  "jti": "jti-service-krs"
}
```

### 10.5 Запись журнала аудита

```json
{
  "id": "log-iam-001",
  "user_id": "cashier-uuid-001",
  "event_type": "LOGIN_SUCCESS",
  "ip_address": "192.168.1.45",
  "user_agent": "Mozilla/5.0 (Tauri)",
  "details_json": {
    "station_id": "st-001",
    "assignments_count": 1
  },
  "created_at": "2025-07-15T08:00:05+07:00"
}
```

---

## 11. События (NATS JetStream)

### События, которые сервис **публикует** (stream: `transora.iam`)

| Subject | Триггер | Потребители |
|---------|---------|------------|
| `iam.user.deactivated` | Деактивация пользователя | Все сервисы (для инвалидации сессий) |
| `iam.user.role_changed` | Смена роли / назначения | — (аудит) |
| `iam.token.revoked` | Отзыв токена (logout, deactivate) | Kong (для обновления blacklist) |

### Пример payload `iam.user.deactivated`

```json
{
  "event_id": "evt-iam-001",
  "event_type": "iam.user.deactivated",
  "occurred_at": "2025-07-15T10:00:00Z",
  "payload": {
    "user_id": "cashier-uuid-001",
    "login": "petrova_cashier",
    "deactivated_by": "admin-uuid",
    "reason": "Уволена"
  }
}
```

---

## 12. Зависимости и взаимодействие

```
                   ┌──────────────────────────┐
                   │        iam-service        │
                   └────────────┬─────────────┘
                                │
        ┌───────────────────────┼──────────────────────┐
        │                       │                      │
  REST API                 Публикует              Хранилища
  (входящие)              события NATS
        │                       │                      │
┌───────▼──────────┐   ┌────────▼────────┐   ┌────────▼──────┐
│  Все клиенты     │   │  Kong Gateway   │   │  PostgreSQL   │
│  · cashier-app   │   │  (blacklist     │   │  (users,      │
│  · dispatcher-app│   │   обновление)   │   │   roles,      │
│  · boarding-app  │   │                 │   │   tokens)     │
│  · station-agent │   │  Все сервисы    │   ├───────────────┤
│  · admin-panel   │   │  (deactivated   │   │     Redis     │
└──────────────────┘   │   событие)      │   │  (blacklist,  │
                       └─────────────────┘   │   brute-force,│
                                             │   jwks cache) │
                       Kong JWT Plugin:       └───────────────┘
                       ┌─────────────────┐
                       │  GET /auth/     │  ← Загружает публичный
                       │  jwks.json      │    ключ при старте
                       └─────────────────┘

Верификация токена другими сервисами:
  · Kong проверяет подпись и exp → добавляет заголовки X-User-*
  · Сервисы читают X-User-Perms → проверяют своё разрешение
  · Blacklist проверяется в Kong → без обращения к iam-service
```

**Ключевые принципы:**

- **Stateless верификация** — сервисы проверяют JWT по публичному ключу без запроса к `iam-service` при каждом запросе. Это обеспечивает горизонтальное масштабирование.
- **Blacklist в Redis** — единственное исключение из stateless: отозванные токены проверяются в Redis через Kong. TTL записи = оставшееся время жизни токена.
- **Минимальный payload** — JWT содержит только необходимое: роль, список разрешений, список вокзалов. Персональные данные в токен не включаются.
- **RS256 вместо HS256** — асимметричная подпись позволяет любому сервису верифицировать токен, имея только публичный ключ, без знания секрета.

---

*Следующий документ: `transora-deployment.md` — Схема развёртывания: Kubernetes, Docker Compose, сетевая топология*
