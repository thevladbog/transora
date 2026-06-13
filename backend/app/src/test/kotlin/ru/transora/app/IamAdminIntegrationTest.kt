package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.dev.DevStationIds
import ru.transora.app.test.TestAuth

@AutoConfigureMockMvc
class IamAdminIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var adminAuth: String
    private val t1StationId = DevStationIds.TERMINAL_1.toString()

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `deactivate user revokes sessions and blocks auth`() {
        val login = "iamusr${System.nanoTime().toString().takeLast(10)}"
        val password = "password123"
        val userId = createUser(login, password, roleCode = "CASHIER")

        val (accessToken, refreshToken) = loginPair(login, password)
        mockMvc.get("/auth/me") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(accessToken))
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/admin/users/$userId/deactivate") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = loginBody(login, password)
        }.andExpect { status { isForbidden() } }

        mockMvc.post("/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken","stationId":"$t1StationId"}"""
        }.andExpect { status { isUnauthorized() } }

        mockMvc.get("/auth/me") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(accessToken))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin cannot deactivate self`() {
        val meResponse = mockMvc.get("/auth/me") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val adminUserId = extractJsonField(meResponse, "userId")

        mockMvc.post("/api/admin/users/$adminUserId/deactivate") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `revoke assignment removes station permissions`() {
        val login = "iamasg${System.nanoTime().toString().takeLast(10)}"
        val password = "password123"
        val userId = createUser(login, password, roleCode = "CASHIER")

        val detail = mockMvc.get("/api/admin/users/$userId") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val assignmentId = extractJsonArrayField(detail, "assignments", "assignmentId")

        val userAuth = TestAuth.bearer(TestAuth.login(mockMvc, login, password))
        mockMvc.get("/auth/me") {
            header(HttpHeaders.AUTHORIZATION, userAuth)
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.permissions.length()").value(org.hamcrest.Matchers.greaterThan(0)) }

        mockMvc.delete("/api/admin/users/$userId/assignments/$assignmentId") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/auth/me") {
            header(HttpHeaders.AUTHORIZATION, userAuth)
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.permissions.length()").value(0) }
    }

    @Test
    fun `service token lifecycle`() {
        val login = "iamsvc${System.nanoTime().toString().takeLast(10)}"
        val userId = createServiceUser(login)

        val createResponse = mockMvc.post("/api/admin/service-tokens") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "userId": "$userId",
                  "name": "integration-test"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val tokenValue = extractJsonField(createResponse, "tokenValue")
        val tokenId = extractJsonField(createResponse, "tokenId")
        assertThat(tokenValue).startsWith("st_")

        val listResponse = mockMvc.get("/api/admin/service-tokens") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertThat(listResponse).contains(tokenId)
        assertThat(listResponse).doesNotContain("tokenValue")

        mockMvc.get("/auth/me") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenValue))
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.login").value(login) }

        mockMvc.delete("/api/admin/service-tokens/$tokenId") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/auth/me") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(tokenValue))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `SERVICE user password login is blocked`() {
        val login = "iamsvc${System.nanoTime().toString().takeLast(10)}"
        createServiceUser(login, password = "password123")

        mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = loginBody(login, "password123")
        }.andExpect { status { isForbidden() } }
    }

    private fun createUser(login: String, password: String, roleCode: String): String {
        val response = mockMvc.post("/api/admin/users") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "login": "$login",
                  "password": "$password",
                  "fullName": "IAM Test User",
                  "assignments": [{
                    "stationId": "$t1StationId",
                    "roleCode": "$roleCode"
                  }]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "userId")
    }

    private fun createServiceUser(login: String, password: String? = null): String {
        val passwordField = password?.let { ""","password": "$it"""" } ?: ""
        val response = mockMvc.post("/api/admin/users") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "login": "$login",
                  "fullName": "Service Account",
                  "userType": "SERVICE"
                  $passwordField
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "userId")
    }

    private fun loginPair(login: String, password: String): Pair<String, String> {
        val response = mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = loginBody(login, password)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "accessToken") to extractJsonField(response, "refreshToken")
    }

    private fun loginBody(login: String, password: String): String =
        """
            {
              "login": "$login",
              "password": "$password",
              "stationId": "$t1StationId"
            }
        """.trimIndent()

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }

    private fun extractJsonArrayField(json: String, arrayField: String, field: String): String {
        val arrayPattern = """"$arrayField"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val arrayContent = arrayPattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Array $arrayField not found in $json")
        return extractJsonField(arrayContent, field)
    }
}
