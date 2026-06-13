package ru.transora.app.stationagent

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.iam.security.RequirePermission
import ru.transora.app.scheduling.ServiceStationRepository
import ru.transora.iam.permissions.Permissions
import java.util.UUID

@RestController
@RequestMapping("/api/stations/{stationId}/agent")
@Tag(name = "Station Agent", description = "Operational commands for connected station agents")
class StationAgentController(
    private val serviceStationRepository: ServiceStationRepository,
    private val stationAgentEventPublisher: StationAgentEventPublisher,
) {
    @PostMapping("/sync-force")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Force station agent to resync schedule cache")
    fun syncForce(@PathVariable stationId: UUID) {
        serviceStationRepository.findById(stationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown station")
        if (!stationAgentEventPublisher.sendSyncForce(stationId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Station agent is not connected")
        }
    }
}
