package ru.transora.app.notifications

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.iam.security.RequirePermission
import ru.transora.app.iam.security.StationScope
import ru.transora.app.scheduling.ServiceStationRepository
import ru.transora.iam.permissions.Permissions
import java.util.UUID

@RestController
@RequestMapping("/api/admin/stations/{stationId}/display-boards")
@Tag(name = "Admin Display Boards", description = "Display boards registered at a station branch")
class DisplayBoardAdminController(
    private val serviceStationRepository: ServiceStationRepository,
    private val displayBoardRepository: DisplayBoardRepository,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    @Operation(summary = "List active display boards for a station branch")
    fun list(@PathVariable stationId: UUID): List<AdminDisplayBoardResponse> {
        val station = serviceStationRepository.findById(stationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found")
        StationScope.assertSameStationOrSuperuser(stationId)
        return displayBoardRepository.listActiveByStation(station.code).map { board ->
            AdminDisplayBoardResponse(
                id = board.id.toString(),
                stationCode = board.stationCode,
                boardType = board.boardType,
                platformNumber = board.platformNumber,
                name = board.name,
                isActive = board.isActive,
                agentId = board.agentId,
                lastSeenAt = board.lastSeenAt?.toString(),
            )
        }
    }
}

data class AdminDisplayBoardResponse(
    val id: String,
    val stationCode: String,
    val boardType: String,
    val platformNumber: String?,
    val name: String,
    val isActive: Boolean,
    val agentId: String?,
    val lastSeenAt: String?,
)
