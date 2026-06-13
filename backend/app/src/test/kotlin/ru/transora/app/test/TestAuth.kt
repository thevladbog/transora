package ru.transora.app.test

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import ru.transora.app.dev.DevStationIds

object TestAuth {
    fun loginAsAdmin(mockMvc: MockMvc, login: String = "admin", password: String = "admin"): String =
        login(mockMvc, login, password)

    fun loginAsCashier(mockMvc: MockMvc, login: String = "cashier", password: String = "cashier"): String =
        login(mockMvc, login, password)

    fun login(mockMvc: MockMvc, login: String, password: String, stationId: String = DevStationIds.TERMINAL_1.toString()): String {
        val response = mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "login": "$login",
                  "password": "$password",
                  "stationId": "$stationId"
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        return extractJsonField(response, "accessToken")
    }

    fun loginWithStation(mockMvc: MockMvc, login: String, password: String, stationId: String): String =
        login(mockMvc, login, password, stationId)

    fun bearer(token: String) = "Bearer $token"

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }
}
