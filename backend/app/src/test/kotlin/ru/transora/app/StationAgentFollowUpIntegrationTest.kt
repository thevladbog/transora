package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import ru.transora.app.dev.DevStationIds
import ru.transora.app.stationagent.StationAgentPingJob
import ru.transora.app.test.TestAuth
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StationAgentFollowUpIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var pingJob: StationAgentPingJob

    @LocalServerPort
    private var port: Int = 0

    private lateinit var dispatcherAuth: String
    private lateinit var agentToken: String
    private val stationId = DevStationIds.TERMINAL_1.toString()

    @BeforeEach
    fun authenticate() {
        dispatcherAuth = TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher", stationId))
        agentToken = TestAuth.login(mockMvc, "station_agent", "station_agent", stationId)
    }

    @Test
    fun `connected station agent receives periodic ping from core`() {
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

        val session = openAgentSession(handler, connected)
        pingJob.pingConnectedAgents()

        val ping = pollMessage(messages, 10, TimeUnit.SECONDS) { it.contains("\"type\":\"ping\"") }
        assertThat(ping).isNotNull
        assertThat(ping).contains("\"ts\"")

        session.close()
    }

    @Test
    fun `sync force triggers agent resync flow`() {
        val messages = ConcurrentLinkedQueue<String>()
        val connected = CountDownLatch(1)
        val handler = object : TextWebSocketHandler() {
            override fun afterConnectionEstablished(session: WebSocketSession) {
                connected.countDown()
            }

            override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                messages.add(message.payload)
                if (message.payload.contains("\"type\":\"sync.force\"")) {
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
                }
            }
        }

        val session = openAgentSession(handler, connected)

        mockMvc.post("/api/stations/$stationId/agent/sync-force") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect {
            status { isNoContent() }
        }

        val syncForce = pollMessage(messages, 10, TimeUnit.SECONDS) { it.contains("\"type\":\"sync.force\"") }
        assertThat(syncForce).isNotNull
        assertThat(syncForce).contains("\"stationId\":\"$stationId\"")

        val snapshot = pollMessage(messages, 10, TimeUnit.SECONDS) { it.contains("\"type\":\"sync.snapshot\"") }
        assertThat(snapshot).isNotNull

        session.close()
    }

    @Test
    fun `sync force returns conflict when agent is offline`() {
        mockMvc.post("/api/stations/$stationId/agent/sync-force") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `pause announcement queue sends audio stop to station agent`() {
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

        val session = openAgentSession(handler, connected)

        mockMvc.post("/api/announcements/queue/pause") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect {
            status { isOk() }
        }

        val audioStop = pollMessage(messages, 10, TimeUnit.SECONDS) { it.contains("\"type\":\"audio.stop\"") }
        assertThat(audioStop).isNotNull
        assertThat(audioStop).contains("\"reason\":\"queue_paused\"")

        session.close()
    }

    private fun openAgentSession(handler: TextWebSocketHandler, connected: CountDownLatch): WebSocketSession {
        val headers = WebSocketHttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, TestAuth.bearer(agentToken))
            add("X-Station-ID", stationId)
        }
        val client = StandardWebSocketClient()
        val session = client.execute(handler, headers, URI.create("ws://localhost:$port/ws/stations"))
            .get(10, TimeUnit.SECONDS)
        assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue
        return session
    }

    private fun pollMessage(
        messages: ConcurrentLinkedQueue<String>,
        timeout: Long,
        unit: TimeUnit,
        predicate: (String) -> Boolean,
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
