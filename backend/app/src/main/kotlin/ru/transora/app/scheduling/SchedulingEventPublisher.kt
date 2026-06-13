package ru.transora.app.scheduling

import org.springframework.stereotype.Component
import ru.transora.app.outbox.OutboxEventRepository
import java.time.Clock
import java.util.UUID

@Component
class SchedulingEventPublisher(
    private val outboxEventRepository: OutboxEventRepository,
) {
    fun publish(
        eventType: String,
        tripId: UUID,
        payload: Map<String, Any?>,
        sourceStationId: UUID? = null,
    ) {
        val envelope = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "event_type" to eventType,
            "occurred_at" to Clock.systemUTC().instant().toString(),
            "source_station_id" to sourceStationId?.toString(),
            "payload" to payload,
        )
        outboxEventRepository.append(
            aggregateType = "trip",
            aggregateId = tripId.toString(),
            eventType = eventType,
            payload = envelope,
        )
    }

    fun publishScheduleUpdated(scheduleId: UUID, routeId: UUID) {
        val envelope = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "event_type" to "scheduling.schedule.updated",
            "occurred_at" to Clock.systemUTC().instant().toString(),
            "payload" to mapOf(
                "scheduleId" to scheduleId.toString(),
                "routeId" to routeId.toString(),
            ),
        )
        outboxEventRepository.append(
            aggregateType = "schedule",
            aggregateId = scheduleId.toString(),
            eventType = "scheduling.schedule.updated",
            payload = envelope,
        )
    }
}
