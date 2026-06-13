package ru.transora.app.scheduling

import jakarta.validation.Valid
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.scheduling.domain.Trip
import java.time.Instant

@RestController
@RequestMapping("/api/trips")
class TripController(
    private val tripService: TripService,
) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreateTripRequest): TripResponse =
        tripService.createTrip(request).toResponse()

    @GetMapping
    fun list(): List<TripResponse> =
        tripService.listTrips().map { it.toResponse() }
}

data class CreateTripRequest(
    @field:NotBlank val routeNumber: String,
    @field:NotBlank val departureStation: String,
    @field:NotBlank val arrivalStation: String,
    @field:Future val departureTime: Instant,
    val platform: String?,
    @field:Min(1) @field:Max(80) val seatCount: Int,
)

data class TripResponse(
    val id: String,
    val routeNumber: String,
    val departureStation: String,
    val arrivalStation: String,
    val departureTime: Instant,
    val platform: String?,
    val status: String,
)

private fun Trip.toResponse(): TripResponse =
    TripResponse(
        id = id.toString(),
        routeNumber = routeNumber,
        departureStation = departureStation,
        arrivalStation = arrivalStation,
        departureTime = departureTime,
        platform = platform,
        status = status.name,
    )

