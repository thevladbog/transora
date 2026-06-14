package ru.transora.app.iam.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import ru.transora.app.iam.ServiceTokenRepository
import ru.transora.app.iam.StationAssignmentRepository
import ru.transora.app.iam.TokenHashing
import ru.transora.app.iam.UserRepository
import ru.transora.app.iam.security.effectivePermissions
import java.time.Instant
import java.util.UUID

@Component
class ServiceTokenAuthenticationFilter(
    private val serviceTokenRepository: ServiceTokenRepository,
    private val userRepository: UserRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith("Bearer st_")) {
            filterChain.doFilter(request, response)
            return
        }

        val token = header.removePrefix("Bearer ").trim()
        val record = serviceTokenRepository.findActiveByHash(TokenHashing.sha256(token))
        if (record == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
            return
        }
        val user = userRepository.findById(record.userId)
        if (user == null || !user.isActive) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
            return
        }

        val assignments = stationAssignmentRepository.activeAssignmentsForUser(user.id)
        val stationHeader = request.getHeader("X-Station-ID")?.let(UUID::fromString)
        val stationId = stationHeader ?: assignments.firstOrNull()?.stationId
        val permissions = effectivePermissions(user.isSuperuser, assignments, stationId)
        val principal = JwtPrincipal(
            userId = user.id,
            login = user.login,
            fullName = user.fullName,
            isSuperuser = user.isSuperuser,
            permissions = permissions,
            stationId = stationHeader ?: assignments.firstOrNull()?.stationId,
            jti = "service-token:${record.id}",
            expiresAt = Instant.MAX,
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            grantAuthorities(principal),
        )
        serviceTokenRepository.updateLastUsed(record.id)
        filterChain.doFilter(request, response)
    }
}
