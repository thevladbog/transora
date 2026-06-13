package ru.transora.app.scheduling

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import ru.transora.scheduling.domain.Driver
import java.util.UUID

@RestController
@RequestMapping("/api/drivers")
@Tag(name = "Drivers", description = "Driver management")
class DriverController(
    private val driverService: DriverService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun list(): List<DriverResponse> = driverService.list().map { it.toResponse() }

    @GetMapping("/{driverId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun get(@PathVariable driverId: UUID): DriverResponse =
        driverService.get(driverId).toResponse()

    @PostMapping
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    fun create(@Valid @RequestBody request: CreateDriverBody): DriverResponse =
        driverService.create(
            CreateDriverRequest(
                carrierId = request.carrierId,
                fullName = request.fullName,
                licenseNo = request.licenseNo,
                phone = request.phone,
            ),
        ).toResponse()

    @PatchMapping("/{driverId}")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    fun update(
        @PathVariable driverId: UUID,
        @Valid @RequestBody request: UpdateDriverRequest,
    ): DriverResponse =
        driverService.update(driverId, request).toResponse()
}

data class CreateDriverBody(
    val carrierId: UUID,
    @field:NotBlank val fullName: String,
    @field:NotBlank val licenseNo: String,
    val phone: String? = null,
)

data class DriverResponse(
    val id: String,
    val carrierId: String,
    val fullName: String,
    val licenseNo: String,
    val phone: String?,
    val isActive: Boolean,
)

private fun Driver.toResponse() = DriverResponse(
    id = id.toString(),
    carrierId = carrierId.toString(),
    fullName = fullName,
    licenseNo = licenseNo,
    phone = phone,
    isActive = isActive,
)
