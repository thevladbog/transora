package ru.transora.app.sales

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import ru.transora.sales.domain.CashierShift
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/shifts")
class ShiftController(
    private val shiftService: ShiftService,
) {
    @PostMapping
    @RequirePermission(Permissions.SHIFTS_MANAGE)
    fun open(@Valid @RequestBody request: OpenShiftRequestDto): ShiftResponse =
        shiftService.open(request.toServiceRequest()).toResponse()

    @GetMapping("/open")
    @RequirePermission(Permissions.SHIFTS_MANAGE)
    fun listOpen(): List<ShiftResponse> =
        shiftService.listOpen().map { it.toResponse() }

    @PostMapping("/{shiftId}/close")
    @RequirePermission(Permissions.SHIFTS_MANAGE)
    fun close(
        @PathVariable shiftId: UUID,
        @RequestBody(required = false) request: CloseShiftRequestDto?,
    ): ShiftResponse =
        shiftService.close(shiftId, request?.toServiceRequest() ?: CloseShiftRequest()).toResponse()
}

data class OpenShiftRequestDto(
    @field:NotBlank val stationName: String,
    @field:NotBlank val cashierName: String,
    val posId: String? = null,
    val openingBalanceCents: Long? = null,
) {
    fun toServiceRequest(): OpenShiftRequest =
        OpenShiftRequest(
            stationName = stationName,
            cashierName = cashierName,
            posId = posId,
            openingBalanceCents = openingBalanceCents ?: 0,
        )
}

data class CloseShiftRequestDto(
    val closingBalanceCents: Long? = null,
) {
    fun toServiceRequest(): CloseShiftRequest =
        CloseShiftRequest(closingBalanceCents = closingBalanceCents)
}

data class ShiftResponse(
    val id: String,
    val stationName: String,
    val cashierName: String,
    val posId: String,
    val openingBalanceCents: Long,
    val closingBalanceCents: Long?,
    val status: String,
    val openedAt: Instant,
    val closedAt: Instant?,
)

private fun CashierShift.toResponse(): ShiftResponse =
    ShiftResponse(
        id = id.toString(),
        stationName = stationName,
        cashierName = cashierName,
        posId = posId,
        openingBalanceCents = openingBalanceCents,
        closingBalanceCents = closingBalanceCents,
        status = status.name,
        openedAt = openedAt,
        closedAt = closedAt,
    )
