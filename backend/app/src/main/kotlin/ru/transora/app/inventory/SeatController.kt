package ru.transora.app.inventory

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/trips/{tripId}/seats")
class SeatController(
    private val seatService: SeatService,
) {
    @GetMapping
    fun list(@PathVariable tripId: UUID): List<SeatAvailabilityResponse> =
        seatService.listSeats(tripId).map { it.toResponse() }
}

data class SeatAvailabilityResponse(
    val tripId: String,
    val seatNumber: Int,
    val status: String,
)

private fun SeatAvailability.toResponse(): SeatAvailabilityResponse =
    SeatAvailabilityResponse(
        tripId = tripId.toString(),
        seatNumber = seatNumber,
        status = status.name,
    )

