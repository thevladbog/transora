package ru.transora.app.scheduling

import io.swagger.v3.oas.annotations.Operation
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
import ru.transora.scheduling.domain.Carrier
import ru.transora.scheduling.domain.ContractType
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/carriers")
@Tag(name = "Carriers", description = "Carrier management")
class CarrierController(
    private val carrierService: CarrierService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun list(): List<CarrierResponse> = carrierService.list().map { it.toResponse() }

    @GetMapping("/{carrierId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun get(@PathVariable carrierId: UUID): CarrierResponse =
        carrierService.get(carrierId).toResponse()

    @PostMapping
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    fun create(@Valid @RequestBody request: CreateCarrierRequest): CarrierResponse =
        carrierService.create(request).toResponse()

    @PatchMapping("/{carrierId}")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    fun update(
        @PathVariable carrierId: UUID,
        @Valid @RequestBody request: UpdateCarrierRequest,
    ): CarrierResponse =
        carrierService.update(carrierId, request).toResponse()
}

data class CarrierResponse(
    val id: String,
    val name: String,
    val legalName: String,
    val inn: String,
    val contractType: String,
    val isActive: Boolean,
)

private fun Carrier.toResponse() = CarrierResponse(
    id = id.toString(),
    name = name,
    legalName = legalName,
    inn = inn,
    contractType = contractType.name,
    isActive = isActive,
)
