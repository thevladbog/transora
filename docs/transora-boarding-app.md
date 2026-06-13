# Transora — Boarding App
## Мобильное приложение учёта посадки пассажиров: детальная спецификация

> Версия: 1.0 | Статус: Черновик | Модуль: `boarding-app`

---

## Содержание

1. [Назначение приложения](#1-назначение-приложения)
2. [Ключевые понятия и термины](#2-ключевые-понятия-и-термины)
3. [Бизнес-правила и ограничения](#3-бизнес-правила-и-ограничения)
4. [Целевые устройства](#4-целевые-устройства)
5. [Архитектура приложения](#5-архитектура-приложения)
6. [Локальное хранилище](#6-локальное-хранилище)
7. [Процесс сканирования и посадки](#7-процесс-сканирования-и-посадки)
8. [Офлайн-режим](#8-офлайн-режим)
9. [Экраны и UX-флоу](#9-экраны-и-ux-флоу)
10. [API взаимодействие](#10-api-взаимодействие)
11. [Формат QR-кода и штрихкода](#11-формат-qr-кода-и-штрихкода)
12. [Примеры данных](#12-примеры-данных)
13. [Сборка и деплой](#13-сборка-и-деплой)

---

## 1. Назначение приложения

`boarding-app` — нативное Android-приложение для сотрудников вокзала,
обеспечивающее учёт посадки пассажиров на рейс с помощью сканирования
QR-кодов и штрихкодов на билетах.

Основные обязанности:

- Авторизация сотрудника и выбор рабочего рейса
- Загрузка и локальное кеширование списка пассажиров рейса
- Сканирование QR-кода или штрихкода билета камерой устройства
  или встроенным сканером ТСД
- Мгновенная визуальная и звуковая обратная связь по результату
- Ведение локального счётчика посаженных пассажиров
- Работа в офлайн-режиме с буферизацией и последующей синхронизацией
- Отображение итоговой статистики посадки для диспетчера

**Стек:** Android Native (Kotlin) + Room (SQLite) + Retrofit + ML Kit Barcode Scanner

---

## 2. Ключевые понятия и термины

| Термин | Описание |
|--------|----------|
| **BoardingSession** | Рабочая сессия посадки: один сотрудник, один рейс, одна смена посадки |
| **ScanResult** | Результат сканирования одного билета |
| **PassengerCache** | Локальный кеш списка пассажиров рейса (Room DB) |
| **ScanBuffer** | Буфер результатов сканирования для синхронизации при офлайн |
| **ScanStatus** | Статус проверки билета: VALID / ALREADY_USED / NOT_FOUND / INVALID |
| **StationAgent** | Локальный агент вокзала, к которому подключается приложение |

---

## 3. Бизнес-правила и ограничения

- **BR-BRD-001:** Приложение подключается к `station-agent` по локальной сети
  вокзала, не напрямую к ядру.
- **BR-BRD-002:** Сотрудник должен авторизоваться перед началом работы.
  Сессия сохраняется на устройстве до явного выхода.
- **BR-BRD-003:** Перед началом посадки необходимо выбрать рейс из списка
  активных рейсов вокзала.
- **BR-BRD-004:** При выборе рейса приложение загружает полный список пассажиров
  и кеширует его локально.
- **BR-BRD-005:** Каждый билет может быть отсканирован только один раз.
  Повторное сканирование возвращает статус `ALREADY_USED`.
- **BR-BRD-006:** Проверка дублей выполняется локально по кешу — не зависит
  от состояния сети.
- **BR-BRD-007:** Все результаты сканирования сохраняются локально и
  синхронизируются с ядром при наличии связи.
- **BR-BRD-008:** При офлайн-режиме приложение продолжает работу на основе
  локального кеша пассажиров.
- **BR-BRD-009:** Сотрудник не может изменить статус билета вручную —
  только через сканирование.
- **BR-BRD-010:** По завершении посадки сотрудник закрывает сессию.
  Статистика сохраняется и синхронизируется.

---

## 4. Целевые устройства

### 4.1 Промышленные ТСД (Терминалы сбора данных)

| Производитель | Серия | Особенности |
|--------------|-------|-------------|
| Zebra | TC21, TC26, TC52 | Android 10+, встроенный лазерный сканер |
| Honeywell | EDA51, EDA52 | Android 10+, встроенный 2D-сканер |
| Datalogic | Memor 10, Joya Touch | Android 9+, встроенный сканер |
| Urovo | DT50, i6310 | Android 8+, встроенный сканер |

### 4.2 Обычные смартфоны (резервный вариант)

- Android 8.0 (API 26) и выше
- Камера с автофокусом (для ML Kit сканера)
- Минимум 2 ГБ RAM

### 4.3 Интеграция со встроенным сканером ТСД

Промышленные ТСД передают данные сканирования через
**Android Intent** или **DataWedge** (Zebra) / **ScannerService** (Honeywell):

```kotlin
// Приём данных сканера через BroadcastReceiver
class ScannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Zebra DataWedge
            "com.symbol.datawedge.api.RESULT_ACTION" -> {
                val barcode = intent.getStringExtra("com.symbol.datawedge.data_string")
                barcode?.let { onBarcodeReceived(it) }
            }
            // Honeywell ScannerService
            "com.honeywell.aidc.action.ACTION_DECODE" -> {
                val barcode = intent.getStringExtra("version1")
                barcode?.let { onBarcodeReceived(it) }
            }
            // Generic Intent (универсальный)
            "transora.SCAN_RESULT" -> {
                val barcode = intent.getStringExtra("barcode_data")
                barcode?.let { onBarcodeReceived(it) }
            }
        }
    }
}
```

---

## 5. Архитектура приложения

### 5.1 Слои приложения (Clean Architecture)

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation Layer                    │
│                                                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │  Login      │  │  TripSelect │  │  Boarding       │ │
│  │  Screen     │  │  Screen     │  │  Screen         │ │
│  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘ │
│         │                │                  │          │
│  ┌──────▼──────┐  ┌──────▼──────┐  ┌────────▼────────┐ │
│  │  LoginVM    │  │TripSelectVM │  │  BoardingVM     │ │
│  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘ │
└─────────┼────────────────┼─────────────────┼───────────┘
          │                │                 │
┌─────────▼────────────────▼─────────────────▼───────────┐
│                     Domain Layer                        │
│                                                         │
│  AuthUseCase   TripUseCase   BoardingUseCase            │
│  · login()     · getTrips()  · startSession()           │
│  · logout()    · selectTrip()· scanTicket()             │
│                · syncTrip()  · closeSession()           │
└─────────────────────┬───────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│                      Data Layer                         │
│                                                         │
│  ┌──────────────────┐      ┌──────────────────────────┐ │
│  │  Local (Room DB) │      │  Remote (Retrofit)       │ │
│  │                  │      │                          │ │
│  │  · PassengerDao  │      │  · AuthApi               │ │
│  │  · ScanDao       │      │  · TripApi               │ │
│  │  · SessionDao    │      │  · BoardingApi           │ │
│  └──────────────────┘      └──────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### 5.2 Зависимости (Gradle)

```kotlin
// build.gradle.kts (app)
dependencies {
    // Android KTX + ViewModel
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // UI
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Room (локальная БД)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Retrofit + OkHttp (сеть)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ML Kit — сканер штрихкодов
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    // DI
    implementation("io.insert-koin:koin-android:3.5.0")
    implementation("io.insert-koin:koin-androidx-compose:3.5.0")
}
```

---

## 6. Локальное хранилище

### 6.1 Room Database Schema

```kotlin
// AppDatabase.kt
@Database(
    entities = [
        SessionEntity::class,
        CachedPassengerEntity::class,
        ScanRecordEntity::class,
        TripEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun passengerDao(): PassengerDao
    abstract fun scanDao(): ScanDao
    abstract fun tripDao(): TripDao
}
```

### 6.2 Сущности Room

```kotlin
// Активная сессия посадки
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id: String,          // UUID
    val operatorId: String,
    val operatorName: String,
    val stationId: String,
    val tripId: String,
    val tripNumber: String,
    val tripDate: String,                // "2025-07-15"
    val routeName: String,
    val status: String,                  // ACTIVE | CLOSED
    val startedAt: Long,                 // Unix timestamp
    val closedAt: Long?
)

// Кешированный пассажир рейса
@Entity(
    tableName = "cached_passenger",
    primaryKeys = ["tripId", "ticketId"],
    indices = [
        Index("ticketId", unique = true),
        Index("seatNumber"),
        Index("tripId")
    ]
)
data class CachedPassengerEntity(
    val tripId: String,
    val ticketId: String,
    val ticketNumber: String,
    val seatNumber: Int,
    val passengerName: String,
    val docType: String,
    val docNumber: String,
    val fromStopName: String,
    val toStopName: String,
    // Статус посадки (обновляется при сканировании)
    val boardingStatus: String,          // PENDING | BOARDED | ABSENT
    val boardedAt: Long?,
    // QR данные для быстрого поиска
    val qrPayload: String,               // JSON строка из QR-кода
    val barcode: String,                 // Значение штрихкода (ticket_id)
    val cachedAt: Long
)

// Запись о каждом сканировании
@Entity(
    tableName = "scan_record",
    indices = [
        Index("sessionId"),
        Index("ticketId"),
        Index("synced")
    ]
)
data class ScanRecordEntity(
    @PrimaryKey val id: String,          // UUID
    val sessionId: String,
    val ticketId: String,
    val tripId: String,
    val rawScanData: String,             // Исходные данные со сканера
    val scanResult: String,              // VALID | ALREADY_USED | NOT_FOUND | INVALID_FORMAT
    val passengerName: String?,          // Заполнено при VALID / ALREADY_USED
    val seatNumber: Int?,
    val scannedAt: Long,
    val synced: Boolean = false,
    val syncedAt: Long? = null
)

// Краткая информация о рейсах (для экрана выбора)
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey val tripId: String,
    val tripNumber: String,
    val tripDate: String,
    val routeName: String,
    val departureName: String,
    val arrivalName: String,
    val scheduledDeparture: String,
    val status: String,
    val totalPassengers: Int,
    val boardedCount: Int,
    val cachedAt: Long
)
```

### 6.3 DAO интерфейсы

```kotlin
@Dao
interface PassengerDao {

    @Query("SELECT COUNT(*) FROM cached_passenger WHERE tripId = :tripId")
    suspend fun countPassengers(tripId: String): Int

    @Query("""
        SELECT * FROM cached_passenger
        WHERE tripId = :tripId AND (qrPayload = :data OR barcode = :data OR ticketId = :data)
        LIMIT 1
    """)
    suspend fun findByQrOrBarcode(tripId: String, data: String): CachedPassengerEntity?

    @Query("""
        SELECT * FROM cached_passenger
        WHERE tripId = :tripId
        ORDER BY seatNumber ASC
    """)
    fun observePassengers(tripId: String): Flow<List<CachedPassengerEntity>>

    @Query("""
        SELECT COUNT(*) FROM cached_passenger
        WHERE tripId = :tripId AND boardingStatus = 'BOARDED'
    """)
    fun observeBoardedCount(tripId: String): Flow<Int>

    @Query("""
        UPDATE cached_passenger
        SET boardingStatus = 'BOARDED', boardedAt = :timestamp
        WHERE tripId = :tripId AND ticketId = :ticketId
    """)
    suspend fun markBoarded(tripId: String, ticketId: String, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(passengers: List<CachedPassengerEntity>)

    @Query("DELETE FROM cached_passenger WHERE tripId = :tripId")
    suspend fun clearTrip(tripId: String)
}

@Dao
interface ScanDao {

    @Insert
    suspend fun insert(record: ScanRecordEntity)

    @Query("SELECT * FROM scan_record WHERE synced = 0 ORDER BY scannedAt ASC LIMIT 100")
    suspend fun getPendingSync(): List<ScanRecordEntity>

    @Query("UPDATE scan_record SET synced = 1, syncedAt = :ts WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, ts: Long)

    @Query("""
        SELECT COUNT(*) FROM scan_record
        WHERE sessionId = :sessionId AND scanResult = 'VALID'
    """)
    fun observeValidScans(sessionId: String): Flow<Int>

    @Query("""
        SELECT * FROM scan_record
        WHERE sessionId = :sessionId
        ORDER BY scannedAt DESC
        LIMIT 50
    """)
    fun observeRecentScans(sessionId: String): Flow<List<ScanRecordEntity>>
}
```

---

## 7. Процесс сканирования и посадки

### 7.1 Алгоритм обработки сканирования

```kotlin
// BoardingUseCase.kt
class BoardingUseCase(
    private val passengerDao: PassengerDao,
    private val scanDao: ScanDao,
    private val syncService: SyncService
) {
    suspend fun processScan(
        rawData: String,
        session: SessionEntity
    ): ScanResultModel = withContext(Dispatchers.IO) {

        // 1. Распарсить данные сканирования
        val parsedData = parseQrOrBarcode(rawData)
            ?: return@withContext ScanResultModel(
                status = ScanStatus.INVALID_FORMAT,
                message = "Не удалось распознать билет",
                rawData = rawData
            )

        // 2. Поиск пассажира в локальном кеше
        val passenger = passengerDao.findByQrOrBarcode(
            tripId = session.tripId,
            data = parsedData.ticketId
        )

        val result: ScanResultModel = when {

            // Пассажир не найден в кеше данного рейса
            passenger == null -> ScanResultModel(
                status = ScanStatus.NOT_FOUND,
                message = "Билет не найден в списке рейса",
                rawData = rawData,
                ticketId = parsedData.ticketId
            )

            // Пассажир уже посажен (локальная проверка дублей)
            passenger.boardingStatus == "BOARDED" -> ScanResultModel(
                status = ScanStatus.ALREADY_USED,
                message = "Билет уже использован",
                rawData = rawData,
                ticketId = passenger.ticketId,
                passengerName = passenger.passengerName,
                seatNumber = passenger.seatNumber,
                boardedAt = passenger.boardedAt
            )

            // Всё в порядке — посадка разрешена
            else -> ScanResultModel(
                status = ScanStatus.VALID,
                message = "Посадка разрешена",
                rawData = rawData,
                ticketId = passenger.ticketId,
                passengerName = passenger.passengerName,
                seatNumber = passenger.seatNumber,
                fromStop = passenger.fromStopName,
                toStop = passenger.toStopName
            )
        }

        // 3. Сохранить запись сканирования
        val record = ScanRecordEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            ticketId = parsedData.ticketId,
            tripId = session.tripId,
            rawScanData = rawData,
            scanResult = result.status.name,
            passengerName = result.passengerName,
            seatNumber = result.seatNumber,
            scannedAt = System.currentTimeMillis(),
            synced = false
        )
        scanDao.insert(record)

        // 4. При VALID — обновить статус пассажира локально
        if (result.status == ScanStatus.VALID) {
            passengerDao.markBoarded(
                tripId = session.tripId,
                ticketId = parsedData.ticketId,
                timestamp = System.currentTimeMillis()
            )
        }

        // 5. Попытаться синхронизировать с ядром (non-blocking)
        syncService.triggerSync()

        result
    }
}
```

### 7.2 Статусы сканирования

```kotlin
enum class ScanStatus {
    VALID,           // ✅ Билет действителен, посадка разрешена
    ALREADY_USED,    // ⚠️ Билет уже был отсканирован
    NOT_FOUND,       // ❌ Билет не найден в списке рейса
    INVALID_FORMAT   // ❌ QR/штрихкод не распознан или невалидный формат
}
```

### 7.3 Звуковая и визуальная обратная связь

```kotlin
// FeedbackManager.kt
class FeedbackManager(
    private val context: Context,
    private val soundPool: SoundPool,
    private val vibrator: Vibrator
) {
    // Звуки (загружаются из res/raw/)
    private val soundSuccess = soundPool.load(context, R.raw.beep_success, 1)
    private val soundWarning = soundPool.load(context, R.raw.beep_warning, 1)
    private val soundError   = soundPool.load(context, R.raw.beep_error, 1)

    fun provide(status: ScanStatus) {
        when (status) {
            ScanStatus.VALID -> {
                playSound(soundSuccess)
                vibrate(longArrayOf(0, 80))                     // Один короткий
                flashScreen(Color.Green, durationMs = 600)
            }
            ScanStatus.ALREADY_USED -> {
                playSound(soundWarning)
                vibrate(longArrayOf(0, 100, 100, 100))          // Два коротких
                flashScreen(Color.Yellow, durationMs = 800)
            }
            ScanStatus.NOT_FOUND, ScanStatus.INVALID_FORMAT -> {
                playSound(soundError)
                vibrate(longArrayOf(0, 200, 100, 200))          // Два длинных
                flashScreen(Color.Red, durationMs = 1000)
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
```

---

## 8. Офлайн-режим

### 8.1 Стратегия работы без сети

```
При запуске приложения:
  ├── Есть сеть → загрузить актуальный список пассажиров → сохранить в Room
  └── Нет сети → использовать кешированный список (если есть)
      └── Нет кеша → показать ошибку «Необходимо подключение для первого запуска»

При сканировании:
  ├── Проверка ВСЕГДА по локальному кешу (не зависит от сети)
  ├── Запись результата ВСЕГДА в Room
  └── Синхронизация с ядром:
      ├── Есть сеть → отправить немедленно
      └── Нет сети → добавить в буфер, отправить при восстановлении
```

### 8.2 SyncService — синхронизация буфера

```kotlin
// SyncService.kt
class SyncService(
    private val scanDao: ScanDao,
    private val boardingApi: BoardingApi,
    private val networkMonitor: NetworkMonitor
) {
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // Слушать восстановление сети
        syncScope.launch {
            networkMonitor.isConnected.collect { connected ->
                if (connected) triggerSync()
            }
        }
        // Обработчик триггеров синхронизации
        syncScope.launch {
            _syncTrigger
                .debounce(500)  // Не чаще раза в 500мс
                .collect { performSync() }
        }
    }

    fun triggerSync() {
        _syncTrigger.tryEmit(Unit)
    }

    private suspend fun performSync() {
        if (!networkMonitor.isConnected.value) return

        val pending = scanDao.getPendingSync()
        if (pending.isEmpty()) return

        try {
            val events = pending.map { record ->
                BoardingEventDto(
                    id         = record.id,
                    ticketId   = record.ticketId,
                    tripId     = record.tripId,
                    scanResult = record.scanResult,
                    scannedAt  = record.scannedAt,
                    scannedBy  = record.sessionId
                )
            }

            boardingApi.syncBoardingEvents(BatchBoardingRequest(events))

            scanDao.markSynced(
                ids = pending.map { it.id },
                ts  = System.currentTimeMillis()
            )
            Timber.i("Synced ${pending.size} boarding events")

        } catch (e: Exception) {
            Timber.w("Sync failed: ${e.message}, will retry")
        }
    }
}
```

### 8.3 Индикатор состояния сети в UI

```kotlin
// Отображается в верхней части экрана посадки
@Composable
fun ConnectivityBanner(isOnline: Boolean, pendingCount: Int) {
    AnimatedVisibility(visible = !isOnline || pendingCount > 0) {
        Surface(
            color = if (isOnline) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isOnline) Icons.Default.CloudSync
                                  else Icons.Default.CloudOff,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        !isOnline && pendingCount > 0 ->
                            "Офлайн • $pendingCount событий ожидают синхронизации"
                        !isOnline ->
                            "Нет связи • Работа по локальному кешу"
                        pendingCount > 0 ->
                            "Синхронизация: $pendingCount событий..."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
```

---

## 9. Экраны и UX-флоу

### 9.1 Навигационный граф

```
SplashScreen
    │
    ├── (Нет сохранённой сессии) ──► LoginScreen
    │                                    │
    │                               TripSelectScreen
    │                                    │
    └── (Есть активная сессия) ─────────►│
                                    BoardingScreen
                                         │
                                    SessionSummaryScreen
                                         │
                                    TripSelectScreen (новый рейс)
```

### 9.2 LoginScreen

```kotlin
@Composable
fun LoginScreen(viewModel: LoginViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        // Логотип
        Text(
            text = "TRANSORA",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            text = "Учёт посадки пассажиров",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Адрес Station Agent (преднастроен, но можно изменить)
        OutlinedTextField(
            value = uiState.agentUrl,
            onValueChange = viewModel::onAgentUrlChange,
            label = { Text("Адрес агента вокзала") },
            placeholder = { Text("http://192.168.1.100:8080") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = uiState.login,
            onValueChange = viewModel::onLoginChange,
            label = { Text("Логин") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = uiState.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = viewModel::onLogin,
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Войти", style = MaterialTheme.typography.titleMedium)
            }
        }

        uiState.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }
    }
}
```

### 9.3 TripSelectScreen

```kotlin
@Composable
fun TripSelectScreen(
    onTripSelected: (TripEntity) -> Unit,
    viewModel: TripSelectViewModel = koinViewModel()
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Выберите рейс") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(trips, key = { it.tripId }) { trip ->
                    TripCard(trip = trip, onClick = { onTripSelected(trip) })
                }
            }
        }
    }
}

@Composable
fun TripCard(trip: TripEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Рейс №${trip.tripNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = trip.scheduledDeparture,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${trip.departureName} → ${trip.arrivalName}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("${trip.totalPassengers} пасс.") },
                    leadingIcon = {
                        Icon(Icons.Default.People, contentDescription = null,
                             modifier = Modifier.size(16.dp))
                    }
                )
                if (trip.boardedCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("${trip.boardedCount} посажено") },
                        leadingIcon = {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                 modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
    }
}
```

### 9.4 BoardingScreen — основной экран посадки

```kotlin
@Composable
fun BoardingScreen(
    sessionId: String,
    viewModel: BoardingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lastScan by viewModel.lastScan.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Рейс №${uiState.tripNumber}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = uiState.routeName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Счётчик посаженных
                    Text(
                        text = "${uiState.boardedCount} / ${uiState.totalCount}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                TextButton(
                    onClick = viewModel::closeSession,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Завершить посадку")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Баннер состояния сети
            ConnectivityBanner(
                isOnline = isOnline,
                pendingCount = uiState.pendingSyncCount
            )

            // Результат последнего сканирования
            lastScan?.let { scan ->
                ScanResultCard(result = scan)
            }

            // Область сканирования (камера или ожидание аппаратного сканера)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.useCamera) {
                    CameraScanner(
                        onBarcodeDetected = viewModel::onScanReceived
                    )
                } else {
                    // Режим аппаратного сканера ТСД
                    HardwareScannerPlaceholder()
                }
            }

            // Прогресс посадки
            BoardingProgressBar(
                boarded = uiState.boardedCount,
                total = uiState.totalCount
            )
        }
    }
}

@Composable
fun ScanResultCard(result: ScanResultModel) {
    val (containerColor, icon, title) = when (result.status) {
        ScanStatus.VALID -> Triple(
            Color(0xFF2E7D32),
            Icons.Default.CheckCircle,
            "ПОСАДКА РАЗРЕШЕНА"
        )
        ScanStatus.ALREADY_USED -> Triple(
            Color(0xFFF57F17),
            Icons.Default.Warning,
            "УЖЕ ИСПОЛЬЗОВАН"
        )
        ScanStatus.NOT_FOUND -> Triple(
            Color(0xFFC62828),
            Icons.Default.Cancel,
            "НЕ НАЙДЕН"
        )
        ScanStatus.INVALID_FORMAT -> Triple(
            Color(0xFFC62828),
            Icons.Default.Error,
            "ОШИБКА СКАНИРОВАНИЯ"
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                result.passengerName?.let { name ->
                    Text(
                        text = name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                result.seatNumber?.let { seat ->
                    Text(
                        text = "Место $seat",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun BoardingProgressBar(boarded: Int, total: Int) {
    val progress = if (total > 0) boarded.toFloat() / total else 0f

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Посажено: $boarded", style = MaterialTheme.typography.bodyMedium)
            Text("Осталось: ${total - boarded}", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
```

### 9.5 SessionSummaryScreen — итоги посадки

```
┌────────────────────────────────────────┐
│  ИТОГИ ПОСАДКИ                         │
│                                        │
│  Рейс №101  •  15.07.2025             │
│  Красноярск → Канск                    │
│  ─────────────────────────────────     │
│                                        │
│  ✅  Посажено:          36             │
│  ❌  Не явились:         2             │
│  👥  Всего билетов:     38             │
│                                        │
│  Начало посадки:    07:10              │
│  Конец посадки:     07:28              │
│  Продолжительность: 18 мин            │
│                                        │
│  Синхронизация:     ✅ Все события     │
│                       отправлены       │
│                                        │
│  ─────────────────────────────────     │
│  [  Новый рейс  ]  [  Выйти  ]        │
└────────────────────────────────────────┘
```

---

## 10. API взаимодействие

### 10.1 Retrofit интерфейсы

```kotlin
// AuthApi.kt
interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>
}

data class LoginRequest(val login: String, val password: String)
data class LoginResponse(val token: String, val operatorId: String, val operatorName: String)

// TripApi.kt
interface TripApi {
    // Получить список активных рейсов вокзала
    @GET("schedule/trips")
    suspend fun getActiveTrips(
        @Query("station_id") stationId: String,
        @Query("date") date: String,               // "2025-07-15"
        @Query("status") status: String = "OPEN"
    ): Response<TripsResponse>

    // Получить список пассажиров рейса
    @GET("boarding/trips/{tripId}/passengers")
    suspend fun getPassengers(
        @Path("tripId") tripId: String
    ): Response<PassengersResponse>
}

// BoardingApi.kt
interface BoardingApi {
    // Синхронизация буфера событий посадки
    @POST("boarding/events/batch")
    suspend fun syncBoardingEvents(
        @Body request: BatchBoardingRequest
    ): Response<BatchSyncResponse>

    // Закрыть сессию посадки
    @POST("boarding/sessions/{sessionId}/close")
    suspend fun closeSession(
        @Path("sessionId") sessionId: String,
        @Body summary: SessionSummaryRequest
    ): Response<Unit>
}

data class BatchBoardingRequest(val events: List<BoardingEventDto>)

data class BoardingEventDto(
    val id: String,
    val ticketId: String,
    val tripId: String,
    val scanResult: String,
    val scannedAt: Long,
    val scannedBy: String
)

data class SessionSummaryRequest(
    val sessionId: String,
    val tripId: String,
    val boardedCount: Int,
    val totalCount: Int,
    val closedAt: Long
)
```

### 10.2 OkHttp настройка

```kotlin
// NetworkModule.kt (Koin)
val networkModule = module {

    single {
        val prefs: SharedPreferences = get()

        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Авторизация
            .addInterceptor { chain ->
                val token = prefs.getString("auth_token", null)
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            // Логирование в debug-сборке
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG)
                    HttpLoggingInterceptor.Level.BODY
                else
                    HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    single {
        val prefs: SharedPreferences = get()
        val baseUrl = prefs.getString("agent_url", "http://192.168.1.100:8080") + "/"

        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(get())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    single { get<Retrofit>().create(AuthApi::class.java) }
    single { get<Retrofit>().create(TripApi::class.java) }
    single { get<Retrofit>().create(BoardingApi::class.java) }
}
```

---

## 11. Формат QR-кода и штрихкода

### 11.1 QR-код на билете

QR-код содержит JSON-строку следующего формата:

```json
{
  "v": 1,
  "tid": "ticket-001",
  "trip": "trip-e5f6a7b8",
  "seat": 12,
  "cs": "a3f5"
}
```

| Поле | Описание |
|------|---------|
| `v` | Версия формата (текущая: 1) |
| `tid` | `ticket_id` (UUID) |
| `trip` | `trip_id` (UUID) |
| `seat` | Номер места |
| `cs` | Контрольная сумма (первые 4 символа CRC32 от `tid+trip+seat`) |

### 11.2 Штрихкод (Code 128) на посадочной ведомости

Штрихкод содержит только `ticket_id`:

```
250010000042                  ← ticket_number (на посадочной ведомости)
или
ticket-001-uuid-here          ← ticket_id (на самом билете)
```

### 11.3 Парсинг в приложении

```kotlin
// BarcodeParser.kt
object BarcodeParser {

    data class ParsedTicket(
        val ticketId: String,
        val tripId: String?,
        val seatNumber: Int?
    )

    fun parse(raw: String): ParsedTicket? {
        // Попытка 1: JSON (QR-код)
        runCatching {
            val json = JSONObject(raw)
            val cs = json.getString("cs")
            val tid = json.getString("tid")
            val trip = json.getString("trip")
            val seat = json.getInt("seat")

            // Проверка контрольной суммы
            val expectedCs = crc32short("$tid$trip$seat")
            if (cs != expectedCs) return null

            return ParsedTicket(ticketId = tid, tripId = trip, seatNumber = seat)
        }

        // Попытка 2: UUID (ticket_id напрямую)
        if (isUUID(raw)) {
            return ParsedTicket(ticketId = raw, tripId = null, seatNumber = null)
        }

        // Попытка 3: Номер билета (Code 128 с посадочной ведомости)
        if (raw.matches(Regex("\\d{12,15}"))) {
            return ParsedTicket(ticketId = raw, tripId = null, seatNumber = null)
        }

        return null
    }

    private fun crc32short(input: String): String {
        val crc = CRC32().apply { update(input.toByteArray()) }
        return crc.value.toString(16).takeLast(4)
    }

    private fun isUUID(s: String): Boolean =
        s.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
}
```

---

## 12. Примеры данных

### 12.1 Список пассажиров (ответ API)

```json
{
  "trip_id": "trip-e5f6a7b8",
  "trip_number": "101",
  "trip_date": "2025-07-15",
  "route_name": "Красноярск — Канск",
  "total_passengers": 38,
  "passengers": [
    {
      "ticket_id": "ticket-001",
      "ticket_number": "250010000042",
      "seat_number": 12,
      "passenger_name": "Иванов Иван Иванович",
      "doc_type": "PASSPORT_RF",
      "doc_number": "04 12 123456",
      "from_stop_name": "Красноярск (Центральный)",
      "to_stop_name": "Канск (Автовокзал)",
      "qr_payload": "{\"v\":1,\"tid\":\"ticket-001\",\"trip\":\"trip-e5f6a7b8\",\"seat\":12,\"cs\":\"a3f5\"}",
      "barcode": "250010000042"
    }
  ]
}
```

### 12.2 Результат сканирования (локальная модель)

```kotlin
// VALID
ScanResultModel(
    status = ScanStatus.VALID,
    message = "Посадка разрешена",
    ticketId = "ticket-001",
    passengerName = "Иванов Иван Иванович",
    seatNumber = 12,
    fromStop = "Красноярск (Центральный)",
    toStop = "Канск (Автовокзал)",
    rawData = "{\"v\":1,\"tid\":\"ticket-001\",...}"
)

// ALREADY_USED
ScanResultModel(
    status = ScanStatus.ALREADY_USED,
    message = "Билет уже использован",
    ticketId = "ticket-001",
    passengerName = "Иванов Иван Иванович",
    seatNumber = 12,
    boardedAt = 1721012800000L  // Время первого сканирования
)
```

---

## 13. Сборка и деплой

### 13.1 Конфигурация сборки

```kotlin
// build.gradle.kts
android {
    namespace = "com.transora.boarding"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.transora.boarding"
        minSdk = 26          // Android 8.0
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEFAULT_AGENT_URL", "\"http://192.168.1.100:8080\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "DEFAULT_AGENT_URL", "\"\"")
            // Подпись через keystore
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### 13.2 Разрешения AndroidManifest

```xml
<manifest>
    <!-- Сеть -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Камера (для программного сканера) -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <!-- Вибрация -->
    <uses-permission android:name="android.permission.VIBRATE" />

    <!-- Получение broadcast от аппаратного сканера ТСД -->
    <uses-permission android:name="com.symbol.datawedge.api.permission.datawedge" />

    <application>
        <!-- BroadcastReceiver для аппаратного сканера -->
        <receiver
            android:name=".scanner.ScannerReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.symbol.datawedge.api.RESULT_ACTION" />
                <action android:name="com.honeywell.aidc.action.ACTION_DECODE" />
                <action android:name="transora.SCAN_RESULT" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

### 13.3 Распространение APK

```
Варианты доставки приложения на устройства:

1. MDM (Mobile Device Management)
   · Рекомендуется для парка устройств
   · Централизованное обновление
   · Инструменты: VMware Workspace ONE, SOTI MobiControl

2. Локальный APK-сервер
   · Простой HTTP-сервер в сети вокзала
   · Устройство открывает URL в браузере → скачивает APK
   · Обновление вручную

3. Google Play (закрытый трек)
   · Для организаций с Google Workspace
   · Управляемый Google Play
```

### 13.4 Настройка DataWedge (Zebra)

```json
// Профиль DataWedge для Transora Boarding App
{
  "PROFILE_NAME": "Transora Boarding",
  "PROFILE_ENABLED": true,
  "CONFIG_MODE": "CREATE_IF_NOT_EXIST",
  "APP_LIST": [
    {
      "PACKAGE_NAME": "com.transora.boarding",
      "ACTIVITY_LIST": ["*"]
    }
  ],
  "PARAM_LIST": {
    "scanner_selection": "auto",
    "decoder_ean8": false,
    "decoder_ean13": false,
    "decoder_code128": true,
    "decoder_qrcode": true,
    "intent_output_enabled": true,
    "intent_action": "com.symbol.datawedge.api.RESULT_ACTION",
    "intent_delivery": "2"
  }
}
```

---

*Следующий документ: `transora-iam-service.md` — Сервис авторизации, ролей и управления доступом*
