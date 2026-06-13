package ru.transora.app.outbox

interface OutboxEventHandler {
    val eventType: String

    fun handle(event: OutboxEvent)
}
