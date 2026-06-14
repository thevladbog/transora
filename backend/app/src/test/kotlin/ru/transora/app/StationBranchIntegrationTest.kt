package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import ru.transora.app.dev.DevStationIds
import ru.transora.app.test.TestAuth
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StationBranchIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `provision flow and admin stations`() {
        val adminAuth = TestAuth.bearer(TestAuth.login(mockMvc, "admin", "admin"))

        val createResponse = mockMvc.post("/api/admin/stations") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "TST-99",
                  "name": "Test Station",
                  "city": "Test City",
                  "point": { "latitude": 55.75, "longitude": 37.62 }
                }
            """.trimIndent()
        }.andReturn().response.contentAsString

        val stationId = Regex(""""id"\s*:\s*"([^"]+)"""").find(createResponse)?.groupValues?.get(1)
            ?: error("station id missing")

        val tokenResponse = mockMvc.post("/api/admin/stations/$stationId/provisioning-token") {
            header("Authorization", adminAuth)
        }.andReturn().response.contentAsString

        val code = Regex(""""code"\s*:\s*"([^"]+)"""").find(tokenResponse)?.groupValues?.get(1)
            ?: error("code missing")

        val provisionBody = mockMvc.post("/api/stations/provision") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"$code"}"""
        }.andReturn().response.contentAsString

        assert(provisionBody.contains("serviceToken")) { "provision missing serviceToken" }

        val serviceToken = Regex(""""serviceToken"\s*:\s*"([^"]+)"""").find(provisionBody)?.groupValues?.get(1)
            ?: error("serviceToken missing")

        mockMvc.get("/api/admin/stations/$stationId/status") {
            header("Authorization", adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/auth/me/stations") {
            header("Authorization", adminAuth)
        }.andExpect { status { isOk() } }

        val connected = CountDownLatch(1)
        val handler = object : TextWebSocketHandler() {
            override fun afterConnectionEstablished(session: org.springframework.web.socket.WebSocketSession) {
                connected.countDown()
            }
        }
        val headers = WebSocketHttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, "Bearer $serviceToken")
            add("X-Station-ID", stationId)
        }
        val session = StandardWebSocketClient()
            .execute(handler, headers, URI.create("ws://localhost:$port/ws/stations"))
            .get(10, TimeUnit.SECONDS)
        assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue()

        mockMvc.get("/api/admin/stations/$stationId/status") {
            header("Authorization", adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.connected").value(true)
        }

        session.close()
    }

    @Test
    fun `switch-station changes permissions for multi-assignment user`() {
        val stationT1 = DevStationIds.TERMINAL_1.toString()
        val loginResponse = mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"login":"admin","password":"admin"}"""
        }.andReturn().response.contentAsString

        val accessToken = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(loginResponse)?.groupValues?.get(1)
            ?: error("accessToken missing")
        val refreshToken = Regex(""""refreshToken"\s*:\s*"([^"]+)"""").find(loginResponse)?.groupValues?.get(1)
            ?: error("refreshToken missing")

        mockMvc.get("/auth/me/stations") {
            header("Authorization", TestAuth.bearer(accessToken))
        }.andExpect { status { isOk() } }

        val switchResponse = mockMvc.post("/auth/switch-station") {
            header("Authorization", TestAuth.bearer(accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationId":"$stationT1","refreshToken":"$refreshToken"}"""
        }.andReturn().response.contentAsString

        val switchedToken = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(switchResponse)?.groupValues?.get(1)
            ?: error("switched accessToken missing")

        mockMvc.get("/auth/me") {
            header("Authorization", TestAuth.bearer(switchedToken))
            header("X-Station-ID", stationT1)
        }.andExpect {
            status { isOk() }
            jsonPath("$.stationId").value(stationT1)
        }
    }

    @Test
    fun `users list supports stationId filter`() {
        val adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        mockMvc.get("/api/admin/users") {
            header("Authorization", adminAuth)
            param("stationId", DevStationIds.TERMINAL_1.toString())
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `dispatcher login with station`() {
        val stationId = DevStationIds.TERMINAL_1
        val token = TestAuth.login(mockMvc, "dispatcher", "dispatcher", stationId.toString())
        assertThat(token).isNotBlank()
    }
}
