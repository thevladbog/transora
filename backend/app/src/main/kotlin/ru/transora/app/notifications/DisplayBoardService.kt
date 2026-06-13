package ru.transora.app.notifications

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.iam.security.StationScope
import ru.transora.app.scheduling.ServiceStationRepository
import java.time.Clock
import java.util.UUID

@Service
class DisplayBoardService(
    private val displayBoardRepository: DisplayBoardRepository,
    private val serviceStationRepository: ServiceStationRepository,
) {
    @Transactional
    fun register(request: RegisterDisplayBoardRequest): DisplayBoardResponse {
        val stationCode = resolveStationCode()
        val agentId = request.agentId.trim()
        val existing = displayBoardRepository.findByAgentId(agentId)
        if (existing != null) {
            displayBoardRepository.touchLastSeen(existing.id)
            return existing.toResponse()
        }
        val board = DisplayBoardRecord(
            id = UUID.randomUUID(),
            stationCode = stationCode,
            boardType = request.boardType.uppercase(),
            platformNumber = request.platformNumber,
            name = request.name.trim(),
            isActive = true,
            agentId = agentId,
        )
        displayBoardRepository.insert(board)
        return board.toResponse()
    }

    @Transactional
    fun heartbeat(boardId: UUID) {
        val stationCode = resolveStationCode()
        val board = displayBoardRepository.findById(boardId, stationCode)
            ?: throw NoSuchElementException("Display board $boardId was not found")
        displayBoardRepository.touchLastSeen(board.id)
    }

    private fun resolveStationCode(): String {
        val stationId = StationScope.requireStationId()
        return serviceStationRepository.findById(stationId)?.code?.uppercase() ?: "T1"
    }

    private fun DisplayBoardRecord.toResponse() = DisplayBoardResponse(
        id = id.toString(),
        stationCode = stationCode,
        boardType = boardType,
        platformNumber = platformNumber,
        name = name,
        agentId = agentId,
        isActive = isActive,
        lastSeenAt = lastSeenAt,
    )
}

data class RegisterDisplayBoardRequest(
    val agentId: String,
    val boardType: String,
    val name: String,
    val platformNumber: String? = null,
)

data class DisplayBoardResponse(
    val id: String,
    val stationCode: String,
    val boardType: String,
    val platformNumber: String?,
    val name: String,
    val agentId: String?,
    val isActive: Boolean,
    val lastSeenAt: java.time.Instant?,
)
