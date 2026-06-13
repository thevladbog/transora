package ru.transora.app.sales

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.sales.domain.CashierShift
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/shifts")
class ShiftController(
    private val shiftService: ShiftService,
) {
    @PostMapping
    fun open(@Valid @RequestBody request: OpenShiftRequest): ShiftResponse =
        shiftService.open(request).toResponse()

    @GetMapping("/open")
    fun listOpen(): List<ShiftResponse> =
        shiftService.listOpen().map { it.toResponse() }

    @PostMapping("/{shiftId}/close")
    fun close(@PathVariable shiftId: UUID): ShiftResponse =
        shiftService.close(shiftId).toResponse()
}

data class OpenShiftRequest(
    @field:NotBlank val stationName: String,
    @field:NotBlank val cashierName: String,
)

data class ShiftResponse(
    val id: String,
    val stationName: String,
    val cashierName: String,
    val status: String,
    val openedAt: Instant,
    val closedAt: Instant?,
)

private fun CashierShift.toResponse(): ShiftResponse =
    ShiftResponse(
        id = id.toString(),
        stationName = stationName,
        cashierName = cashierName,
        status = status.name,
        openedAt = openedAt,
        closedAt = closedAt,
    )

