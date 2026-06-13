package ru.transora.app.iam.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import ru.transora.app.iam.TokenBlacklistService
import ru.transora.app.iam.UserRepository
import java.util.UUID

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val tokenBlacklistService: TokenBlacklistService,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.removePrefix("Bearer ").trim()
            if (token.startsWith("st_")) {
                filterChain.doFilter(request, response)
                return
            }
            try {
                val principal = jwtService.parseAccessToken(token)
                if (tokenBlacklistService.isBlacklisted(principal.jti)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token revoked")
                    return
                }
                val user = userRepository.findById(principal.userId)
                if (user == null || !user.isActive) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account disabled")
                    return
                }
                val stationHeader = request.getHeader("X-Station-ID")?.let(UUID::fromString)
                val resolved = principal.withStation(stationHeader)
                val auth = UsernamePasswordAuthenticationToken(
                    resolved,
                    null,
                    grantAuthorities(resolved),
                )
                SecurityContextHolder.getContext().authentication = auth
            } catch (_: InvalidTokenException) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}

fun currentPrincipal(): JwtPrincipal? =
    SecurityContextHolder.getContext().authentication?.principal as? JwtPrincipal
