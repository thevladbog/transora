package ru.transora.app.inventory

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import ru.transora.inventory.domain.Reservation
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/reservations")
@Tag(name = "Reservations", description = "Seat reservations")
class ReservationController(
    private val reservationService: ReservationService,
) {
    @PostMapping
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "Reserve a seat")
    fun reserve(@Valid @RequestBody request: CreateReservationRequest): ReservationResponse =
        reservationService.reserve(request).toResponse()

    @PostMapping("/{reservationId}/confirm")
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "Confirm an active reservation after payment")
    fun confirm(@PathVariable reservationId: UUID): ReservationResponse =
        reservationService.confirm(reservationId).toResponse()

    @PostMapping("/{reservationId}/release")
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "Release an active reservation")
    fun release(@PathVariable reservationId: UUID): ReservationResponse =
        reservationService.release(reservationId).toResponse()

    @PostMapping("/{reservationId}/cancel")
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "Cancel an active reservation")
    fun cancel(@PathVariable reservationId: UUID): ReservationResponse =
        reservationService.cancel(reservationId).toResponse()
}

data class CreateReservationRequest(
    @field:NotNull val tripId: UUID?,
    @field:Min(1) val seatNumber: Int,
    @field:Min(1) val fromStopOrder: Int? = null,
    @field:Min(1) val toStopOrder: Int? = null,
)

data class ReservationResponse(
    val id: String,
    val tripId: String,
    val seatNumber: Int,
    val status: String,
    val expiresAt: Instant,
    val sessionId: String? = null,
    val fromStopOrder: Int? = null,
    val toStopOrder: Int? = null,
)

fun Reservation.toResponse(): ReservationResponse =
    ReservationResponse(
        id = id.toString(),
        tripId = tripId.toString(),
        seatNumber = seatNumber,
        status = status.name,
        expiresAt = expiresAt,
        sessionId = sessionId,
        fromStopOrder = fromStopOrder,
        toStopOrder = toStopOrder,
    )
