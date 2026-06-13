package ru.transora.app.notifications

import org.springframework.stereotype.Service
import ru.transora.app.scheduling.ServiceStationRepository
import ru.transora.app.scheduling.TripRepository
import java.time.Clock
import java.util.UUID

@Service
class AnnouncementAutoEnqueueService(
    private val announcementRepository: AnnouncementRepository,
    private val tripRepository: TripRepository,
    private val serviceStationRepository: ServiceStationRepository,
) {
    fun onTripDelayUpdated(tripId: UUID, delayMinutes: Int?, stationCode: String?) {
        if (delayMinutes == null || delayMinutes <= 0) return
        val trip = tripRepository.findById(tripId) ?: return
        val code = stationCode?.uppercase() ?: trip.departureStationCode.uppercase()
        if (hasQueuedAnnouncement(code, tripId, "DELAY")) return
        enqueue(
            stationCode = code,
            tripId = tripId,
            priority = "HIGH",
            text = "Внимание! Рейс ${trip.tripNumber ?: trip.routeNumber} задерживается на $delayMinutes мин.",
            kind = "DELAY",
        )
    }

    fun onTripDeparted(tripId: UUID, stationCode: String?) {
        val trip = tripRepository.findById(tripId) ?: return
        val code = stationCode?.uppercase() ?: trip.departureStationCode.uppercase()
        if (hasQueuedAnnouncement(code, tripId, "DEPARTED")) return
        enqueue(
            stationCode = code,
            tripId = tripId,
            priority = "HIGH",
            text = "Рейс ${trip.tripNumber ?: trip.routeNumber} отправился.",
            kind = "DEPARTED",
        )
    }

    fun onTripCancelled(tripId: UUID, stationCode: String?) {
        val trip = tripRepository.findById(tripId) ?: return
        val code = stationCode?.uppercase() ?: trip.departureStationCode.uppercase()
        if (hasQueuedAnnouncement(code, tripId, "CANCELLED")) return
        enqueue(
            stationCode = code,
            tripId = tripId,
            priority = "HIGH",
            text = "Рейс ${trip.tripNumber ?: trip.routeNumber} отменён.",
            kind = "CANCELLED",
        )
    }

    fun onTransitGateOpened(tripId: UUID, gateId: UUID, stationId: UUID, availableSeats: List<Int>) {
        val trip = tripRepository.findById(tripId) ?: return
        val code = serviceStationRepository.findById(stationId)?.code?.uppercase() ?: return
        val dedupKey = "TRANSIT_OPEN:$gateId"
        if (hasQueuedAnnouncement(code, tripId, dedupKey)) return
        val seats = availableSeats.joinToString(", ")
        enqueue(
            stationCode = code,
            tripId = tripId,
            priority = "HIGH",
            text = "Открыты транзитные продажи, рейс ${trip.tripNumber ?: trip.routeNumber}, места: $seats",
            kind = dedupKey,
        )
    }

    fun onTransitGateClosed(tripId: UUID, gateId: UUID, stationId: UUID) {
        val trip = tripRepository.findById(tripId) ?: return
        val code = serviceStationRepository.findById(stationId)?.code?.uppercase() ?: return
        val dedupKey = "TRANSIT_CLOSE:$gateId"
        if (hasQueuedAnnouncement(code, tripId, dedupKey)) return
        enqueue(
            stationCode = code,
            tripId = tripId,
            priority = "HIGH",
            text = "Посадка завершена, рейс ${trip.tripNumber ?: trip.routeNumber}",
            kind = dedupKey,
        )
    }

    private fun hasQueuedAnnouncement(stationCode: String, tripId: UUID, kind: String): Boolean =
        announcementRepository.listQueue(stationCode).any { row ->
            row.tripId == tripId && row.textContent.contains(kindMarker(kind))
        }

    private fun enqueue(stationCode: String, tripId: UUID, priority: String, text: String, kind: String) {
        val now = Clock.systemUTC().instant()
        announcementRepository.insert(
            AnnouncementRow(
                id = UUID.randomUUID(),
                stationCode = stationCode,
                tripId = tripId,
                priority = priority,
                textContent = "${kindMarker(kind)} $text",
                status = "QUEUED",
                scheduledAt = now,
                createdAt = now,
            ),
        )
    }

    private fun kindMarker(kind: String): String = "[$kind]"
}
