# Transora — Station Agent
## Агент вокзала: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `station-agent`

---

## Содержание

1. [Назначение компонента](#1-назначение-компонента)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Архитектура агента](#3-архитектура-агента)
4. [Подключение к ядру системы](#4-подключение-к-ядру-системы)
5. [Локальный кеш расписания](#5-локальный-кеш-расписания)
6. [Маршрутизация запросов касс и диспетчера](#6-маршрутизация-запросов-касс-и-диспетчера)
7. [Подсистема воспроизведения аудио](#7-подсистема-воспроизведения-аудио)
8. [Работа при потере связи с ядром](#8-работа-при-потере-связи-с-ядром)
9. [HTTP API агента](#9-http-api-агента)
10. [Конфигурация](#10-конфигурация)
11. [Хранилище (SQLite)](#11-хранилище-sqlite)
12. [Мониторинг и диагностика](#12-мониторинг-и-диагностика)
13. [Установка и деплой](#13-установка-и-деплой)

---

## 1. Назначение компонента

`station-agent` — постоянно работающий Go-процесс на каждом вокзале,
выступающий локальным посредником между клиентскими приложениями вокзала
(кассы, рабочее место диспетчера, табло) и центральным ядром системы Transora.

Основные обязанности:

- Поддержание постоянного WebSocket-соединения с ядром системы
- Проксирование HTTP-запросов от касс и диспетчера к API ядра
- Ведение локального кеша расписания для работы табло при потере связи
- Управление аудиосистемой вокзала: получение команд от `notification-service`
  и воспроизведение аудиофайлов через системный аудиовыход
- Буферизация событий посадки из ТСД-приложения при потере связи
  с последующей синхронизацией
- Мониторинг состояния сети и уведомление локальных клиентов о статусе связи

Агент работает как **системный сервис** (Windows Service / systemd) и является
первой точкой отказа, которую нужно диагностировать при проблемах на вокзале.

**Стек:** Go 1.22+ | SQLite | chi (HTTP) | gorilla/websocket | ffplay/mpv (аудио)

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **CoreConnection** | WebSocket-соединение агента с ядром системы (Kong API Gateway) |
| **ScheduleCache** | Локальная SQLite-база с кешем расписания. Обновляется в реальном времени через события NATS. |
| **ProxyRouter** | HTTP reverse proxy: запросы от локальных клиентов → ядро → ответ клиенту |
| **PlaybackAgent** | Подсистема воспроизведения аудио. Получает команды от `notification-service` и играет MP3 через системный аудиовыход. |
| **BoardingBuffer** | Буфер событий посадки из ТСД-приложений при потере связи с ядром |
| **SyncQueue** | Очередь операций, накопленных в офлайн-режиме, для отправки при восстановлении связи |
| **HeartbeatMonitor** | Компонент мониторинга доступности ядра и управления режимом работы |
| **StationMode** | Текущий режим работы вокзала: `ONLINE` / `DEGRADED` / `OFFLINE` |

---

## 3. Архитектура агента

### 3.1 Общая схема

```
┌──────────────────────────────────────────────────────────────────────┐
│                      station-agent (Go process)                      │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                    HTTP Server (chi) :8080                      │ │
│  │              Доступен локально в сети вокзала                   │ │
│  └───────────────┬─────────────────────────────────────────────────┘ │
│                  │                                                    │
│  ┌───────────────▼─────────────────────────────────────────────────┐ │
│  │                      Request Router                             │ │
│  │  · Кеш-запросы (расписание) → ScheduleCache (local)            │ │
│  │  · API-запросы (продажи, инвентарь) → ProxyRouter → Core       │ │
│  │  · Статус агента → local handler                                │ │
│  └───────┬────────────────────────────┬────────────────────────────┘ │
│          │                            │                               │
│  ┌───────▼───────┐          ┌─────────▼──────────┐                  │
│  │ ScheduleCache │          │    ProxyRouter      │                  │
│  │  (SQLite)     │          │  (httputil.Reverse  │                  │
│  │               │          │   Proxy + retry)    │                  │
│  │ · Рейсы       │          └─────────┬───────────┘                  │
│  │ · Остановки   │                    │ HTTPS                         │
│  │ · Статусы     │          ┌─────────▼───────────┐                  │
│  └───────────────┘          │   CoreConnection    │                  │
│                             │   (WebSocket)       │                  │
│  ┌────────────────┐         │   · Heartbeat       │                  │
│  │ PlaybackAgent  │         │   · Reconnect loop  │                  │
│  │                │◄────────│   · Event receiver  │                  │
│  │ · Queue        │  WS cmd │   · Mode manager    │                  │
│  │ · ffplay/mpv   │         └─────────────────────┘                  │
│  │ · Status→Core  │                                                   │
│  └────────────────┘                                                   │
│                                                                       │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                   BoardingBuffer (SQLite)                      │  │
│  │  · Буфер событий посадки при offline                          │  │
│  │  · SyncQueue для восстановления                               │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
        ▲              ▲             ▲              ▲
        │              │             │              │
  ┌─────┴────┐  ┌──────┴─────┐ ┌────┴──────┐ ┌────┴──────┐
  │  Кассы   │  │ Диспетчер  │ │   Табло   │ │  ТСД-     │
  │ (Tauri)  │  │  (веб)     │ │  (браузер)│ │  приложен.│
  └──────────┘  └────────────┘ └───────────┘ └───────────┘
```

### 3.2 Место агента в общей топологии

```
ИНТЕРНЕТ / WAN
        │
        │ HTTPS / WSS
        ▼
┌───────────────────┐
│  Ядро Transora    │  (Kubernetes, HA)
│  Kong API Gateway │
│  Core Services    │
└────────┬──────────┘
         │ WSS (постоянное соединение)
         │
┌────────▼──────────────────────────────────────────┐
│              station-agent                        │  Вокзал (LAN)
│              :8080                                │
└──┬──────────┬────────────────┬────────────────────┘
   │          │                │
   │  HTTP    │  HTTP          │  HTTP
   ▼          ▼                ▼
Кассы 1..N  Диспетчер    Табло / ТСД
```

---

## 4. Подключение к ядру системы

### 4.1 CoreConnection — WebSocket-соединение

```go
package core

import (
    "context"
    "time"
    "github.com/gorilla/websocket"
)

type CoreConnection struct {
    config      ConnectionConfig
    conn        *websocket.Conn
    mode        StationMode
    modeMu      sync.RWMutex
    send        chan []byte          // Исходящие сообщения
    recv        chan CoreMessage     // Входящие сообщения
    modeChange  chan StationMode     // Уведомления о смене режима
    done        chan struct{}
}

type ConnectionConfig struct {
    CoreURL       string        // wss://core.transora.internal/ws/stations
    StationID     string        // UUID вокзала
    AuthToken     string        // JWT-токен агента
    PingInterval  time.Duration // 30 сек
    PingTimeout   time.Duration // 10 сек
    ReconnectMin  time.Duration // 1 сек (начальная задержка)
    ReconnectMax  time.Duration // 60 сек (максимальная задержка)
}

type StationMode string
const (
    ModeOnline   StationMode = "ONLINE"    // Связь с ядром есть
    ModeDegraded StationMode = "DEGRADED"  // Переходное состояние (переподключение)
    ModeOffline  StationMode = "OFFLINE"   // Связи нет
)
```

### 4.2 Цикл переподключения с exponential backoff

```go
func (c *CoreConnection) RunReconnectLoop(ctx context.Context) {
    backoff := c.config.ReconnectMin

    for {
        select {
        case <-ctx.Done():
            return
        default:
        }

        log.Info("Connecting to core", "url", c.config.CoreURL)
        c.setMode(ModeDegraded)

        err := c.connect(ctx)
        if err != nil {
            log.Warn("Connection failed", "error", err, "retry_in", backoff)
            c.setMode(ModeOffline)

            select {
            case <-ctx.Done():
                return
            case <-time.After(backoff):
                // Exponential backoff с jitter
                backoff = min(backoff*2, c.config.ReconnectMax)
                backoff += time.Duration(rand.Int63n(int64(backoff / 4)))
            }
            continue
        }

        // Соединение установлено
        backoff = c.config.ReconnectMin
        c.setMode(ModeOnline)
        log.Info("Connected to core")

        // Запустить обработку в горутинах
        c.runSession(ctx)

        // runSession вернулся — соединение потеряно
        log.Warn("Connection lost, will reconnect")
    }
}

func (c *CoreConnection) connect(ctx context.Context) error {
    dialer := websocket.DefaultDialer
    header := http.Header{
        "Authorization": []string{"Bearer " + c.config.AuthToken},
        "X-Station-ID":  []string{c.config.StationID},
    }

    conn, _, err := dialer.DialContext(ctx, c.config.CoreURL, header)
    if err != nil {
        return err
    }
    c.conn = conn
    return nil
}
```

### 4.3 Протокол обмена с ядром (WebSocket)

**Сообщения от ядра к агенту:**

```go
type CoreMessage struct {
    Type    string          `json:"type"`
    Payload json.RawMessage `json:"payload"`
}

// Типы входящих сообщений:
const (
    // Обновления расписания
    MsgTripUpdated      = "trip.updated"       // Рейс изменён
    MsgTripCreated      = "trip.created"       // Новый рейс
    MsgTripCancelled    = "trip.cancelled"     // Рейс отменён
    MsgTripDelayed      = "trip.delay_updated" // Задержка

    // Команды аудио
    MsgAudioPlay        = "audio.play"         // Воспроизвести аудиофайл
    MsgAudioStop        = "audio.stop"         // Остановить воспроизведение
    MsgAudioQueueUpdate = "audio.queue_update" // Обновить состояние очереди

    // Управление
    MsgPing             = "ping"
    MsgConfigUpdate     = "config.update"      // Обновление конфигурации агента
    MsgForceSync        = "sync.force"         // Принудительная синхронизация кеша
)
```

**Сообщения от агента к ядру:**

```go
// Типы исходящих сообщений:
const (
    MsgPong              = "pong"
    MsgAudioStatus       = "audio.status"       // Статус воспроизведения
    MsgBoardingEvent     = "boarding.event"      // Событие посадки (от ТСД)
    MsgAgentStatus       = "agent.status"        // Периодический heartbeat
    MsgSyncRequest       = "sync.request"        // Запрос полной синхронизации
)

// Heartbeat агента (каждые 30 сек)
type AgentStatusPayload struct {
    StationID    string      `json:"station_id"`
    Mode         StationMode `json:"mode"`
    CacheVersion int64       `json:"cache_version"` // Timestamp последнего обновления
    AudioQueueLen int        `json:"audio_queue_len"`
    Uptime       int64       `json:"uptime_sec"`
}
```

### 4.4 Первичная синхронизация при подключении

```go
func (c *CoreConnection) onConnected(ctx context.Context) error {
    // 1. Запросить полный снимок расписания для вокзала
    syncReq := SyncRequestPayload{
        StationID:        c.config.StationID,
        CacheVersionFrom: c.cache.LastVersion(),
        HorizonHours:     48, // Рейсы на 48 часов вперёд
    }
    c.send <- mustMarshal(CoreMessage{
        Type:    MsgSyncRequest,
        Payload: mustMarshal(syncReq),
    })

    // 2. Отправить накопленные в офлайне события посадки
    go c.flushBoardingBuffer(ctx)

    return nil
}
```

---

## 5. Локальный кеш расписания

### 5.1 Назначение и ограничения

Кеш расписания хранится локально в SQLite и используется:
- Табло рейсов и перронными табло при потере связи с ядром
- Для быстрого ответа на запросы расписания без обращения к ядру
- Аудиосистемой для отображения данных рейсов в объявлениях при офлайн

**Важно:** Кеш доступен **только для чтения** клиентами. Изменение данных
расписания — исключительно через API ядра.

### 5.2 Схема SQLite-кеша

```sql
-- Файл: /var/transora/station-agent/schedule_cache.db

CREATE TABLE cached_trip (
    trip_id           TEXT PRIMARY KEY,
    trip_number       TEXT NOT NULL,
    trip_date         TEXT NOT NULL,   -- ISO date: "2025-07-15"
    route_id          TEXT NOT NULL,
    route_name        TEXT NOT NULL,
    carrier_name      TEXT NOT NULL,
    vehicle_plate     TEXT,
    driver_name       TEXT,
    status            TEXT NOT NULL,
    delay_minutes     INTEGER NOT NULL DEFAULT 0,
    -- JSON массив остановок (денормализовано для скорости чтения)
    stops_json        TEXT NOT NULL,
    -- Данные для табло (денормализовано)
    platform_number   INTEGER,
    is_departure      INTEGER NOT NULL DEFAULT 1, -- 1 = отправление, 0 = прибытие
    display_time      TEXT NOT NULL,  -- Итоговое время для отображения
    direction_stop    TEXT NOT NULL,  -- Конечная/начальная для отображения
    -- Метаданные кеша
    cached_at         INTEGER NOT NULL, -- Unix timestamp
    version           INTEGER NOT NULL  -- Версия записи от ядра
);

CREATE INDEX idx_cached_trip_date   ON cached_trip(trip_date, display_time);
CREATE INDEX idx_cached_trip_status ON cached_trip(status);
CREATE INDEX idx_cached_trip_version ON cached_trip(version);

CREATE TABLE cache_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- Записи:
-- { key: "last_sync_at",      value: "1721012400" }
-- { key: "last_version",      value: "1721012400123" }
-- { key: "station_id",        value: "st-001" }
-- { key: "horizon_hours",     value: "48" }
```

### 5.3 Обновление кеша по входящим событиям

```go
// CacheUpdater — обновляет SQLite при получении событий от ядра
type CacheUpdater struct {
    db      *sql.DB
    mu      sync.Mutex
}

func (u *CacheUpdater) HandleMessage(msg CoreMessage) error {
    u.mu.Lock()
    defer u.mu.Unlock()

    switch msg.Type {

    case MsgTripCreated, MsgTripUpdated:
        var payload TripPayload
        if err := json.Unmarshal(msg.Payload, &payload); err != nil {
            return err
        }
        return u.upsertTrip(payload)

    case MsgTripCancelled:
        var payload struct{ TripID string `json:"trip_id"` }
        if err := json.Unmarshal(msg.Payload, &payload); err != nil {
            return err
        }
        return u.updateTripStatus(payload.TripID, "CANCELLED")

    case MsgTripDelayed:
        var payload struct {
            TripID       string `json:"trip_id"`
            DelayMinutes int    `json:"delay_minutes"`
            DisplayTime  string `json:"display_time"`
        }
        if err := json.Unmarshal(msg.Payload, &payload); err != nil {
            return err
        }
        return u.updateTripDelay(payload.TripID, payload.DelayMinutes, payload.DisplayTime)

    case MsgForceSync:
        // Полная перезагрузка кеша
        return u.fullResync()
    }

    return nil
}

func (u *CacheUpdater) upsertTrip(t TripPayload) error {
    stopsJSON, _ := json.Marshal(t.Stops)
    _, err := u.db.Exec(`
        INSERT INTO cached_trip (
            trip_id, trip_number, trip_date, route_id, route_name,
            carrier_name, vehicle_plate, driver_name, status,
            delay_minutes, stops_json, platform_number,
            is_departure, display_time, direction_stop,
            cached_at, version
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(trip_id) DO UPDATE SET
            status        = excluded.status,
            delay_minutes = excluded.delay_minutes,
            stops_json    = excluded.stops_json,
            display_time  = excluded.display_time,
            vehicle_plate = excluded.vehicle_plate,
            driver_name   = excluded.driver_name,
            platform_number = excluded.platform_number,
            cached_at     = excluded.cached_at,
            version       = excluded.version
    `,
        t.TripID, t.TripNumber, t.TripDate, t.RouteID, t.RouteName,
        t.CarrierName, t.VehiclePlate, t.DriverName, t.Status,
        t.DelayMinutes, string(stopsJSON), t.PlatformNumber,
        t.IsDeparture, t.DisplayTime, t.DirectionStop,
        time.Now().Unix(), t.Version,
    )
    return err
}
```

### 5.4 Очистка устаревших записей

```go
// Фоновый процесс: удаляет рейсы старше 24 часов от текущего момента
func (u *CacheUpdater) StartCleanupLoop(ctx context.Context) {
    ticker := time.NewTicker(1 * time.Hour)
    defer ticker.Stop()

    for {
        select {
        case <-ctx.Done():
            return
        case <-ticker.C:
            cutoff := time.Now().AddDate(0, 0, -1).Format("2006-01-02")
            result, err := u.db.ExecContext(ctx,
                "DELETE FROM cached_trip WHERE trip_date < ?", cutoff,
            )
            if err != nil {
                log.Error("Cache cleanup failed", "error", err)
                continue
            }
            n, _ := result.RowsAffected()
            if n > 0 {
                log.Info("Cache cleanup", "removed_trips", n)
            }
        }
    }
}
```

---

## 6. Маршрутизация запросов касс и диспетчера

### 6.1 Логика маршрутизации

```go
// Router — определяет, куда направить входящий запрос
func (r *RequestRouter) ServeHTTP(w http.ResponseWriter, req *http.Request) {

    path := req.URL.Path

    switch {

    // ── Запросы расписания → локальный кеш (работают при offline) ──
    case matchPath(path, "/schedule/trips"):
        r.scheduleHandler.ListTrips(w, req)

    case matchPath(path, "/schedule/trips/{tripId}"):
        r.scheduleHandler.GetTrip(w, req)

    case matchPath(path, "/schedule/board"):
        r.scheduleHandler.GetBoardState(w, req)

    // ── Статус агента → local ──
    case path == "/agent/status":
        r.statusHandler.GetStatus(w, req)

    case path == "/agent/mode":
        r.statusHandler.GetMode(w, req)

    // ── Управление аудио → local PlaybackAgent ──
    case matchPath(path, "/audio/{cacheId}"):
        r.audioHandler.StreamAudio(w, req)

    // ── Посадка (ТСД) → буферизация + проксирование ──
    case matchPath(path, "/boarding/scan"):
        r.boardingHandler.Scan(w, req)

    // ── Всё остальное → прокси к ядру ──
    default:
        r.proxy.ServeHTTP(w, req)
    }
}
```

### 6.2 Обработчик запросов расписания из кеша

```go
// ScheduleHandler — отвечает из локального кеша
type ScheduleHandler struct {
    cache *ScheduleCache
    mode  *ModeReader
}

func (h *ScheduleHandler) ListTrips(w http.ResponseWriter, r *http.Request) {
    q := r.URL.Query()
    date := q.Get("date")       // "2025-07-15" или пусто (сегодня)
    window := q.Get("window")   // Горизонт в минутах

    trips, err := h.cache.QueryTrips(date, window)
    if err != nil {
        http.Error(w, "cache error", http.StatusInternalServerError)
        return
    }

    // Добавить заголовок с информацией об источнике данных
    if h.mode.Current() != ModeOnline {
        w.Header().Set("X-Data-Source", "cache")
        w.Header().Set("X-Cache-Version", h.cache.LastVersionStr())
    }

    writeJSON(w, trips)
}
```

### 6.3 Reverse Proxy к ядру

```go
// CoreProxy — httputil.ReverseProxy с обработкой ошибок офлайн
type CoreProxy struct {
    proxy *httputil.ReverseProxy
    mode  *ModeReader
}

func NewCoreProxy(coreURL string) *CoreProxy {
    target, _ := url.Parse(coreURL)

    proxy := httputil.NewSingleHostReverseProxy(target)

    // Модифицировать запрос перед проксированием
    proxy.Director = func(req *http.Request) {
        req.URL.Scheme = target.Scheme
        req.URL.Host = target.Host
        req.Header.Set("X-Forwarded-By", "station-agent")
        req.Header.Set("X-Station-ID", stationID)
    }

    // Обработка ошибок проксирования
    proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
        log.Warn("Proxy error", "path", r.URL.Path, "error", err)
        writeJSON(w, ErrorResponse{
            Code:    "CORE_UNAVAILABLE",
            Message: "Нет связи с ядром системы. Операция недоступна.",
        }, http.StatusServiceUnavailable)
    }

    return &CoreProxy{proxy: proxy}
}

func (p *CoreProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // Проверить режим: если OFFLINE — сразу вернуть ошибку
    if p.mode.Current() == ModeOffline {
        writeJSON(w, ErrorResponse{
            Code:    "STATION_OFFLINE",
            Message: "Вокзал работает в автономном режиме. Операция недоступна.",
        }, http.StatusServiceUnavailable)
        return
    }

    p.proxy.ServeHTTP(w, r)
}
```

---

## 7. Подсистема воспроизведения аудио

### 7.1 Архитектура PlaybackAgent

```go
// PlaybackAgent — управляет очередью и воспроизведением аудио
type PlaybackAgent struct {
    config      PlaybackConfig
    queue       []*PlayCommand      // Очередь команд воспроизведения
    mu          sync.Mutex
    current     *PlayCommand        // Текущее воспроизведение
    coreSend    chan<- []byte        // Канал для отправки статусов в ядро
    audioDir    string              // Локальная директория для кеша аудио
    httpClient  *http.Client        // Для скачивания аудиофайлов
}

type PlayCommand struct {
    AnnouncementID string `json:"announcement_id"`
    AudioCacheID   string `json:"audio_cache_id"`
    AudioURL       string `json:"audio_url"`       // URL на notification-service
    Priority       string `json:"priority"`        // HIGH | MEDIUM | LOW
    ReceivedAt     time.Time
}

type PlaybackConfig struct {
    Player          string  // "ffplay" | "mpv" | "aplay"
    Volume          int     // 0-100
    AudioOutputDev  string  // Системное устройство вывода (опционально)
    LocalCacheDir   string  // Директория кеша MP3 файлов
    DownloadTimeout int     // Секунды на скачивание файла
}
```

### 7.2 Обработка команды воспроизведения

```go
func (a *PlaybackAgent) HandlePlayCommand(cmd PlayCommand) {
    a.mu.Lock()
    a.queue = append(a.queue, &cmd)
    a.mu.Unlock()

    // Сигнализировать обработчику очереди
    a.triggerProcessQueue()
}

func (a *PlaybackAgent) processQueue(ctx context.Context) {
    for {
        select {
        case <-ctx.Done():
            return
        case <-a.queueTrigger:
            a.playNext(ctx)
        }
    }
}

func (a *PlaybackAgent) playNext(ctx context.Context) {
    a.mu.Lock()
    if len(a.queue) == 0 || a.current != nil {
        a.mu.Unlock()
        return
    }
    cmd := a.queue[0]
    a.queue = a.queue[1:]
    a.current = cmd
    a.mu.Unlock()

    defer func() {
        a.mu.Lock()
        a.current = nil
        a.mu.Unlock()
        // Обработать следующий элемент если очередь не пуста
        a.triggerProcessQueue()
    }()

    // 1. Получить аудиофайл (кеш или скачать)
    filePath, err := a.getAudioFile(ctx, cmd)
    if err != nil {
        log.Error("Failed to get audio file", "id", cmd.AnnouncementID, "error", err)
        a.reportStatus(cmd.AnnouncementID, "FAILED", err.Error())
        return
    }

    // 2. Воспроизвести
    a.reportStatus(cmd.AnnouncementID, "PLAYING", "")
    if err := a.playFile(ctx, filePath); err != nil {
        log.Error("Playback failed", "id", cmd.AnnouncementID, "error", err)
        a.reportStatus(cmd.AnnouncementID, "FAILED", err.Error())
        return
    }

    a.reportStatus(cmd.AnnouncementID, "DONE", "")
}
```

### 7.3 Получение аудиофайла (кеш + скачивание)

```go
func (a *PlaybackAgent) getAudioFile(ctx context.Context, cmd *PlayCommand) (string, error) {
    // 1. Проверить локальный кеш
    localPath := filepath.Join(a.config.LocalCacheDir, cmd.AudioCacheID+".mp3")
    if _, err := os.Stat(localPath); err == nil {
        log.Debug("Audio cache hit", "id", cmd.AudioCacheID)
        return localPath, nil
    }

    // 2. Скачать с notification-service
    log.Info("Downloading audio", "id", cmd.AudioCacheID, "url", cmd.AudioURL)

    dlCtx, cancel := context.WithTimeout(ctx,
        time.Duration(a.config.DownloadTimeout)*time.Second)
    defer cancel()

    resp, err := a.httpClient.Get(cmd.AudioURL)  // Запрос к notification-service
    if err != nil {
        return "", fmt.Errorf("download failed: %w", err)
    }
    defer resp.Body.Close()

    if resp.StatusCode != http.StatusOK {
        return "", fmt.Errorf("download HTTP %d", resp.StatusCode)
    }

    // 3. Сохранить в локальный кеш
    tmpPath := localPath + ".tmp"
    f, err := os.Create(tmpPath)
    if err != nil {
        return "", fmt.Errorf("create temp file: %w", err)
    }

    if _, err := io.Copy(f, resp.Body); err != nil {
        f.Close()
        os.Remove(tmpPath)
        return "", fmt.Errorf("write audio: %w", err)
    }
    f.Close()

    if err := os.Rename(tmpPath, localPath); err != nil {
        return "", fmt.Errorf("rename audio: %w", err)
    }

    return localPath, nil
}
```

### 7.4 Воспроизведение через системный плеер

```go
// playFile запускает внешний плеер для воспроизведения MP3
func (a *PlaybackAgent) playFile(ctx context.Context, filePath string) error {
    var cmd *exec.Cmd

    switch a.config.Player {

    case "ffplay":
        args := []string{
            "-nodisp",                           // Без GUI
            "-autoexit",                         // Выйти после окончания
            "-loglevel", "quiet",
            "-volume", fmt.Sprintf("%d", a.config.Volume),
        }
        if a.config.AudioOutputDev != "" {
            args = append(args, "-audio_device", a.config.AudioOutputDev)
        }
        args = append(args, filePath)
        cmd = exec.CommandContext(ctx, "ffplay", args...)

    case "mpv":
        args := []string{
            "--no-video",
            "--really-quiet",
            fmt.Sprintf("--volume=%d", a.config.Volume),
            filePath,
        }
        cmd = exec.CommandContext(ctx, "mpv", args...)

    case "aplay":
        // aplay — только Linux, только WAV.
        // Для MP3 нужна предварительная конвертация через ffmpeg.
        cmd = exec.CommandContext(ctx, "aplay", filePath)

    default:
        return fmt.Errorf("unknown player: %s", a.config.Player)
    }

    // Запустить и ждать завершения
    if err := cmd.Run(); err != nil {
        // Проверить: была ли это нормальная остановка (ctx.Done)
        if ctx.Err() != nil {
            return nil // Намеренная остановка — не ошибка
        }
        return fmt.Errorf("player exited with error: %w", err)
    }

    return nil
}

// reportStatus отправляет статус воспроизведения в ядро
func (a *PlaybackAgent) reportStatus(announcementID, status, errMsg string) {
    payload := AudioStatusPayload{
        AnnouncementID: announcementID,
        Status:         status,
        ErrorMessage:   errMsg,
        ReportedAt:     time.Now(),
    }
    msg := CoreMessage{
        Type:    MsgAudioStatus,
        Payload: mustMarshal(payload),
    }
    select {
    case a.coreSend <- mustMarshal(msg):
    default:
        log.Warn("Core send channel full, audio status dropped")
    }
}
```

### 7.5 Локальный кеш аудиофайлов

```
/var/transora/station-agent/audio-cache/
├── a3f5c9d2e1b847f6.mp3    ← Закешированный аудиофайл
├── b7e2a1d4c8f03912.mp3
└── ...

Размер одного файла: ~50-200 КБ (8-30 секунд речи)
Максимальный размер кеша: настраивается (по умолчанию 500 МБ)
Очистка: LRU при превышении лимита
```

---

## 8. Работа при потере связи с ядром

### 8.1 Поведение компонентов по режимам

| Компонент | ONLINE | DEGRADED | OFFLINE |
|-----------|--------|----------|---------|
| Продажа билетов | ✅ Работает | ⏳ Ожидание (5 сек) → ❌ | ❌ Заблокировано |
| Возврат билетов | ✅ Работает | ⏳ Ожидание | ❌ Заблокировано |
| Расписание (чтение) | ✅ Из ядра | ✅ Из кеша | ✅ Из кеша |
| Табло | ✅ Актуально | ✅ Из кеша | ✅ Из кеша (баннер «Нет связи») |
| Аудио-объявления | ✅ Работает | ✅ Из локального кеша | ✅ Из локального кеша |
| ТСД-посадка | ✅ Онлайн | ✅ Буферизация | ✅ Буферизация |
| Рабочее место диспетчера | ✅ Работает | ⏳ Ожидание | ❌ Только чтение кеша |

### 8.2 Переход между режимами

```
         Потеря пинга или
         ошибка соединения
  ┌──────────────────────────┐
  │                          ▼
ONLINE ──────────────────► DEGRADED ──────────────────► OFFLINE
  ▲        5 сек таймаут               30 сек без         ▲
  │                                    соединения         │
  │                                                        │
  └────────────────────────────────────────────────────────┘
         Успешное переподключение
         + полная синхронизация кеша

Уведомления при смене режима:
  ONLINE → DEGRADED:  Локальным клиентам: { "mode": "DEGRADED", "message": "Связь нестабильна" }
  DEGRADED → OFFLINE: Локальным клиентам: { "mode": "OFFLINE",  "message": "Нет связи с ядром" }
                      Заблокировать продажи
  * → ONLINE:         Локальным клиентам: { "mode": "ONLINE" }
                      Разблокировать продажи
                      Отправить буфер посадки
```

### 8.3 BoardingBuffer — буферизация посадки при офлайн

```go
// BoardingBuffer — сохраняет события посадки при потере связи
type BoardingBuffer struct {
    db *sql.DB
}

// SQLite схема буфера
const boardingBufferSchema = `
CREATE TABLE IF NOT EXISTS boarding_buffer (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id    TEXT NOT NULL,
    trip_id      TEXT NOT NULL,
    scanned_by   TEXT NOT NULL,   -- ID сотрудника с ТСД
    scanned_at   TEXT NOT NULL,   -- ISO8601
    result       TEXT NOT NULL,   -- VALID | ALREADY_USED | NOT_FOUND
    synced       INTEGER NOT NULL DEFAULT 0,
    sync_at      TEXT
);
CREATE INDEX IF NOT EXISTS idx_buffer_synced ON boarding_buffer(synced);
`

func (b *BoardingBuffer) Record(event BoardingEvent) error {
    _, err := b.db.Exec(
        `INSERT INTO boarding_buffer (ticket_id, trip_id, scanned_by, scanned_at, result)
         VALUES (?, ?, ?, ?, ?)`,
        event.TicketID, event.TripID, event.ScannedBy,
        event.ScannedAt.Format(time.RFC3339), event.Result,
    )
    return err
}

// FlushToCore — отправляет накопленные события при восстановлении связи
func (b *BoardingBuffer) FlushToCore(ctx context.Context, coreClient CoreClient) error {
    rows, err := b.db.QueryContext(ctx,
        `SELECT id, ticket_id, trip_id, scanned_by, scanned_at, result
         FROM boarding_buffer WHERE synced = 0 ORDER BY id ASC LIMIT 100`,
    )
    if err != nil {
        return err
    }
    defer rows.Close()

    var ids []int64
    var events []BoardingEvent

    for rows.Next() {
        var id int64
        var e BoardingEvent
        var scannedAt string
        rows.Scan(&id, &e.TicketID, &e.TripID, &e.ScannedBy, &scannedAt, &e.Result)
        e.ScannedAt, _ = time.Parse(time.RFC3339, scannedAt)
        ids = append(ids, id)
        events = append(events, e)
    }

    if len(events) == 0 {
        return nil
    }

    // Отправить пакет в ядро
    if err := coreClient.SendBoardingEvents(ctx, events); err != nil {
        return fmt.Errorf("flush boarding events: %w", err)
    }

    // Пометить как синхронизированные
    now := time.Now().Format(time.RFC3339)
    for _, id := range ids {
        b.db.ExecContext(ctx,
            "UPDATE boarding_buffer SET synced = 1, sync_at = ? WHERE id = ?",
            now, id,
        )
    }

    log.Info("Boarding buffer flushed", "count", len(events))
    return nil
}
```

### 8.4 Обработка запроса посадки при офлайн

```go
// BoardingHandler — обрабатывает сканирование ТСД
func (h *BoardingHandler) Scan(w http.ResponseWriter, r *http.Request) {
    var req BoardingScanRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        http.Error(w, "bad request", http.StatusBadRequest)
        return
    }

    mode := h.mode.Current()

    if mode == ModeOnline {
        // Онлайн: проксируем в ядро для полной проверки
        h.proxy.ServeHTTP(w, r)
        return
    }

    // Офлайн/Degraded: локальная проверка по кешу + буферизация

    // 1. Проверить билет по локальному кешу
    result := h.checkTicketLocally(req.TicketID, req.TripID)

    // 2. Записать в буфер для синхронизации
    event := BoardingEvent{
        TicketID:  req.TicketID,
        TripID:    req.TripID,
        ScannedBy: req.OperatorID,
        ScannedAt: time.Now(),
        Result:    result.Status,
    }
    h.buffer.Record(event)

    // 3. Ответить ТСД-приложению
    writeJSON(w, BoardingScanResponse{
        TicketID:    req.TicketID,
        Status:      result.Status,
        Message:     result.Message,
        DataSource:  "local_cache",
        OfflineMode: true,
    })
}
```

---

## 9. HTTP API агента

Агент слушает на `:8080` и доступен для устройств в локальной сети вокзала.

### 9.1 Эндпоинты

```
── Статус агента ────────────────────────────────────────────────────

GET  /agent/status
     → { station_id, mode, cache_version, uptime_sec, connections }

GET  /agent/mode
     → { mode: "ONLINE"|"DEGRADED"|"OFFLINE", since }

GET  /agent/status/stream                    (SSE)
     → data: { mode, message }              (при смене режима)

── Расписание (из локального кеша) ─────────────────────────────────

GET  /schedule/trips?date=2025-07-15&window_min=180
     → { trips: [TripBoardRow...], source: "cache"|"core", cache_version }

GET  /schedule/trips/{tripId}
     → TripDetail | 404

GET  /schedule/board?type=MAIN
     → BoardState (для табло)

GET  /schedule/board?type=PLATFORM&platform={n}
     → BoardState (для перронного табло)

── Аудио (для PlaybackAgent) ────────────────────────────────────────

GET  /audio/{cacheId}
     → MP3 файл (стриминг, из локального кеша или проксирование)

── Посадка (от ТСД) ─────────────────────────────────────────────────

POST /boarding/scan
     Body: { ticket_id, trip_id, operator_id }
     → { status, message, passenger_name?, seat_number?, offline_mode }

GET  /boarding/buffer/status
     → { pending_count, last_sync_at }

── Проксирование к ядру (всё остальное) ─────────────────────────────

ALL  /*  → reverse proxy → Core API Gateway
```

### 9.2 SSE-стрим режима для клиентов

```javascript
// Кассовое приложение подписывается на изменения режима
const eventSource = new EventSource(
  'http://station-agent:8080/agent/status/stream'
)

eventSource.onmessage = (event) => {
  const { mode, message } = JSON.parse(event.data)

  if (mode === 'OFFLINE') {
    showBanner('Нет связи с ядром. Продажи билетов недоступны.')
    disableSalesUI()
  } else if (mode === 'ONLINE') {
    hideBanner()
    enableSalesUI()
  }
}
```

---

## 10. Конфигурация

### 10.1 Файл `config.yaml`

```yaml
agent:
  station_id: "st-001"                    # UUID вокзала в системе
  listen: ":8080"
  log_level: "info"
  log_file: "/var/log/transora/station-agent.log"

core:
  websocket_url: "wss://core.transora.example.com/ws/stations"
  http_url: "https://core.transora.example.com"
  auth_token: "eyJhbGci..."               # JWT-токен агента (из IAM)
  ping_interval_sec: 30
  ping_timeout_sec: 10
  reconnect_min_sec: 1
  reconnect_max_sec: 60
  # Переход в OFFLINE если нет соединения более N секунд
  offline_threshold_sec: 30

schedule_cache:
  db_path: "/var/transora/station-agent/schedule_cache.db"
  horizon_hours: 48                       # Хранить рейсы на 48 часов вперёд
  cleanup_interval_hours: 1

audio:
  enabled: true
  player: "ffplay"                        # ffplay | mpv | aplay
  volume: 80
  local_cache_dir: "/var/transora/station-agent/audio-cache"
  max_cache_size_mb: 500
  download_timeout_sec: 10
  # Адрес notification-service для скачивания аудио
  notification_service_url: "http://notification-service.internal:8082"

boarding:
  buffer_db_path: "/var/transora/station-agent/boarding_buffer.db"
  # Интервал попытки синхронизации буфера после восстановления связи
  flush_interval_sec: 5
```

---

## 11. Хранилище (SQLite)

Агент использует **два** SQLite-файла:

| Файл | Назначение | Критичность |
|------|-----------|-------------|
| `schedule_cache.db` | Кеш расписания для табло и офлайн-работы | Некритично (восстанавливается при подключении) |
| `boarding_buffer.db` | Буфер событий посадки при офлайн | **Критично** — данные должны быть синхронизированы |

### 11.1 Инициализация схем при старте

```go
func initDatabases(cfg Config) (*ScheduleCache, *BoardingBuffer, error) {
    // schedule_cache.db
    schedDB, err := sql.Open("sqlite3",
        cfg.ScheduleCache.DBPath+"?_journal_mode=WAL&_synchronous=NORMAL")
    if err != nil {
        return nil, nil, fmt.Errorf("schedule cache db: %w", err)
    }

    // boarding_buffer.db — критичные данные, full synchronous
    boardDB, err := sql.Open("sqlite3",
        cfg.Boarding.BufferDBPath+"?_journal_mode=WAL&_synchronous=FULL")
    if err != nil {
        return nil, nil, fmt.Errorf("boarding buffer db: %w", err)
    }

    cache := NewScheduleCache(schedDB)
    if err := cache.InitSchema(); err != nil {
        return nil, nil, err
    }

    buffer := NewBoardingBuffer(boardDB)
    if err := buffer.InitSchema(); err != nil {
        return nil, nil, err
    }

    return cache, buffer, nil
}
```

---

## 12. Мониторинг и диагностика

### 12.1 Метрики (Prometheus)

```go
// Метрики агента (экспортируются на /metrics)
var (
    coreConnectionStatus = prometheus.NewGauge(prometheus.GaugeOpts{
        Name: "station_agent_core_connected",
        Help: "1 = подключён к ядру, 0 = отключён",
    })
    cacheSize = prometheus.NewGauge(prometheus.GaugeOpts{
        Name: "station_agent_cache_trips_total",
        Help: "Количество рейсов в кеше расписания",
    })
    boardingBufferPending = prometheus.NewGauge(prometheus.GaugeOpts{
        Name: "station_agent_boarding_buffer_pending",
        Help: "Количество несинхронизированных событий посадки",
    })
    audioQueueLen = prometheus.NewGauge(prometheus.GaugeOpts{
        Name: "station_agent_audio_queue_length",
        Help: "Длина очереди аудиообъявлений",
    })
    proxyRequestsTotal = prometheus.NewCounterVec(
        prometheus.CounterOpts{
            Name: "station_agent_proxy_requests_total",
        },
        []string{"method", "path", "status"},
    )
    coreReconnectsTotal = prometheus.NewCounter(prometheus.CounterOpts{
        Name: "station_agent_core_reconnects_total",
        Help: "Количество переподключений к ядру",
    })
)
```

### 12.2 Диагностический эндпоинт

```
GET /agent/status

Response:
{
  "station_id": "st-001",
  "station_name": "Красноярск (Центральный)",
  "mode": "ONLINE",
  "mode_since": "2025-07-15T06:00:00+07:00",
  "uptime_sec": 43200,
  "core": {
    "connected": true,
    "url": "wss://core.transora.example.com/...",
    "reconnects_total": 2,
    "last_message_at": "2025-07-15T10:28:00+07:00"
  },
  "cache": {
    "trips_count": 47,
    "last_version": 1721012400123,
    "last_sync_at": "2025-07-15T10:25:00+07:00"
  },
  "audio": {
    "enabled": true,
    "queue_length": 2,
    "currently_playing": "ann-005",
    "cache_files": 312,
    "cache_size_mb": 38.4
  },
  "boarding_buffer": {
    "pending_count": 0,
    "last_flush_at": "2025-07-15T10:27:55+07:00"
  },
  "version": "1.0.0",
  "go_version": "go1.22.3"
}
```

---

## 13. Установка и деплой

### 13.1 Структура проекта

```
station-agent/
├── cmd/
│   └── agent/
│       └── main.go
├── internal/
│   ├── api/
│   │   ├── server.go
│   │   ├── handlers_schedule.go
│   │   ├── handlers_boarding.go
│   │   ├── handlers_audio.go
│   │   └── sse.go
│   ├── core/
│   │   ├── connection.go       ← WebSocket + reconnect loop
│   │   ├── protocol.go         ← Типы сообщений
│   │   └── sync.go             ← Первичная синхронизация
│   ├── cache/
│   │   ├── schedule_cache.go
│   │   └── schema.go
│   ├── proxy/
│   │   └── proxy.go            ← Reverse proxy к ядру
│   ├── audio/
│   │   ├── playback_agent.go
│   │   └── file_cache.go
│   ├── boarding/
│   │   ├── handler.go
│   │   └── buffer.go
│   ├── mode/
│   │   └── manager.go          ← StationMode управление
│   └── config/
│       └── config.go
├── config.yaml.example
├── install-linux.sh
└── install-windows.ps1
```

### 13.2 systemd unit (Linux)

```ini
# /etc/systemd/system/transora-station-agent.service
[Unit]
Description=Transora Station Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=transora
WorkingDirectory=/opt/transora/station-agent
ExecStart=/opt/transora/station-agent/station-agent --config config.yaml
Restart=always
RestartSec=3s

# Переменные окружения (альтернатива config.yaml для секретов)
EnvironmentFile=-/etc/transora/station-agent.env

# Логирование
StandardOutput=journal
StandardError=journal
SyslogIdentifier=transora-station-agent

# Лимиты
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

### 13.3 Windows Service

```powershell
# install-windows.ps1
$serviceName  = "TransoraStationAgent"
$binPath      = "C:\Transora\station-agent\station-agent.exe"
$configPath   = "C:\Transora\station-agent\config.yaml"

New-Service `
  -Name $serviceName `
  -DisplayName "Transora Station Agent" `
  -Description "Transora — агент управления вокзалом" `
  -BinaryPathName "$binPath --config $configPath" `
  -StartupType Automatic

sc.exe failure $serviceName reset= 60 actions= restart/3000/restart/5000/restart/10000

Start-Service $serviceName
Write-Host "Station Agent установлен и запущен."
```

### 13.4 Сборка

```bash
# Linux
GOOS=linux GOARCH=amd64 go build \
  -ldflags="-s -w -X main.Version=1.0.0" \
  -o station-agent ./cmd/agent

# Windows
GOOS=windows GOARCH=amd64 go build \
  -ldflags="-s -w -X main.Version=1.0.0" \
  -o station-agent.exe ./cmd/agent

# Зависимости для аудио (Linux):
apt-get install -y ffmpeg   # для ffplay
# или
apt-get install -y mpv

# Размер бинарника: ~10-14 МБ
```

### 13.5 Чеклист запуска нового вокзала

```
□ 1. Создать вокзал в системе (admin UI) → получить station_id
□ 2. Сгенерировать JWT-токен агента в IAM
□ 3. Установить station-agent как сервис
□ 4. Прописать station_id и auth_token в config.yaml
□ 5. Запустить сервис, проверить /agent/status → mode: ONLINE
□ 6. Убедиться что кеш заполнился: cache.trips_count > 0
□ 7. Зарегистрировать табло в notification-service
□ 8. Открыть табло в браузере, проверить отображение рейсов
□ 9. Проверить аудиосистему: POST /agent/audio/test
□ 10. Открыть кассовое приложение, проверить связь с агентом
```

---

*Следующий документ: `transora-boarding-app.md` — Мобильное приложение учёта посадки (ТСД)*
