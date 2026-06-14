package ru.transora.app.scheduling

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.iam.permissions.Permissions
import java.util.UUID

@RestController
@RequestMapping("/api/admin/stations")
@Tag(name = "Admin Stations", description = "Network station (branch) management")
class StationAdminController(
    private val stationAdminService: StationAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.STATIONS_MANAGE)
    fun list(): List<AdminStationResponse> = stationAdminService.listWithStatus()

    @GetMapping("/{stationId}")
    @RequirePermission(Permissions.STATIONS_MANAGE)
    fun get(@PathVariable stationId: UUID): AdminStationResponse =
        stationAdminService.getWithStatus(stationId)

    @PostMapping
    @RequirePermission(Permissions.STATIONS_MANAGE)
    fun create(@Valid @RequestBody request: CreateAdminStationRequest): AdminStationResponse {
        val actorId = currentPrincipal()?.userId
        return stationAdminService.create(request, actorId)
    }

    @PatchMapping("/{stationId}")
    @RequirePermission(Permissions.STATIONS_MANAGE)
    fun update(
        @PathVariable stationId: UUID,
        @Valid @RequestBody request: UpdateAdminStationRequest,
    ): AdminStationResponse = stationAdminService.update(stationId, request)

    @PostMapping("/{stationId}/provisioning-token")
    @RequirePermission(Permissions.STATIONS_MANAGE)
    @Operation(summary = "Generate one-time code for station-agent provisioning")
    fun provisioningToken(@PathVariable stationId: UUID): ProvisioningTokenResponse {
        val actorId = currentPrincipal()?.userId
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
            )
        return stationAdminService.createProvisioningToken(stationId, actorId)
    }

    @GetMapping("/{stationId}/status")
    @RequirePermission(Permissions.STATIONS_MANAGE)
    fun status(@PathVariable stationId: UUID): StationAgentStatusResponse =
        stationAdminService.agentStatus(stationId)
}

@RestController
@RequestMapping("/api/stations")
@Tag(name = "Station Provisioning", description = "Public station-agent onboarding")
class StationProvisionController(
    private val stationAdminService: StationAdminService,
) {
    @PostMapping("/provision")
    @Operation(summary = "Bind station-agent to a branch using a provisioning code")
    fun provision(@Valid @RequestBody request: StationProvisionRequest): StationProvisionResponse =
        stationAdminService.provision(request.code, request.agentLabel)
}
