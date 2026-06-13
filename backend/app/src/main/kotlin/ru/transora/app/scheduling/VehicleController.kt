package ru.transora.app.scheduling

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
import ru.transora.iam.permissions.Permissions
import ru.transora.scheduling.domain.Vehicle
import java.util.UUID

@RestController
@RequestMapping("/api/vehicles")
@Tag(name = "Vehicles", description = "Vehicle management")
class VehicleController(
    private val vehicleService: VehicleService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun list(): List<VehicleResponse> = vehicleService.list().map { it.toResponse() }

    @GetMapping("/{vehicleId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun get(@PathVariable vehicleId: UUID): VehicleResponse =
        vehicleService.get(vehicleId).toResponse()

    @PostMapping
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    fun create(@Valid @RequestBody request: CreateVehicleRequest): VehicleResponse =
        vehicleService.create(request).toResponse()

    @PatchMapping("/{vehicleId}")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    fun update(
        @PathVariable vehicleId: UUID,
        @Valid @RequestBody request: UpdateVehicleRequest,
    ): VehicleResponse =
        vehicleService.update(vehicleId, request).toResponse()
}

data class VehicleResponse(
    val id: String,
    val carrierId: String,
    val model: String,
    val plateNumber: String,
    val seatLayoutId: String,
    val totalSeats: Int,
    val year: Int?,
    val notes: String?,
    val isActive: Boolean,
)

private fun Vehicle.toResponse() = VehicleResponse(
    id = id.toString(),
    carrierId = carrierId.toString(),
    model = model,
    plateNumber = plateNumber,
    seatLayoutId = seatLayoutId.toString(),
    totalSeats = totalSeats,
    year = year,
    notes = notes,
    isActive = isActive,
)
