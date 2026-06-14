package ru.transora.app.stationagent

import org.springframework.http.HttpHeaders
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import ru.transora.app.iam.ServiceTokenRepository
import ru.transora.app.iam.StationAssignmentRepository
import ru.transora.app.iam.TokenBlacklistService
import ru.transora.app.iam.TokenHashing
import ru.transora.app.iam.UserRepository
import ru.transora.app.iam.security.InvalidTokenException
import ru.transora.app.iam.security.JwtService
import ru.transora.iam.permissions.RoleCodes
import java.util.UUID

@Component
class StationWebSocketHandshakeInterceptor(
    private val jwtService: JwtService,
    private val tokenBlacklistService: TokenBlacklistService,
    private val serviceTokenRepository: ServiceTokenRepository,
    private val userRepository: UserRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
) : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest
            ?: return reject(response, "Invalid request")
        val authHeader = servletRequest.getHeader(HttpHeaders.AUTHORIZATION)
            ?: return reject(response, "Missing Authorization")
        if (!authHeader.startsWith("Bearer ")) {
            return reject(response, "Invalid Authorization")
        }
        val token = authHeader.removePrefix("Bearer ").trim()
        val stationHeader = servletRequest.getHeader("X-Station-ID")
            ?: return reject(response, "Missing X-Station-ID")
        val stationId = runCatching { UUID.fromString(stationHeader) }.getOrNull()
            ?: return reject(response, "Invalid X-Station-ID")

        if (token.startsWith("st_")) {
            return authenticateServiceToken(token, stationId, response, attributes)
        }

        return try {
            val principal = jwtService.parseAccessToken(token)
            if (tokenBlacklistService.isBlacklisted(principal.jti)) {
                return reject(response, "Token revoked")
            }
            if (!hasStationAgentRole(token, stationId)) {
                return reject(response, "STATION_AGENT role required")
            }
            attributes[ATTR_STATION_ID] = stationId
            attributes[ATTR_USER_ID] = principal.userId
            true
        } catch (_: InvalidTokenException) {
            reject(response, "Invalid token")
        }
    }

    private fun authenticateServiceToken(
        token: String,
        stationId: UUID,
        response: ServerHttpResponse,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val record = serviceTokenRepository.findActiveByHash(TokenHashing.sha256(token))
            ?: return reject(response, "Invalid token")
        val user = userRepository.findById(record.userId)
            ?: return reject(response, "Invalid token")
        if (!user.isActive) {
            return reject(response, "Invalid token")
        }
        val assignments = stationAssignmentRepository.activeAssignmentsForUser(user.id)
        val hasAgentRole = assignments.any {
            it.stationId == stationId && it.roleCode == RoleCodes.STATION_AGENT
        }
        if (!hasAgentRole) {
            return reject(response, "STATION_AGENT role required")
        }
        serviceTokenRepository.updateLastUsed(record.id)
        attributes[ATTR_STATION_ID] = stationId
        attributes[ATTR_USER_ID] = user.id
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit

    @Suppress("UNCHECKED_CAST")
    private fun hasStationAgentRole(token: String, stationId: UUID): Boolean {
        val signed = com.nimbusds.jwt.SignedJWT.parse(token)
        val claims = signed.jwtClaimsSet
        val assignments = claims.getClaim("assignments") as? List<Map<String, Any>> ?: emptyList()
        return assignments.any { assignment ->
            assignment["sid"] == stationId.toString() &&
                assignment["role"] == RoleCodes.STATION_AGENT
        }
    }

    private fun reject(response: ServerHttpResponse, message: String): Boolean {
        response.headers.add("X-Error-Reason", message)
        return false
    }

    companion object {
        const val ATTR_STATION_ID = "stationId"
        const val ATTR_USER_ID = "userId"
    }
}
