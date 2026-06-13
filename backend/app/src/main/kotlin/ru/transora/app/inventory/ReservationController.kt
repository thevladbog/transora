package ru.transora.app.inventory

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.inventory.domain.Reservation
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/reservations")
class ReservationController(
    private val reservationService: ReservationService,
) {
    @PostMapping
    fun reserve(@Valid @RequestBody request: CreateReservationRequest): ReservationResponse =
        reservationService.reserve(request).toResponse()
}

data class CreateReservationRequest(
    @field:NotNull val tripId: UUID?,
    @field:Min(1) val seatNumber: Int,
)

data class ReservationResponse(
    val id: String,
    val tripId: String,
    val seatNumber: Int,
    val status: String,
    val expiresAt: Instant,
)

fun Reservation.toResponse(): ReservationResponse =
    ReservationResponse(
        id = id.toString(),
        tripId = tripId.toString(),
        seatNumber = seatNumber,
        status = status.name,
        expiresAt = expiresAt,
    )

