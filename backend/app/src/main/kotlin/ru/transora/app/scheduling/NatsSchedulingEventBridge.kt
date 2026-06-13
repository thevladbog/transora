package ru.transora.app.scheduling

/**
 * Extension point for publishing scheduling events to NATS JetStream.
 * In-process outbox remains the source of truth until NATS is enabled.
 */
interface NatsSchedulingEventBridge {
    fun publish(subject: String, envelope: Map<String, Any?>)
}
