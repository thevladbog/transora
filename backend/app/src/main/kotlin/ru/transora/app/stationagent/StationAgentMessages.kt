package ru.transora.app.stationagent

import com.fasterxml.jackson.databind.ObjectMapper
import ru.transora.app.notifications.StationBoardTrip
import ru.transora.scheduling.domain.TripStop
import java.time.Instant
import java.util.UUID

data class StationAgentMessage(
    val type: String,
    val payload: Any,
)

data class SyncRequestPayload(
    val stationId: String,
    val cacheVersionFrom: Long? = null,
    val horizonHours: Int? = null,
)

data class SyncSnapshotPayload(
    val stationId: String,
    val generatedAt: String,
    val version: Long,
    val trips: List<SyncTripPayload>,
)

data class SyncTripPayload(
    val tripId: String,
    val tripNumber: String,
    val tripDate: String,
    val routeId: String? = null,
    val routeName: String,
    val carrierName: String? = null,
    val vehiclePlate: String? = null,
    val driverName: String? = null,
    val status: String,
    val delayMinutes: Int,
    val displayTime: String,
    val directionStop: String,
    val platformNumber: Int? = null,
    val isDeparture: Boolean = true,
    val version: Long,
    val stops: List<SyncStopPayload> = emptyList(),
)

data class SyncStopPayload(
    val stopOrder: Int,
    val stopName: String,
    val stopStatus: String,
    val scheduledDeparture: String? = null,
    val scheduledArrival: String? = null,
)

data class TicketStatusPayload(
    val ticketId: String,
    val ticketNumber: String,
    val tripId: String,
    val status: String,
    val passengerName: String,
    val seatNumber: Int,
    val stationId: String,
    val scannedAt: String,
)

data class AudioPlayPayload(
    val announcementId: String,
    val audioUrl: String,
    val priority: String,
    val text: String,
)

fun StationBoardTrip.toSyncTripPayload(stops: List<TripStop>, version: Long): SyncTripPayload =
    SyncTripPayload(
        tripId = tripId.toString(),
        tripNumber = tripNumber,
        tripDate = displayTime.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
        routeName = routeNumber,
        status = tripStatus.name,
        delayMinutes = delayMinutes ?: 0,
        displayTime = displayTime.toString(),
        directionStop = destinationLabel,
        platformNumber = platform?.toIntOrNull(),
        isDeparture = direction.name != "ARRIVAL",
        version = version,
        stops = stops.map {
            SyncStopPayload(
                stopOrder = it.stopOrder,
                stopName = it.stopName,
                stopStatus = it.stopStatus.name,
                scheduledDeparture = it.scheduledDeparture.toString(),
                scheduledArrival = it.scheduledArrival?.toString(),
            )
        },
    )

fun ObjectMapper.writeStationAgentMessage(type: String, payload: Any): String =
    writeValueAsString(StationAgentMessage(type = type, payload = payload))
