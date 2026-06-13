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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import ru.transora.app.dev.DevStationIds
import ru.transora.app.notifications.AnnouncementDepartureScheduler
import ru.transora.app.notifications.AnnouncementPlaybackService
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationsAudioIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var playbackService: AnnouncementPlaybackService

    @Autowired
    private lateinit var departureScheduler: AnnouncementDepartureScheduler

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

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
    fun `playback sends audio play over station agent websocket`() {
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
        val session = client.execute(handler, headers, java.net.URI.create("ws://localhost:$port/ws/stations"))
            .get(10, TimeUnit.SECONDS)
        assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue

        val announcementId = enqueueAnnouncement("Audio playback test")

        playbackService.processDueAnnouncements()

        assertThat(messages.any { it.contains("\"type\":\"audio.play\"") && it.contains(announcementId) }).isTrue

        mockMvc.get("/api/announcements/$announcementId/audio") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(agentToken))
        }.andExpect {
            status { isOk() }
            content { contentType("audio/wav") }
        }

        session.close()
    }

    @Test
    fun `departure scheduler enqueues template announcement for trip in 30 minutes`() {
        val tripId = createTrip(minutesFromNow = 30)
        waitForOutbox()

        departureScheduler.enqueueDepartureReminders()

        val rows = jdbc.queryForList(
            """
            SELECT template_code FROM notifications.announcement_queue
            WHERE trip_id = ? AND template_code = 'DEPARTURE_30'
            """.trimIndent(),
            UUID.fromString(tripId),
        )
        assertThat(rows).hasSize(1)
    }

    @Test
    fun `display board register and heartbeat`() {
        val agentId = "board-agent-${System.nanoTime()}"
        val registerResponse = mockMvc.post("/api/display-boards/register") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "agentId":"$agentId",
                  "boardType":"PLATFORM",
                  "name":"Platform board",
                  "platformNumber":"3"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val boardId = extractJsonField(registerResponse, "id")
        assertThat(registerResponse).contains("\"agentId\":\"$agentId\"")

        mockMvc.post("/api/display-boards/$boardId/heartbeat") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }

        val lastSeen = jdbc.queryForObject(
            "SELECT last_seen_at IS NOT NULL FROM notifications.display_boards WHERE id = ?",
            Boolean::class.java,
            UUID.fromString(boardId),
        )
        assertThat(lastSeen).isTrue()
    }

    @Test
    fun `paused queue skips playback dispatch`() {
        mockMvc.post("/api/announcements/queue/pause") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }

        val announcementId = enqueueAnnouncement("Paused queue test")
        playbackService.processDueAnnouncements()

        val status = jdbc.queryForObject(
            "SELECT status FROM notifications.announcement_queue WHERE id = ?",
            String::class.java,
            UUID.fromString(announcementId),
        )
        assertThat(status).isEqualTo("QUEUED")

        mockMvc.post("/api/announcements/queue/resume") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }
    }

    private fun enqueueAnnouncement(text: String): String {
        val response = mockMvc.post("/api/announcements") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"textContent":"$text","priority":"HIGH"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createTrip(minutesFromNow: Long): String {
        val departureTime = Instant.now().plus(minutesFromNow, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "AUD-${System.nanoTime()}",
                  "departureStation": "Transora Central",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departureTime",
                  "platform": "3",
                  "seatCount": 45
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun waitForOutbox() {
        outboxPublisher.publishPendingEvents()
        TimeUnit.MILLISECONDS.sleep(200)
        outboxPublisher.publishPendingEvents()
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)""""
        return Regex(pattern).find(json)?.groupValues?.get(1)
            ?: error("Field $field not found in: $json")
    }
}
