package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import ru.transora.app.dev.DevStationIds
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StationAgentWebSocketIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @LocalServerPort
    private var port: Int = 0

    private lateinit var adminAuth: String
    private lateinit var agentToken: String
    private val stationId = DevStationIds.TERMINAL_1.toString()

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        agentToken = TestAuth.login(mockMvc, "station_agent", "station_agent", stationId)
    }

    @Test
    fun `station agent receives sync snapshot and trip update events`() {
        val messages = ConcurrentLinkedQueue<String>()
        val connected = CountDownLatch(1)
        val handler = object : TextWebSocketHandler() {
            override fun afterConnectionEstablished(session: WebSocketSession) {
                connected.countDown()
            }

            override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                messages.add(message.payload)
            }
        }

        val headers = WebSocketHttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, TestAuth.bearer(agentToken))
            add("X-Station-ID", stationId)
        }
        val client = StandardWebSocketClient()
        val session = client.execute(handler, headers, URI.create("ws://localhost:$port/ws/stations")).get(10, TimeUnit.SECONDS)
        assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue

        session.sendMessage(
            TextMessage(
                """
                {
                  "type": "sync.request",
                  "payload": {
                    "stationId": "$stationId",
                    "horizonHours": 1
                  }
                }
                """.trimIndent(),
            ),
        )

        val snapshot = pollMessage(messages, 30, TimeUnit.SECONDS) { it.contains("\"type\":\"sync.snapshot\"") }
        assertThat(snapshot).isNotNull
        assertThat(snapshot).contains("\"type\":\"sync.snapshot\"")
        assertThat(snapshot).contains("\"stationId\":\"$stationId\"")

        val departure = Instant.now().plus(4, ChronoUnit.HOURS).toString()
        val tripJson = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "901",
                  "departureStation": "Bus Station Terminal 1",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departure",
                  "platform": "2",
                  "seatCount": 40
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        val tripId = extractJsonField(tripJson, "id")

        waitForOutbox()

        val tripEvent = pollMessage(messages, 15, TimeUnit.SECONDS) {
            it.contains("\"type\":\"trip.created\"") || it.contains("\"type\":\"trip.updated\"")
        }
        assertThat(tripEvent).isNotNull
        assertThat(tripEvent).containsAnyOf("\"type\":\"trip.created\"", "\"type\":\"trip.updated\"")
        assertThat(tripEvent).contains(tripId)

        session.close()
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }

    private fun waitForOutbox() {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        while (System.currentTimeMillis() < deadline) {
            if (outboxEventRepository.countUnpublished() == 0) {
                return
            }
            outboxPublisher.publishPendingEvents()
            Thread.sleep(200)
        }
        throw AssertionError("Outbox events were not published in time")
    }

    private fun pollMessage(
        messages: ConcurrentLinkedQueue<String>,
        timeout: Long,
        unit: TimeUnit,
        predicate: (String) -> Boolean = { true },
    ): String? {
        val skipped = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        try {
            while (System.currentTimeMillis() < deadline) {
                val next = messages.poll()
                if (next != null) {
                    if (predicate(next)) {
                        return next
                    }
                    skipped.add(next)
                } else {
                    Thread.sleep(100)
                }
            }
            return null
        } finally {
            skipped.forEach { messages.offer(it) }
        }
    }
}
