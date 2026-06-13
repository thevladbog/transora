package ru.transora.scheduling.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant

object TripBoardProjection {
    private val scheduledHorizon: Duration = Duration.ofMinutes(15)

    fun displayStatus(trip: Trip, now: Instant = Clock.systemUTC().instant()): BoardDisplayStatus =
        displayStatusForStop(
            tripStatus = trip.status,
            stopStatus = StopStatus.UPCOMING,
            delayMinutes = trip.delayMinutes,
            forArrivalsBoard = false,
            now = now,
            expectedDepartureTime = trip.expectedDepartureTime,
            departureTime = trip.departureTime,
        )

    fun displayStatusForStop(
        tripStatus: TripStatus,
        stopStatus: StopStatus,
        delayMinutes: Int?,
        forArrivalsBoard: Boolean,
        now: Instant = Clock.systemUTC().instant(),
        expectedDepartureTime: Instant? = null,
        departureTime: Instant? = null,
    ): BoardDisplayStatus {
        if (tripStatus == TripStatus.CANCELLED) return BoardDisplayStatus.CANCELLED

        if (forArrivalsBoard) {
            return when {
                stopStatus == StopStatus.ARRIVED || tripStatus == TripStatus.ARRIVED -> BoardDisplayStatus.ARRIVED
                tripStatus == TripStatus.IN_TRANSIT || stopStatus == StopStatus.UPCOMING -> BoardDisplayStatus.ARRIVING
                tripStatus == TripStatus.DEPARTED -> BoardDisplayStatus.ARRIVING
                delayMinutes != null && delayMinutes > 0 -> BoardDisplayStatus.DELAYED
                else -> BoardDisplayStatus.SCHEDULED
            }
        }

        return when {
            stopStatus == StopStatus.DEPARTED || tripStatus in setOf(
                TripStatus.DEPARTED,
                TripStatus.IN_TRANSIT,
                TripStatus.ARRIVED,
                TripStatus.COMPLETED,
            ) -> BoardDisplayStatus.DEPARTED
            tripStatus == TripStatus.BOARDING -> BoardDisplayStatus.BOARDING
            tripStatus in setOf(TripStatus.PLANNED, TripStatus.OPEN) -> {
                when {
                    delayMinutes != null && delayMinutes > 0 -> BoardDisplayStatus.DELAYED
                    expectedDepartureTime != null &&
                        expectedDepartureTime.isAfter(now.plus(scheduledHorizon)) -> BoardDisplayStatus.SCHEDULED
                    else -> BoardDisplayStatus.ON_TIME
                }
            }
            else -> BoardDisplayStatus.SCHEDULED
        }
    }

    fun effectiveDelayMinutes(trip: Trip, now: Instant = Clock.systemUTC().instant()): Int? {
        if (trip.delayMinutes != null && trip.delayMinutes > 0) {
            return trip.delayMinutes
        }
        if (trip.expectedDepartureTime.isAfter(trip.departureTime)) {
            return Duration.between(trip.departureTime, trip.expectedDepartureTime).toMinutes().toInt()
        }
        return null
    }
}
