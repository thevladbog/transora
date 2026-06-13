package ru.transora.app.outbox

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxPublisher(
    private val outboxEventRepository: OutboxEventRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val handlers: List<OutboxEventHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByType = handlers.associateBy { it.eventType }

    @Scheduled(fixedDelayString = "\${transora.outbox.poll-interval-ms:1000}")
    @Transactional
    fun publishPendingEvents() {
        val events = outboxEventRepository.fetchUnpublished()
        events.forEach { event ->
            if (processedEventRepository.isProcessed(event.id)) {
                outboxEventRepository.markPublished(event.id)
                return@forEach
            }

            try {
                handlersByType[event.eventType]?.handle(event)
                    ?: log.debug("No handler registered for event type {}", event.eventType)
                processedEventRepository.markProcessed(event.id)
                outboxEventRepository.markPublished(event.id)
            } catch (ex: Exception) {
                log.error("Failed to handle outbox event {} of type {}", event.id, event.eventType, ex)
                throw ex
            }
        }
    }
}

@Component
class OutboxPayloadReader(
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
    fun readMap(event: OutboxEvent): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        objectMapper.readValue(event.payload, Map::class.java) as Map<String, Any?>

    fun readPayload(event: OutboxEvent): Map<String, Any?> {
        val root = readMap(event)
        @Suppress("UNCHECKED_CAST")
        val nested = root["payload"] as? Map<String, Any?>
        return nested ?: root
    }

    fun uuidValue(payload: Map<String, Any?>, key: String): java.util.UUID =
        java.util.UUID.fromString(payload[key].toString())
}
