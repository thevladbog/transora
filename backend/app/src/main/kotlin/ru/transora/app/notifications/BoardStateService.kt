package ru.transora.app.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import ru.transora.scheduling.domain.BoardDisplayStatus
import java.time.Instant

@Service
class BoardStateService(
    private val boardTripQueryService: BoardTripQueryService,
    private val boardCacheService: BoardCacheService,
    private val displayBoardRepository: DisplayBoardRepository,
    private val objectMapper: ObjectMapper,
    @Lazy private val boardWebSocketHandler: BoardWebSocketHandler,
) {
    fun departures(
        stationCode: String,
        windowBeforeMin: Int? = null,
        windowAfterMin: Int? = null,
    ): BoardDeparturesPayload {
        val departures = boardTripQueryService
            .listForStation(stationCode, windowBeforeMin, windowAfterMin)
            .filter { it.matchesDeparturesBoard() }
            .map { it.toDepartureRow() }
        val payload = BoardDeparturesPayload(
            stationCode = stationCode,
            generatedAt = Instant.now(),
            departures = departures,
        )
        boardCacheService.put(
            boardCacheService.cacheKey(stationCode, BoardCacheType.DEPARTURES),
            payload,
        )
        return payload
    }

    fun arrivals(
        stationCode: String,
        windowBeforeMin: Int? = null,
        windowAfterMin: Int? = null,
    ): BoardArrivalsPayload {
        val arrivals = boardTripQueryService
            .listForStation(stationCode, windowBeforeMin, windowAfterMin)
            .filter { it.matchesArrivalsBoard() }
            .map { it.toArrivalRow() }
        val payload = BoardArrivalsPayload(
            stationCode = stationCode,
            generatedAt = Instant.now(),
            arrivals = arrivals,
        )
        boardCacheService.put(
            boardCacheService.cacheKey(stationCode, BoardCacheType.ARRIVALS),
            payload,
        )
        return payload
    }

    fun platform(
        stationCode: String,
        platformNumber: String,
        windowBeforeMin: Int? = null,
        windowAfterMin: Int? = null,
    ): BoardPlatformPayload {
        val trips = boardTripQueryService
            .listForStation(stationCode, windowBeforeMin, windowAfterMin)
            .filter { it.matchesDeparturesBoard() && it.platform == platformNumber }
            .map { it.toDepartureRow() }
        val payload = BoardPlatformPayload(
            stationCode = stationCode,
            platformNumber = platformNumber,
            generatedAt = Instant.now(),
            trips = trips,
        )
        boardCacheService.put(
            boardCacheService.cacheKey(stationCode, BoardCacheType.PLATFORM, platformNumber),
            payload,
        )
        return payload
    }

    fun refreshForStation(stationCode: String) {
        val code = stationCode.trim().uppercase()
        departures(code)
        arrivals(code)
        displayBoardRepository.listActiveByStation(code).forEach { board ->
            runCatching { broadcastBoardState(board) }
        }
    }

    fun refreshForTrip(stationCode: String?) {
        stationCode?.trim()?.takeIf { it.isNotEmpty() }?.let { refreshForStation(it.uppercase()) }
    }

    private fun broadcastBoardState(board: DisplayBoardRecord) {
        val json = fullStateForBoard(board)
        boardWebSocketHandler.broadcast(board.id, json)
    }

    fun fullStateForBoard(board: DisplayBoardRecord): String {
        when (board.boardType.uppercase()) {
            "PLATFORM" -> {
                val platformNumber = board.platformNumber
                    ?: throw IllegalStateException("Platform board ${board.id} has no platform number")
                platform(board.stationCode, platformNumber)
            }
            "ARRIVALS" -> arrivals(board.stationCode)
            else -> departures(board.stationCode)
        }
        val key = when (board.boardType.uppercase()) {
            "PLATFORM" -> boardCacheService.cacheKey(
                board.stationCode,
                BoardCacheType.PLATFORM,
                board.platformNumber,
            )
            "ARRIVALS" -> boardCacheService.cacheKey(board.stationCode, BoardCacheType.ARRIVALS)
            else -> boardCacheService.cacheKey(board.stationCode, BoardCacheType.DEPARTURES)
        }
        val payloadJson = boardCacheService.get(key) ?: error("Board cache missing for $key")
        displayBoardRepository.saveSnapshot(board.id, payloadJson)
        return wrapWebSocketPayload(board, payloadJson)
    }

    fun wrapWebSocketPayload(board: DisplayBoardRecord, payloadJson: String): String {
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any?>
        val envelope = mapOf(
            "type" to "BOARD_STATE_FULL",
            "boardId" to board.id.toString(),
            "boardType" to board.boardType,
            "stationCode" to board.stationCode,
            "generatedAt" to Instant.now().toString(),
            "payload" to payload,
        )
        return objectMapper.writeValueAsString(envelope)
    }
}

data class BoardDeparturesPayload(
    val stationCode: String,
    val generatedAt: Instant,
    val departures: List<DepartureRow>,
)

data class BoardArrivalsPayload(
    val stationCode: String,
    val generatedAt: Instant,
    val arrivals: List<ArrivalRow>,
)

data class BoardPlatformPayload(
    val stationCode: String,
    val platformNumber: String,
    val generatedAt: Instant,
    val trips: List<DepartureRow>,
)

data class DepartureRow(
    val tripId: String,
    val time: Instant,
    val displayTime: Instant = time,
    val destination: String,
    val route: String,
    val tripNumber: String? = null,
    val platform: String?,
    val displayStatus: BoardDisplayStatus,
    val delayMinutes: Int?,
    val direction: String? = null,
    val stopOrder: Int? = null,
)

data class ArrivalRow(
    val tripId: String,
    val time: Instant,
    val displayTime: Instant = time,
    val origin: String,
    val route: String,
    val tripNumber: String? = null,
    val platform: String?,
    val displayStatus: BoardDisplayStatus,
    val delayMinutes: Int?,
    val direction: String? = null,
    val stopOrder: Int? = null,
)
