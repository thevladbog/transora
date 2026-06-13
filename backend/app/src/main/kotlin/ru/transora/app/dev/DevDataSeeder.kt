package ru.transora.app.dev

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.transora.app.inventory.TripInventoryService
import ru.transora.app.scheduling.TripRepository
import ru.transora.scheduling.domain.Trip
import ru.transora.scheduling.domain.TripStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "transora.dev", name = ["seed-data"], havingValue = "true")
class DevDataSeeder(
    private val tripRepository: TripRepository,
    private val tripInventoryService: TripInventoryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        if (tripRepository.count() > 0) {
            log.info("Dev seed skipped: trips already exist")
            return
        }

        val baseTime = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.HOURS)
        val demos = listOf(
            DemoTrip("850", "Moscow", "4", baseTime.plus(Duration.ofMinutes(0)), TripStatus.OPEN, null),
            DemoTrip("101", "St. Petersburg", "2", baseTime.plus(Duration.ofMinutes(15)), TripStatus.OPEN, null),
            DemoTrip("404", "Kazan", "5", baseTime.plus(Duration.ofMinutes(30)), TripStatus.OPEN, 15),
            DemoTrip("220", "Sochi", "1", baseTime.plus(Duration.ofMinutes(45)), TripStatus.OPEN, null),
            DemoTrip("305", "Novosibirsk", "3", baseTime.plus(Duration.ofMinutes(60)), TripStatus.OPEN, null),
            DemoTrip("112", "Yekaterinburg", "4", baseTime.plus(Duration.ofMinutes(75)), TripStatus.OPEN, null),
            DemoTrip("550", "Nizhny Novgorod", "2", baseTime.plus(Duration.ofMinutes(90)), TripStatus.BOARDING, null),
            DemoTrip("202", "Samara", "6", baseTime.plus(Duration.ofMinutes(105)), TripStatus.PLANNED, null),
        )

        demos.forEach { demo ->
            val departureTime = demo.departureTime
            val expectedDepartureTime = demo.delayMinutes?.let { departureTime.plus(Duration.ofMinutes(it.toLong())) }
                ?: departureTime
            val trip = Trip(
                id = UUID.randomUUID(),
                routeNumber = demo.routeNumber,
                departureStation = "Bus Station Terminal 1",
                arrivalStation = demo.destination,
                departureStationCode = "T1",
                departureTime = departureTime,
                expectedDepartureTime = expectedDepartureTime,
                delayMinutes = demo.delayMinutes,
                platform = demo.platform,
                status = demo.status,
                createdAt = Instant.now(Clock.systemUTC()),
            )
            tripRepository.insert(trip)
            tripInventoryService.initializeForTrip(trip.id, 45)
        }

        log.info("Dev seed created {} demo trips", demos.size)
    }

    private data class DemoTrip(
        val routeNumber: String,
        val destination: String,
        val platform: String,
        val departureTime: Instant,
        val status: TripStatus,
        val delayMinutes: Int?,
    )
}
