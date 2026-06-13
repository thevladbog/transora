# Transora — Hardware Agent
## Go-агент управления кассовым оборудованием: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `hardware-agent`

---

## Содержание

1. [Назначение компонента](#1-назначение-компонента)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Архитектура агента](#3-архитектура-агента)
4. [Поддерживаемое оборудование](#4-поддерживаемое-оборудование)
5. [Интерфейсы устройств (Go)](#5-интерфейсы-устройств-go)
6. [ККТ — Фискальные регистраторы](#6-ккт--фискальные-регистраторы)
7. [Банковский терминал](#7-банковский-терминал)
8. [Принтер билетов](#8-принтер-билетов)
9. [HTTP API агента](#9-http-api-агента)
10. [Конфигурация](#10-конфигурация)
11. [Управление состоянием устройств](#11-управление-состоянием-устройств)
12. [Обработка ошибок и восстановление](#12-обработка-ошибок-и-восстановление)
13. [Установка и деплой](#13-установка-и-деплой)

---

## 1. Назначение компонента

`hardware-agent` — локальный Go-сервис на кассовом рабочем месте, обеспечивающий
взаимодействие кассового приложения (Tauri + React) с физическим оборудованием:
ККТ, банковскими терминалами и термопринтерами.

Основные обязанности:

- Абстракция над производителями оборудования — кассовое приложение работает
  с единым API независимо от модели ККТ или терминала
- Управление жизненным циклом фискальной смены (открытие, закрытие, Z-отчёт)
- Проведение платежей и возвратов через банковский терминал
- Печать билетов и фискальных чеков на термопринтере
- Мониторинг состояния устройств и уведомление кассового приложения
- Журналирование всех операций с оборудованием для аудита и диагностики

Агент работает как **системный сервис** (Windows Service / systemd на Linux) и
запускается независимо от кассового приложения. При запуске Tauri-приложения
агент уже должен быть доступен.

**Стек:** Go 1.22+ | HTTP-сервер (chi) | SQLite (локальный журнал) | Windows/Linux

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **Device** | Абстракция физического устройства с единым интерфейсом |
| **Driver** | Конкретная реализация Device для определённого производителя/модели |
| **FiscalRegistrar** (ФР) | ККТ с фискальным накопителем. Отвечает за фискализацию чеков. |
| **PaymentTerminal** | Банковский терминал для приёма безналичных платежей |
| **TicketPrinter** | Термопринтер для печати билетов (не фискальный) |
| **FiscalShift** | Фискальная смена ККТ. Максимальная продолжительность — 24 часа. |
| **Receipt** | Фискальный чек: SALE (продажа) или REFUND (возврат) |
| **SlipReceipt** | Слип-чек банковского терминала (не фискальный) |
| **DeviceStatus** | Текущее состояние устройства: ONLINE / OFFLINE / ERROR / BUSY |
| **HealthCheck** | Периодическая проверка доступности устройства |

---

## 3. Архитектура агента

### 3.1 Общая схема

```
┌─────────────────────────────────────────────────────────────────────┐
│                     hardware-agent (Go process)                     │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    HTTP API Server (chi)                     │  │
│  │              localhost:9090  (только loopback)               │  │
│  └──────────────────────────┬───────────────────────────────────┘  │
│                             │                                       │
│  ┌──────────────────────────▼───────────────────────────────────┐  │
│  │                     Device Manager                           │  │
│  │  · Реестр устройств (из конфига)                             │  │
│  │  · Health check loop (каждые 10 сек)                         │  │
│  │  · Маршрутизация запросов к нужному драйверу                 │  │
│  │  · SSE-стрим статусов для Tauri-приложения                   │  │
│  └──────┬───────────────────┬────────────────────┬──────────────┘  │
│         │                   │                    │                  │
│  ┌──────▼──────┐   ┌────────▼──────┐   ┌────────▼──────┐          │
│  │  Fiscal     │   │   Payment     │   │   Ticket      │          │
│  │  Registrar  │   │   Terminal    │   │   Printer     │          │
│  │  Driver     │   │   Driver      │   │   Driver      │          │
│  │             │   │               │   │               │          │
│  │ ┌─────────┐ │   │ ┌───────────┐ │   │ ┌───────────┐ │          │
│  │ │  ATOL   │ │   │ │ Ingenico  │ │   │ │ ESC/POS   │ │          │
│  │ │  Driver │ │   │ │  Driver   │ │   │ │  Driver   │ │          │
│  │ └─────────┘ │   │ └───────────┘ │   │ └───────────┘ │          │
│  │ ┌─────────┐ │   │ ┌───────────┐ │   │ ┌───────────┐ │          │
│  │ │  Shtrih │ │   │ │   PAX     │ │   │ │   ZPL     │ │          │
│  │ │  Driver │ │   │ │  Driver   │ │   │ │  Driver   │ │          │
│  │ └─────────┘ │   │ └───────────┘ │   │ └───────────┘ │          │
│  └─────────────┘   └───────────────┘   └───────────────┘          │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │              Operation Journal (SQLite)                      │  │
│  │  · Все операции с оборудованием                              │  │
│  │  · Незавершённые транзакции (для восстановления)             │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
         ▲                    ▲                     ▲
         │ USB/COM            │ TCP/USB             │ USB/COM
         │                    │                     │
   ┌─────┴──────┐     ┌───────┴────────┐    ┌──────┴──────┐
   │  ККТ       │     │   Банковский   │    │  Принтер    │
   │  (АТОЛ /   │     │   терминал     │    │  билетов    │
   │  Штрих-М)  │     │               │    │  (80мм)     │
   └────────────┘     └───────────────┘    └─────────────┘
```

### 3.2 Взаимодействие с Tauri-приложением

```
Tauri App (React UI)
        │
        │  Tauri Command (IPC)
        ▼
Tauri Backend (Rust, тонкий слой)
        │
        │  HTTP запросы к localhost:9090
        │  (через reqwest в Rust или fetch в JS через Tauri allowlist)
        ▼
hardware-agent HTTP API
        │
        │  Синхронный ответ (операции)
        │  SSE стрим (статусы устройств)
        ▼
Физическое оборудование
```

---

## 4. Поддерживаемое оборудование

### 4.1 ККТ (Фискальные регистраторы)

| Производитель | Модели | Интерфейс | Протокол/SDK |
|--------------|--------|-----------|--------------|
| **АТОЛ** | 11Ф, 15Ф, 25Ф, 30Ф, 55Ф, 77Ф, 90Ф | USB, COM, Ethernet | АТОЛ SDK (DLL/SO) |
| **Штрих-М** | ШТРИХ-М-ФА, ШТРИХ-ЛАЙТ-ФА, ШТРИХ-NANO-Ф | USB, COM | ФРМаркет SDK (DLL/SO) |

> **Примечание:** SDK производителей — это Windows DLL или Linux .so библиотеки.
> Go взаимодействует с ними через CGO или через subprocess-обёртку.
> Рекомендуется subprocess-подход: запустить тонкий .NET/Java wrapper,
> общающийся с DLL, и взаимодействовать с ним через stdin/stdout или TCP.

### 4.2 Банковские терминалы

| Производитель | Модели | Интерфейс | Протокол |
|--------------|--------|-----------|---------|
| **Ingenico** | DESK/3500, MOVE/3500 | USB, COM, Ethernet | UCS SmartSale / собственный |
| **PAX** | A80, S900, A920 | USB, Ethernet | PAXSTORE SDK / JSON API |
| **VeriFone** | VX520, VX675 | COM, USB | SmartSale API |
| **Сбер** (Эвотор) | PAX-based | USB, Ethernet | Сбер Terminals SDK |

### 4.3 Принтеры билетов

| Тип | Протокол | Примеры моделей |
|-----|---------|----------------|
| Термопринтер 80мм | ESC/POS | Epson TM-T20, ATOL RP-326, Custom VKP-80 |
| Термопринтер 80мм | ZPL | Zebra ZD220, GK420d |

---

## 5. Интерфейсы устройств (Go)

### 5.1 Базовый интерфейс Device

```go
package device

import "context"

// DeviceStatus — текущее состояние устройства
type DeviceStatus struct {
    ID          string      `json:"id"`
    Type        DeviceType  `json:"type"`
    Model       string      `json:"model"`
    Status      Status      `json:"status"`   // ONLINE | OFFLINE | ERROR | BUSY
    ErrorCode   string      `json:"error_code,omitempty"`
    ErrorMsg    string      `json:"error_msg,omitempty"`
    LastCheckAt time.Time   `json:"last_check_at"`
    Extra       interface{} `json:"extra,omitempty"` // Специфичные данные устройства
}

type Status string
const (
    StatusOnline  Status = "ONLINE"
    StatusOffline Status = "OFFLINE"
    StatusError   Status = "ERROR"
    StatusBusy    Status = "BUSY"
)

type DeviceType string
const (
    DeviceTypeFiscal   DeviceType = "FISCAL_REGISTRAR"
    DeviceTypePayment  DeviceType = "PAYMENT_TERMINAL"
    DeviceTypePrinter  DeviceType = "TICKET_PRINTER"
)

// Device — базовый интерфейс для всех устройств
type Device interface {
    // ID возвращает уникальный идентификатор устройства из конфига
    ID() string
    // Type возвращает тип устройства
    Type() DeviceType
    // Connect устанавливает соединение с устройством
    Connect(ctx context.Context) error
    // Disconnect закрывает соединение
    Disconnect() error
    // HealthCheck проверяет доступность без изменения состояния
    HealthCheck(ctx context.Context) (*DeviceStatus, error)
    // Status возвращает последний известный статус без обращения к устройству
    Status() *DeviceStatus
}
```

### 5.2 Интерфейс FiscalRegistrar

```go
// FiscalRegistrar — интерфейс фискального регистратора
type FiscalRegistrar interface {
    Device

    // --- Смена ---
    // OpenShift открывает фискальную смену
    OpenShift(ctx context.Context, req OpenShiftRequest) (*ShiftInfo, error)
    // CloseShift закрывает смену и печатает Z-отчёт
    CloseShift(ctx context.Context) (*ZReportInfo, error)
    // GetShiftInfo возвращает информацию о текущей смене
    GetShiftInfo(ctx context.Context) (*ShiftInfo, error)

    // --- Чеки ---
    // PrintSaleReceipt печатает чек продажи, возвращает фискальные реквизиты
    PrintSaleReceipt(ctx context.Context, req SaleReceiptRequest) (*FiscalReceiptResult, error)
    // PrintRefundReceipt печатает чек возврата
    PrintRefundReceipt(ctx context.Context, req RefundReceiptRequest) (*FiscalReceiptResult, error)
    // PrintXReport печатает X-отчёт (промежуточный, без закрытия смены)
    PrintXReport(ctx context.Context) error
}

// --- Запросы и ответы ---

type OpenShiftRequest struct {
    CashierName string `json:"cashier_name"`
    CashierINN  string `json:"cashier_inn,omitempty"`
}

type ShiftInfo struct {
    ShiftNumber    int       `json:"shift_number"`
    Status         string    `json:"status"` // OPEN | CLOSED | EXPIRED
    OpenedAt       time.Time `json:"opened_at"`
    ReceiptCount   int       `json:"receipt_count"`
    SalesTotal     int64     `json:"sales_total"`     // В копейках
    RefundsTotal   int64     `json:"refunds_total"`
}

type SaleReceiptRequest struct {
    Items        []ReceiptItem `json:"items"`
    TotalAmount  int64         `json:"total_amount"`   // В копейках
    PaymentType  PaymentType   `json:"payment_type"`   // CASH | CARD
    CashReceived int64         `json:"cash_received,omitempty"`
    CashierName  string        `json:"cashier_name"`
    CustomerEmail string       `json:"customer_email,omitempty"`
    CustomerPhone string       `json:"customer_phone,omitempty"`
}

type ReceiptItem struct {
    Name        string      `json:"name"`        // "Билет №250010000042 Красноярск-Канск"
    Quantity    float64     `json:"quantity"`    // 1.0
    Price       int64       `json:"price"`       // В копейках
    Amount      int64       `json:"amount"`      // quantity * price
    VATType     VATType     `json:"vat_type"`    // NO_VAT | VAT_0 | VAT_10 | VAT_20
    PaymentType PaymentSign `json:"payment_sign"` // FULL_PAYMENT
    SubjectType SubjectSign `json:"subject_sign"` // SERVICE
}

type FiscalReceiptResult struct {
    FiscalSign    string    `json:"fiscal_sign"`     // ФП
    FiscalDocNo   int       `json:"fiscal_doc_no"`   // ФД
    FiscalDriveNo string    `json:"fiscal_drive_no"` // ФН
    KKMSerial     string    `json:"kkm_serial"`
    KKMRegNo      string    `json:"kkm_reg_no"`
    PrintedAt     time.Time `json:"printed_at"`
    ReceiptURL    string    `json:"receipt_url,omitempty"` // Ссылка ОФД на чек
}

type ZReportInfo struct {
    ShiftNumber  int       `json:"shift_number"`
    ClosedAt     time.Time `json:"closed_at"`
    ReceiptCount int       `json:"receipt_count"`
    SalesTotal   int64     `json:"sales_total"`
    RefundsTotal int64     `json:"refunds_total"`
    NetTotal     int64     `json:"net_total"`
}
```

### 5.3 Интерфейс PaymentTerminal

```go
// PaymentTerminal — интерфейс банковского терминала
type PaymentTerminal interface {
    Device

    // Charge проводит оплату картой
    Charge(ctx context.Context, req ChargeRequest) (*PaymentResult, error)
    // Refund возвращает средства по оригинальной транзакции
    Refund(ctx context.Context, req RefundRequest) (*PaymentResult, error)
    // CheckStatus запрашивает статус последней транзакции (для восстановления)
    CheckLastTransaction(ctx context.Context) (*PaymentResult, error)
    // Cancel отменяет текущую незавершённую транзакцию
    Cancel(ctx context.Context) error
}

type ChargeRequest struct {
    Amount      int64  `json:"amount"`       // В копейках
    Currency    string `json:"currency"`     // "643" (RUB)
    OrderID     string `json:"order_id"`     // ID заказа для reconciliation
    Description string `json:"description"`
}

type RefundRequest struct {
    OriginalTransactionID string `json:"original_transaction_id"`
    Amount                int64  `json:"amount"` // В копейках
    OrderID               string `json:"order_id"`
}

type PaymentResult struct {
    TransactionID   string    `json:"transaction_id"`
    AuthCode        string    `json:"auth_code"`
    CardLastFour    string    `json:"card_last_four"`
    CardHolder      string    `json:"card_holder,omitempty"`
    PaymentSystem   string    `json:"payment_system"` // VISA | MIR | MASTERCARD
    TerminalID      string    `json:"terminal_id"`
    TerminalReceiptNo string  `json:"terminal_receipt_no"`
    Status          string    `json:"status"`  // APPROVED | DECLINED | ERROR
    ResponseCode    string    `json:"response_code"`
    ResponseMessage string    `json:"response_message"`
    ProcessedAt     time.Time `json:"processed_at"`
    SlipData        string    `json:"slip_data,omitempty"` // Текст слипа для архива
}
```

### 5.4 Интерфейс TicketPrinter

```go
// TicketPrinter — интерфейс термопринтера билетов
type TicketPrinter interface {
    Device

    // PrintTicket печатает билет из PDF-байт или ESC/POS команд
    PrintTicket(ctx context.Context, req PrintTicketRequest) error
    // PrintRaw отправляет сырые команды на принтер (для диагностики)
    PrintRaw(ctx context.Context, data []byte) error
    // GetPaperStatus проверяет наличие бумаги
    GetPaperStatus(ctx context.Context) (*PaperStatus, error)
    // Cut выполняет обрезку бумаги
    Cut(ctx context.Context, cutType CutType) error
}

type PrintTicketRequest struct {
    // PDF байты или ESC/POS команды — в зависимости от режима принтера
    Data     []byte `json:"data"`
    DataType string `json:"data_type"` // PDF | ESCPOS | ZPL
    Copies   int    `json:"copies"`    // Обычно 1
}

type PaperStatus struct {
    HasPaper     bool `json:"has_paper"`
    PaperLow     bool `json:"paper_low"`      // Бумага заканчивается
    CoverOpen    bool `json:"cover_open"`     // Крышка открыта
    PaperJam     bool `json:"paper_jam"`      // Замятие
}

type CutType string
const (
    CutFull    CutType = "FULL"
    CutPartial CutType = "PARTIAL"
)
```

---

## 6. ККТ — Фискальные регистраторы

### 6.1 Паттерн взаимодействия с SDK через subprocess

SDK АТОЛ и Штрих-М поставляются как Windows DLL (или Linux .so).
Прямой вызов DLL из Go через CGO возможен, но создаёт нестабильность.
Рекомендуемый подход — тонкий subprocess-wrapper:

```
hardware-agent (Go)
        │
        │  stdin/stdout JSON или named pipe
        ▼
fiscal-wrapper (.NET 6, нативный для Windows/Linux)
        │
        │  DLL P/Invoke
        ▼
ATOL10.dll / fptr10.dll (SDK АТОЛ)
        │
        │  USB / COM / TCP
        ▼
ККТ (физическое устройство)
```

**Протокол Go ↔ Wrapper (JSON через stdin/stdout):**

```go
// Запрос
type WrapperRequest struct {
    ID      string          `json:"id"`      // UUID для корреляции
    Command string          `json:"command"` // "open_shift", "print_sale", ...
    Params  json.RawMessage `json:"params"`
}

// Ответ
type WrapperResponse struct {
    ID      string          `json:"id"`
    Success bool            `json:"success"`
    Result  json.RawMessage `json:"result,omitempty"`
    Error   *WrapperError   `json:"error,omitempty"`
}

type WrapperError struct {
    Code    int    `json:"code"`    // Код ошибки SDK
    Message string `json:"message"`
}
```

### 6.2 Реализация АТОЛ Driver

```go
// ATOLDriver — драйвер для ККТ АТОЛ через subprocess wrapper
type ATOLDriver struct {
    config  ATOLConfig
    wrapper *SubprocessWrapper
    mu      sync.Mutex        // ККТ не поддерживает параллельные запросы
    status  *DeviceStatus
}

type ATOLConfig struct {
    WrapperPath   string `yaml:"wrapper_path"`   // Путь к fiscal-wrapper.exe
    Port          string `yaml:"port"`           // COM3 | USB | 192.168.1.100:7778
    BaudRate      int    `yaml:"baud_rate"`      // 115200
    Model         string `yaml:"model"`          // "ATOL_55F"
    CashierName   string `yaml:"cashier_name"`
    OFDChannel    string `yaml:"ofd_channel"`    // "OFD_CHANNEL_PROTO"
}

func (d *ATOLDriver) PrintSaleReceipt(
    ctx context.Context,
    req SaleReceiptRequest,
) (*FiscalReceiptResult, error) {

    d.mu.Lock()
    defer d.mu.Unlock()

    // Формируем команду для wrapper
    params := ATOLSaleParams{
        Items:       mapItems(req.Items),
        Total:       req.TotalAmount,
        PaymentType: mapPaymentType(req.PaymentType),
        CashReceived: req.CashReceived,
        CashierName: req.CashierName,
    }

    // Отправить команду с таймаутом
    resp, err := d.wrapper.Call(ctx, "print_sale_receipt", params)
    if err != nil {
        d.updateStatus(StatusError, err.Error())
        return nil, fmt.Errorf("atol wrapper call failed: %w", err)
    }

    // Разбор ответа
    var result ATOLReceiptResult
    if err := json.Unmarshal(resp.Result, &result); err != nil {
        return nil, fmt.Errorf("atol result parse failed: %w", err)
    }

    // Журнал операции
    d.journal.Log(JournalEntry{
        DeviceID:  d.ID(),
        Operation: "PRINT_SALE_RECEIPT",
        Success:   true,
        Payload:   result,
    })

    return &FiscalReceiptResult{
        FiscalSign:    result.FP,
        FiscalDocNo:   result.FD,
        FiscalDriveNo: result.FN,
        KKMSerial:     result.SerialNumber,
        KKMRegNo:      result.RegNumber,
        PrintedAt:     time.Now(),
    }, nil
}
```

### 6.3 Управление фискальной сменой

```
Жизненный цикл фискальной смены:

  При открытии кассовой смены в sales-service:
  POST /fiscal/shift/open
    → ATOLDriver.OpenShift()
    → Wrapper: "open_shift" { cashier_name, cashier_inn }
    → ККТ печатает Z-отчёт предыдущей смены (если не закрыта)
    → Возвращает { shift_number, opened_at }

  Продажи в течение смены:
  POST /fiscal/receipt/sale  →  PrintSaleReceipt()
  POST /fiscal/receipt/refund → PrintRefundReceipt()

  Промежуточный отчёт (по запросу кассира):
  POST /fiscal/shift/x-report → PrintXReport()

  При закрытии кассовой смены:
  POST /fiscal/shift/close
    → ATOLDriver.CloseShift()
    → Wrapper: "close_shift"
    → ККТ печатает Z-отчёт
    → Возвращает { shift_number, receipt_count, sales_total, ... }

  Автоматическое закрытие при истечении 24 часов:
    → hardware-agent отслеживает время открытия смены
    → За 30 минут до истечения → уведомление кассиру
    → При истечении → принудительное CloseShift() + уведомление
```

### 6.4 Коды ошибок ККТ и реакция

```go
var fiscalErrorHandlers = map[int]ErrorHandler{
    // АТОЛ коды ошибок
    2:   {Action: ActionRetry,  Msg: "ККТ занята, повтор через 1 сек"},
    4:   {Action: ActionNotify, Msg: "Нет бумаги в ККТ"},
    5:   {Action: ActionNotify, Msg: "Крышка ККТ открыта"},
    16:  {Action: ActionNotify, Msg: "Смена превысила 24 часа, требуется закрытие"},
    128: {Action: ActionFatal,  Msg: "Фискальный накопитель не отвечает"},
    192: {Action: ActionNotify, Msg: "Накопитель заполнен более чем на 99%"},
    193: {Action: ActionFatal,  Msg: "Фискальный накопитель исчерпан"},

    // Смена
    246: {Action: ActionReopen, Msg: "Смена уже открыта другим кассиром"},
    247: {Action: ActionNotify, Msg: "Смена закрыта, необходимо открыть"},
}

type ActionType string
const (
    ActionRetry  ActionType = "RETRY"   // Автоматический повтор
    ActionNotify ActionType = "NOTIFY"  // Уведомить кассира, ждать действия
    ActionFatal  ActionType = "FATAL"   // Критическая ошибка, нужен техник
    ActionReopen ActionType = "REOPEN"  // Переоткрыть смену
)
```

---

## 7. Банковский терминал

### 7.1 UCS SmartSale Protocol (Ingenico / VeriFone)

Большинство российских банковских терминалов работают через протокол **UCS SmartSale**
(или его модификации), реализованный через TCP-соединение с терминалом или
через COM-порт.

```go
// UCSDriver — реализация для Ingenico/VeriFone через UCS SmartSale
type UCSDriver struct {
    config UCSConfig
    conn   net.Conn
    mu     sync.Mutex
    status *DeviceStatus
}

type UCSConfig struct {
    Host     string `yaml:"host"`      // "127.0.0.1" (USB-COM) или IP терминала
    Port     int    `yaml:"port"`      // 8888 (типовой порт UCS)
    Timeout  int    `yaml:"timeout"`   // Секунды, обычно 120 для карточных операций
    TermID   string `yaml:"term_id"`   // ID терминала (для журнала)
    Model    string `yaml:"model"`     // "INGENICO_DESK3500"
}

func (d *UCSDriver) Charge(ctx context.Context, req ChargeRequest) (*PaymentResult, error) {
    d.mu.Lock()
    defer d.mu.Unlock()

    // UCS SmartSale: команда оплаты = 0x01
    packet := UCSPacket{
        Command:  0x01,          // PURCHASE
        Amount:   req.Amount,    // В копейках
        Currency: 643,           // RUB
        RRN:      req.OrderID,   // Reference number
    }

    // Отправить пакет
    if err := d.sendPacket(packet); err != nil {
        return nil, fmt.Errorf("terminal send failed: %w", err)
    }

    // Ожидать ответ с учётом таймаута (клиент вводит карту и PIN)
    // Таймаут должен быть достаточным для ввода PIN — обычно 90-120 сек
    respCtx, cancel := context.WithTimeout(ctx, time.Duration(d.config.Timeout)*time.Second)
    defer cancel()

    resp, err := d.readResponse(respCtx)
    if err != nil {
        // При таймауте — запрос статуса
        return d.recoverFromTimeout(ctx)
    }

    return mapUCSResponse(resp), nil
}

// recoverFromTimeout — запрос статуса последней транзакции
// при разрыве соединения или таймауте
func (d *UCSDriver) recoverFromTimeout(ctx context.Context) (*PaymentResult, error) {
    // UCS команда 0x22: STATUS OF LAST TRANSACTION
    packet := UCSPacket{Command: 0x22}
    if err := d.sendPacket(packet); err != nil {
        return nil, ErrTerminalUnreachable
    }
    resp, err := d.readResponse(ctx)
    if err != nil {
        return nil, ErrTransactionStatusUnknown
    }
    result := mapUCSResponse(resp)
    result.ResponseMessage = "RECOVERED_FROM_TIMEOUT"
    return result, nil
}
```

### 7.2 PAX Driver (JSON API)

Терминалы PAX поддерживают более современный JSON API:

```go
type PAXDriver struct {
    config PAXConfig
    client *http.Client
    status *DeviceStatus
}

type PAXConfig struct {
    BaseURL  string `yaml:"base_url"`   // "http://192.168.1.50:10009"
    Timeout  int    `yaml:"timeout"`
    Model    string `yaml:"model"`      // "PAX_A80"
}

func (d *PAXDriver) Charge(ctx context.Context, req ChargeRequest) (*PaymentResult, error) {
    paxReq := map[string]interface{}{
        "CommandType": "SALE",
        "Amount":      req.Amount,
        "ECRRefNum":   req.OrderID,
    }

    httpCtx, cancel := context.WithTimeout(ctx, time.Duration(d.config.Timeout)*time.Second)
    defer cancel()

    var result PAXResponse
    if err := d.post(httpCtx, "/payment", paxReq, &result); err != nil {
        return nil, err
    }

    if result.ResponseCode != "00" {
        return &PaymentResult{
            Status:          "DECLINED",
            ResponseCode:    result.ResponseCode,
            ResponseMessage: result.ResponseMessage,
        }, nil
    }

    return &PaymentResult{
        TransactionID:     result.TransactionID,
        AuthCode:          result.AuthCode,
        CardLastFour:      result.CardLast4,
        PaymentSystem:     result.CardBrand,
        TerminalID:        result.TerminalID,
        TerminalReceiptNo: result.RRN,
        Status:            "APPROVED",
        ProcessedAt:       time.Now(),
    }, nil
}
```

### 7.3 Схема атомарной транзакции (Saga)

```
Продажа:

STEP 1: payment_terminal.Charge()
  Успех  → перейти к STEP 2
  Ошибка → вернуть PAYMENT_FAILED (деньги не списаны)
  Таймаут → CheckLastTransaction()
    → APPROVED  → перейти к STEP 2
    → DECLINED  → вернуть PAYMENT_DECLINED
    → UNKNOWN   → ALERT (ручное урегулирование)

STEP 2: fiscal_registrar.PrintSaleReceipt()
  Успех  → перейти к STEP 3
  Ошибка → КОМПЕНСАЦИЯ:
    payment_terminal.Refund(original_transaction_id)
      Успех компенсации → вернуть FISCAL_ERROR (деньги возвращены)
      Ошибка компенсации → CRITICAL ALERT (деньги списаны, чек не выдан)
        → Записать в operation_journal с флагом needs_manual_resolution
        → Уведомить администратора

STEP 3: ticket_printer.PrintTicket()
  Успех  → транзакция завершена
  Ошибка (нет бумаги / замятие):
    → Уведомить кассира
    → Ожидать устранения и повтора печати
    → Деньги взяты, чек выдан — билет ДОЛЖЕН быть напечатан
    → При невозможности печати: возможен ручной возврат
```

---

## 8. Принтер билетов

### 8.1 ESC/POS Driver

```go
// ESCPOSDriver — универсальный драйвер для ESC/POS термопринтеров
type ESCPOSDriver struct {
    config  ESCPOSConfig
    port    io.ReadWriteCloser // serial.Port или net.Conn
    mu      sync.Mutex
    status  *DeviceStatus
}

type ESCPOSConfig struct {
    ConnectionType string `yaml:"connection_type"` // "usb" | "serial" | "tcp"
    Port           string `yaml:"port"`            // "COM3" | "/dev/ttyUSB0" | "192.168.1.51:9100"
    BaudRate       int    `yaml:"baud_rate"`       // 115200 (для serial)
    Model          string `yaml:"model"`
    PaperWidth     int    `yaml:"paper_width"`     // 80 (мм)
    CodePage       string `yaml:"code_page"`       // "CP866" | "UTF-8"
}

// ESC/POS константы
var (
    ESC = []byte{0x1B}
    GS  = []byte{0x1D}
    LF  = []byte{0x0A}

    // Инициализация принтера
    Init = append(ESC, 0x40)
    // Обрезка бумаги (полная)
    CutFull = append(GS, 0x56, 0x00)
    // Обрезка бумаги (частичная)
    CutPartial = append(GS, 0x56, 0x01)

    // Выравнивание
    AlignLeft   = append(ESC, 0x61, 0x00)
    AlignCenter = append(ESC, 0x61, 0x01)
    AlignRight  = append(ESC, 0x61, 0x02)

    // Жирный
    BoldOn  = append(ESC, 0x45, 0x01)
    BoldOff = append(ESC, 0x45, 0x00)

    // Размер шрифта
    FontNormal = append(GS, 0x21, 0x00)
    FontDouble = append(GS, 0x21, 0x11)
)

func (d *ESCPOSDriver) PrintTicket(ctx context.Context, req PrintTicketRequest) error {
    d.mu.Lock()
    defer d.mu.Unlock()

    var buf bytes.Buffer

    switch req.DataType {
    case "ESCPOS":
        // Данные уже в формате ESC/POS — отправить напрямую
        buf.Write(req.Data)

    case "PDF":
        // Конвертировать PDF в ESC/POS через ghostscript или встроенный рендерер
        escData, err := d.convertPDFtoESCPOS(req.Data)
        if err != nil {
            return fmt.Errorf("pdf conversion failed: %w", err)
        }
        buf.Write(escData)

    default:
        return fmt.Errorf("unsupported data type: %s", req.DataType)
    }

    // Обрезка после печати
    buf.Write(CutPartial)

    // Отправить на принтер
    if _, err := d.port.Write(buf.Bytes()); err != nil {
        d.updateStatus(StatusError, err.Error())
        return fmt.Errorf("print write failed: %w", err)
    }

    return nil
}

func (d *ESCPOSDriver) GetPaperStatus(ctx context.Context) (*PaperStatus, error) {
    d.mu.Lock()
    defer d.mu.Unlock()

    // ESC/POS команда статуса: GS r 1
    statusCmd := append(GS, 0x72, 0x01)
    if _, err := d.port.Write(statusCmd); err != nil {
        return nil, err
    }

    // Читать 1 байт ответа
    resp := make([]byte, 1)
    if err := d.readWithTimeout(resp, 500*time.Millisecond); err != nil {
        return nil, err
    }

    return &PaperStatus{
        HasPaper:  resp[0]&0x0C == 0, // Биты 2-3: 0 = бумага есть
        PaperLow:  resp[0]&0x0C == 4, // Биты 2-3: 01 = почти нет
        CoverOpen: resp[0]&0x04 != 0,
    }, nil
}
```

### 8.2 Генерация ESC/POS данных для билета

```go
// TicketESCPOSBuilder — построитель ESC/POS команд для билета
type TicketESCPOSBuilder struct {
    buf bytes.Buffer
}

func (b *TicketESCPOSBuilder) Build(ticket TicketPrintData) []byte {
    b.write(Init)

    // Шапка
    b.write(AlignCenter)
    b.write(BoldOn)
    b.write(FontDouble)
    b.writeLine("TRANSORA")
    b.write(FontNormal)
    b.writeLine(ticket.CarrierName)
    b.write(BoldOff)
    b.writeSeparator()

    // Номер билета
    b.write(AlignLeft)
    b.write(BoldOn)
    b.writeLine("БИЛЕТ №" + ticket.TicketNumber)
    b.write(BoldOff)
    b.writeSeparator()

    // Маршрут
    b.write(AlignCenter)
    b.write(FontDouble)
    b.writeLine(ticket.FromStop)
    b.writeLine("↓")
    b.writeLine(ticket.ToStop)
    b.write(FontNormal)
    b.writeSeparator()

    // Детали рейса
    b.write(AlignLeft)
    b.writeField("Рейс", ticket.TripNumber)
    b.writeField("Дата", ticket.TripDate)
    b.writeField("Отправление", ticket.DepartureTime)
    b.writeField("Место", fmt.Sprintf("%d", ticket.SeatNumber))
    b.writeSeparator()

    // Пассажир
    b.write(BoldOn)
    b.writeLine(ticket.PassengerName)
    b.write(BoldOff)
    b.writeLine(ticket.DocType + ": " + ticket.DocNumber)
    b.writeSeparator()

    // Стоимость
    b.write(AlignRight)
    b.write(BoldOn)
    b.write(FontDouble)
    b.writeLine(ticket.PriceFormatted)
    b.write(FontNormal)
    b.write(BoldOff)
    b.write(AlignLeft)
    b.writeLine("Оплата: " + ticket.PaymentMethod)
    b.writeSeparator()

    // QR-код
    b.write(AlignCenter)
    b.writeQRCode(ticket.QRData, 6) // Размер модуля: 6
    b.writeLine("")
    b.writeSeparator()

    // Фискальные реквизиты
    b.write(AlignLeft)
    b.writeLine("ФД: " + ticket.FiscalDocNo + "  ФП: " + ticket.FiscalSign)
    b.writeLine("ФН: " + ticket.FiscalDriveNo)
    b.writeLine(ticket.CashierName + "  " + ticket.IssuedAt)

    return b.buf.Bytes()
}

// writeQRCode генерирует ESC/POS команды для QR-кода
func (b *TicketESCPOSBuilder) writeQRCode(data string, moduleSize byte) {
    // ESC/POS QR-код: 3 команды — установить размер, записать данные, напечатать
    dataLen := len(data) + 3
    pL := byte(dataLen & 0xFF)
    pH := byte(dataLen >> 8)

    // Установить размер модуля
    b.buf.Write([]byte{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, moduleSize})
    // Уровень коррекции M
    b.buf.Write([]byte{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x4D})
    // Записать данные
    b.buf.Write([]byte{0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30})
    b.buf.WriteString(data)
    // Напечатать
    b.buf.Write([]byte{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30})
}
```

---

## 9. HTTP API агента

Агент слушает на `localhost:9090`. Доступен только локально.
Аутентификация — через статический токен в заголовке `X-Agent-Token`.

### 9.1 Эндпоинты

```
── Статус устройств ──────────────────────────────────────────────────

GET  /status
     → { devices: [DeviceStatus, ...], agent_version, uptime_sec }

GET  /status/stream                          (SSE)
     → data: { device_id, status, ... }      (при изменении статуса)

── Фискальный регистратор ────────────────────────────────────────────

GET  /fiscal/shift
     → ShiftInfo (текущая смена)

POST /fiscal/shift/open
     Body: { cashier_name }
     → ShiftInfo

POST /fiscal/shift/close
     → ZReportInfo

POST /fiscal/shift/x-report
     → 200 OK

POST /fiscal/receipt/sale
     Body: SaleReceiptRequest
     → FiscalReceiptResult

POST /fiscal/receipt/refund
     Body: RefundReceiptRequest
     → FiscalReceiptResult

── Банковский терминал ───────────────────────────────────────────────

POST /payment/charge
     Body: ChargeRequest
     → PaymentResult

POST /payment/refund
     Body: RefundRequest
     → PaymentResult

GET  /payment/last-transaction
     → PaymentResult (последняя транзакция)

POST /payment/cancel
     → 200 OK | 409 (нечего отменять)

── Принтер билетов ───────────────────────────────────────────────────

POST /printer/print
     Body: PrintTicketRequest
     → 200 OK | 503 (нет бумаги)

GET  /printer/paper-status
     → PaperStatus

POST /printer/cut
     Body: { cut_type: "FULL" | "PARTIAL" }
     → 200 OK

── Журнал операций ───────────────────────────────────────────────────

GET  /journal?limit=50&offset=0&device_type=FISCAL
     → { entries: [JournalEntry, ...], total }
```

### 9.2 Коды ответов

| HTTP код | Значение |
|----------|---------|
| `200` | Операция выполнена успешно |
| `400` | Неверный запрос (валидация) |
| `409` | Конфликт (смена уже открыта, терминал занят) |
| `422` | Операция отклонена устройством (карта отклонена, ошибка ФН) |
| `503` | Устройство недоступно (нет связи, нет бумаги) |
| `504` | Таймаут устройства |

### 9.3 SSE-стрим статусов

```javascript
// Tauri / React: подписка на статусы устройств
const eventSource = new EventSource(
  'http://localhost:9090/status/stream',
  { headers: { 'X-Agent-Token': agentToken } }
)

eventSource.onmessage = (event) => {
  const status = JSON.parse(event.data)
  // { device_id, type, status, error_msg, last_check_at }
  updateDeviceStatus(status)
}

// Типичные события:
// { device_id: "kkm-01", status: "ONLINE" }             — ККТ подключилась
// { device_id: "kkm-01", status: "ERROR", error_msg: "Нет бумаги" }
// { device_id: "terminal-01", status: "BUSY" }          — идёт транзакция
// { device_id: "terminal-01", status: "ONLINE" }        — транзакция завершена
```

---

## 10. Конфигурация

### 10.1 Файл конфигурации `config.yaml`

```yaml
agent:
  listen: "127.0.0.1:9090"
  token: "secret-token-change-in-production"
  log_level: "info"         # debug | info | warn | error
  log_file: "hardware-agent.log"

devices:
  fiscal_registrar:
    enabled: true
    driver: "atol"            # atol | shtrih
    config:
      wrapper_path: "C:\\Transora\\fiscal-wrapper\\fiscal-wrapper.exe"
      port: "USB"             # USB | COM3 | 192.168.1.100:7778
      model: "ATOL_55F"
      cashier_inn: "246012345678"
      ofd_channel: "OFD_CHANNEL_PROTO"
      shift_auto_close: true  # Авто-закрытие смены через 23.5 часа
      shift_warn_minutes: 30  # Предупреждение за N минут до истечения

  payment_terminal:
    enabled: true
    driver: "ucs"             # ucs | pax
    config:
      host: "127.0.0.1"
      port: 8888
      timeout_sec: 120
      model: "INGENICO_DESK3500"
      term_id: "TERM001"

  ticket_printer:
    enabled: true
    driver: "escpos"          # escpos | zpl
    config:
      connection_type: "usb"  # usb | serial | tcp
      port: "USB001"          # или COM4, или 192.168.1.51:9100
      baud_rate: 115200
      model: "EPSON_TM_T20"
      paper_width: 80
      code_page: "CP866"

journal:
  db_path: "C:\\Transora\\hardware-agent\\journal.db"
  retention_days: 90          # Хранить журнал 90 дней

health_check:
  interval_sec: 10            # Интервал проверки устройств
  timeout_sec: 3              # Таймаут одной проверки
```

---

## 11. Управление состоянием устройств

### 11.1 Health Check Loop

```go
// DeviceManager — управление всеми устройствами
type DeviceManager struct {
    devices  map[string]Device
    statuses map[string]*DeviceStatus
    mu       sync.RWMutex
    events   chan DeviceStatus    // SSE-канал для клиентов
}

func (m *DeviceManager) StartHealthCheckLoop(interval time.Duration) {
    ticker := time.NewTicker(interval)
    go func() {
        for range ticker.C {
            for _, device := range m.devices {
                go m.checkDevice(device)
            }
        }
    }()
}

func (m *DeviceManager) checkDevice(device Device) {
    ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
    defer cancel()

    newStatus, err := device.HealthCheck(ctx)
    if err != nil {
        newStatus = &DeviceStatus{
            ID:       device.ID(),
            Type:     device.Type(),
            Status:   StatusOffline,
            ErrorMsg: err.Error(),
        }
    }
    newStatus.LastCheckAt = time.Now()

    m.mu.Lock()
    oldStatus := m.statuses[device.ID()]
    m.statuses[device.ID()] = newStatus
    m.mu.Unlock()

    // Публиковать в SSE только при изменении статуса
    if oldStatus == nil || oldStatus.Status != newStatus.Status {
        m.events <- *newStatus
    }
}
```

### 11.2 Operation Journal (SQLite)

```sql
-- Локальная БД для журнала операций
CREATE TABLE operation_journal (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       TEXT NOT NULL,
    device_type     TEXT NOT NULL,
    operation       TEXT NOT NULL,
    -- PRINT_SALE_RECEIPT | PRINT_REFUND_RECEIPT | OPEN_SHIFT | CLOSE_SHIFT |
    -- PAYMENT_CHARGE | PAYMENT_REFUND | PRINT_TICKET
    success         BOOLEAN NOT NULL,
    request_json    TEXT,
    result_json     TEXT,
    error_code      TEXT,
    error_message   TEXT,
    needs_manual_resolution BOOLEAN NOT NULL DEFAULT 0,
    duration_ms     INTEGER,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_journal_device   ON operation_journal(device_id, created_at DESC);
CREATE INDEX idx_journal_manual   ON operation_journal(needs_manual_resolution)
    WHERE needs_manual_resolution = 1;
CREATE INDEX idx_journal_created  ON operation_journal(created_at DESC);
```

---

## 12. Обработка ошибок и восстановление

### 12.1 Матрица ошибок и реакций

| Ситуация | Автоматическое действие | Действие кассира |
|----------|------------------------|-----------------|
| ККТ не отвечает (Health Check) | 3 попытки переподключения → статус OFFLINE | Проверить USB/кабель |
| ККТ: нет бумаги | Статус ERROR, уведомление | Заправить бумагу, подтвердить |
| ККТ: смена истекает | Уведомление кассиру за 30 мин | Закрыть смену |
| ККТ: смена истекла (24ч) | Авто-закрытие смены | Открыть новую смену |
| Терминал: таймаут транзакции | CheckLastTransaction() | По результату: подтвердить или отменить |
| Терминал: нет связи | Статус OFFLINE, блокировка карточных оплат | Работа только с наличными |
| Принтер: нет бумаги | Статус ERROR, блокировка печати | Заправить бумагу, перепечатать |
| Принтер: замятие | Статус ERROR | Устранить замятие |
| Критическая ошибка (деньги списаны, чек нет) | Запись в journal (needs_manual_resolution=true), алерт | Связаться с администратором |

### 12.2 Алгоритм восстановления при старте агента

```go
func (a *Agent) recoverOnStart() {
    // Найти незавершённые транзакции из прошлой сессии
    unresolved := a.journal.FindUnresolved()

    for _, entry := range unresolved {
        switch entry.Operation {
        case "PAYMENT_CHARGE":
            // Транзакция оплаты была начата — нужно проверить статус
            result, err := a.paymentTerminal.CheckLastTransaction(ctx)
            if err == nil && result.TransactionID == entry.TransactionID {
                if result.Status == "APPROVED" {
                    // Транзакция прошла — помечаем как выполненную
                    a.journal.MarkResolved(entry.ID, result)
                    // Уведомить sales-service через API
                    a.notifySalesService(entry, result)
                } else {
                    // Транзакция не прошла — помечаем как отменённую
                    a.journal.MarkCancelled(entry.ID)
                }
            } else {
                // Статус неизвестен — нужно ручное урегулирование
                a.journal.MarkNeedsManual(entry.ID, "Статус транзакции неизвестен")
            }

        case "PRINT_SALE_RECEIPT":
            // Печать была начата — попытаться перечитать данные из ФН
            // и завершить, если ФН зафиксировал чек
            a.tryRecoverFiscalReceipt(entry)
        }
    }
}
```

---

## 13. Установка и деплой

### 13.1 Структура проекта

```
hardware-agent/
├── cmd/
│   └── agent/
│       └── main.go             ← Точка входа
├── internal/
│   ├── api/
│   │   ├── server.go           ← HTTP-сервер (chi)
│   │   ├── handlers_fiscal.go
│   │   ├── handlers_payment.go
│   │   ├── handlers_printer.go
│   │   └── sse.go              ← SSE-стрим статусов
│   ├── device/
│   │   ├── interfaces.go       ← Device, FiscalRegistrar, и т.д.
│   │   ├── manager.go          ← DeviceManager
│   │   └── drivers/
│   │       ├── atol/
│   │       │   ├── driver.go
│   │       │   └── wrapper.go  ← Subprocess wrapper
│   │       ├── shtrih/
│   │       │   └── driver.go
│   │       ├── ucs/
│   │       │   └── driver.go   ← UCS SmartSale (Ingenico/VeriFone)
│   │       ├── pax/
│   │       │   └── driver.go
│   │       └── escpos/
│   │           ├── driver.go
│   │           ├── builder.go  ← ESC/POS команды
│   │           └── qr.go
│   ├── journal/
│   │   ├── journal.go          ← SQLite операционный журнал
│   │   └── schema.go
│   └── config/
│       └── config.go           ← Загрузка config.yaml
├── fiscal-wrapper/             ← Отдельный .NET проект
│   ├── Program.cs
│   ├── AtolWrapper.cs
│   └── ShtrihWrapper.cs
├── config.yaml.example
├── install-windows.ps1         ← Установка как Windows Service
├── install-linux.sh            ← Установка как systemd unit
└── README.md
```

### 13.2 Windows Service (PowerShell)

```powershell
# install-windows.ps1
$serviceName = "TransoraHardwareAgent"
$binPath = "C:\Transora\hardware-agent\hardware-agent.exe"
$configPath = "C:\Transora\hardware-agent\config.yaml"

# Создать сервис
New-Service `
  -Name $serviceName `
  -DisplayName "Transora Hardware Agent" `
  -Description "Transora — сервис управления кассовым оборудованием" `
  -BinaryPathName "$binPath --config $configPath" `
  -StartupType Automatic

# Настроить перезапуск при сбое
sc.exe failure $serviceName reset= 60 actions= restart/5000/restart/10000/restart/30000

# Запустить
Start-Service $serviceName
```

### 13.3 systemd unit (Linux)

```ini
# /etc/systemd/system/transora-hardware-agent.service
[Unit]
Description=Transora Hardware Agent
After=network.target

[Service]
Type=simple
User=transora
WorkingDirectory=/opt/transora/hardware-agent
ExecStart=/opt/transora/hardware-agent/hardware-agent --config config.yaml
Restart=always
RestartSec=5s
StandardOutput=journal
StandardError=journal

# Ограничения безопасности
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/transora/hardware-agent

[Install]
WantedBy=multi-user.target
```

### 13.4 Сборка

```bash
# Сборка для Windows (x64)
GOOS=windows GOARCH=amd64 go build \
  -ldflags="-s -w -X main.Version=1.0.0" \
  -o hardware-agent.exe \
  ./cmd/agent

# Сборка для Linux (x64)
GOOS=linux GOARCH=amd64 go build \
  -ldflags="-s -w -X main.Version=1.0.0" \
  -o hardware-agent \
  ./cmd/agent

# Размер бинарника: ~8-12 МБ (без зависимостей)
```

---

*Следующий документ: `transora-station-agent.md` — Агент вокзала (WebSocket, офлайн-кеш, аудио)*
