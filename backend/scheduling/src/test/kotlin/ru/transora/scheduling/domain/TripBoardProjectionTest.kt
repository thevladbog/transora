package ru.transora.scheduling.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.transora.scheduling.domain.TripStatus.OPEN
import java.time.Instant
import java.util.UUID

class TripBoardProjectionTest {
    @Test
    fun `maps delayed trip to DELAYED`() {
        val now = Instant.parse("2026-06-14T10:00:00Z")
        val trip = sampleTrip(
            status = OPEN,
            departureTime = now,
            expectedDepartureTime = now.plusSeconds(900),
            delayMinutes = 15,
        )

        assertThat(TripBoardProjection.displayStatus(trip, now)).isEqualTo(BoardDisplayStatus.DELAYED)
        assertThat(TripBoardProjection.effectiveDelayMinutes(trip, now)).isEqualTo(15)
    }

    @Test
    fun `maps boarding trip to BOARDING`() {
        val now = Instant.parse("2026-06-14T10:00:00Z")
        val trip = sampleTrip(status = TripStatus.BOARDING, departureTime = now, expectedDepartureTime = now)

        assertThat(TripBoardProjection.displayStatus(trip, now)).isEqualTo(BoardDisplayStatus.BOARDING)
    }

    private fun sampleTrip(
        status: TripStatus,
        departureTime: Instant,
        expectedDepartureTime: Instant,
        delayMinutes: Int? = null,
    ): Trip =
        Trip(
            id = UUID.randomUUID(),
            routeNumber = "101",
            departureStation = "Terminal 1",
            arrivalStation = "Moscow",
            departureStationCode = "T1",
            departureTime = departureTime,
            expectedDepartureTime = expectedDepartureTime,
            delayMinutes = delayMinutes,
            platform = "2",
            status = status,
            createdAt = departureTime,
        )
}
