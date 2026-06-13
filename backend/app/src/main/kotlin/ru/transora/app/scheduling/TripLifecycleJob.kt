package ru.transora.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.transora.scheduling.domain.TripStatus
import java.time.Clock
import java.time.Duration

@Component
@Profile("!test")
@ConditionalOnProperty(
    prefix = "transora.scheduling",
    name = ["lifecycle-auto-advance"],
    havingValue = "true",
)
class TripLifecycleJob(
    private val tripRepository: TripRepository,
    private val tripService: TripService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${transora.scheduling.lifecycle-interval-ms:60000}")
    fun advanceTripLifecycle() {
        val now = Clock.systemUTC().instant()
        val candidates = tripRepository.listFiltered(
            TripFilter(from = now.minus(Duration.ofHours(12)), to = now.plus(Duration.ofHours(48)), limit = 500),
        )

        candidates.forEach { trip ->
            val target = when (trip.status) {
                TripStatus.PLANNED -> if (trip.departureTime.isBefore(now.plus(Duration.ofHours(24)))) TripStatus.OPEN else null
                TripStatus.OPEN -> if (trip.expectedDepartureTime.isBefore(now.plus(Duration.ofMinutes(30)))) TripStatus.BOARDING else null
                TripStatus.BOARDING -> if (trip.expectedDepartureTime.isBefore(now)) TripStatus.DEPARTED else null
                TripStatus.DEPARTED -> if (trip.expectedDepartureTime.isBefore(now.minus(Duration.ofHours(2)))) TripStatus.IN_TRANSIT else null
                TripStatus.IN_TRANSIT -> if (trip.expectedDepartureTime.isBefore(now.minus(Duration.ofHours(3)))) TripStatus.ARRIVED else null
                TripStatus.ARRIVED -> if (trip.expectedDepartureTime.isBefore(now.minus(Duration.ofHours(4)))) TripStatus.COMPLETED else null
                else -> null
            }
            if (target != null) {
                runCatching {
                    tripService.updateTrip(trip.id, UpdateTripRequest(status = target))
                }.onFailure { ex ->
                    log.debug("Lifecycle skip trip {} -> {}: {}", trip.id, target, ex.message)
                }
            }
        }
    }
}
