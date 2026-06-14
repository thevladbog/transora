package ru.transora.app.iam

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.iam.security.JwtService
import ru.transora.app.iam.TokenBlacklistService
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.app.iam.UserStationResponse
import ru.transora.iam.domain.AuthenticatedUser
import ru.transora.iam.domain.TokenPair
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Authentication")
class AuthController(
    private val authService: AuthService,
    private val jwtService: JwtService,
    private val tokenBlacklistService: TokenBlacklistService,
) {
    @PostMapping("/login")
    @Operation(summary = "Login with credentials")
    fun login(@Valid @RequestBody request: LoginBody, http: HttpServletRequest): TokenResponse {
        val pair = authService.login(
            LoginRequest(request.login, request.password, request.stationId),
            http.getHeader("User-Agent"),
            http.remoteAddr,
        )
        return pair.toResponse()
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshBody): TokenResponse =
        authService.refresh(request.refreshToken, request.stationId).toResponse()

    @PostMapping("/logout")
    fun logout(@RequestBody(required = false) request: LogoutBody?) {
        authService.logout(request?.refreshToken)
        val principal = currentPrincipal()
        if (principal != null) {
            val ttl = Duration.between(java.time.Instant.now(), principal.expiresAt)
            if (!ttl.isNegative) {
                tokenBlacklistService.blacklist(principal.jti, ttl)
            }
        }
    }

    @GetMapping("/me")
    fun me(@RequestParam(required = false) stationId: UUID?): MeResponse {
        val principal = currentPrincipal()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val user = authService.me(principal.userId, stationId ?: principal.stationId)
        return user.toResponse()
    }

    @GetMapping("/me/stations")
    fun meStations(): List<UserStationResponse> {
        val principal = currentPrincipal()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return authService.stationsForUser(principal.userId)
    }

    @PostMapping("/switch-station")
    fun switchStation(@Valid @RequestBody request: SwitchStationBody): TokenResponse {
        val principal = currentPrincipal()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val pair = authService.switchStation(principal.userId, request.stationId, request.refreshToken)
        return pair.toResponse()
    }

    @GetMapping("/jwks.json")
    fun jwks(): Map<String, Any> = jwtService.publicJwk()
}

data class LoginBody(
    @field:NotBlank val login: String,
    @field:NotBlank val password: String,
    val stationId: UUID? = null,
)

data class RefreshBody(
    @field:NotBlank val refreshToken: String,
    val stationId: UUID? = null,
)

data class LogoutBody(val refreshToken: String?)

data class SwitchStationBody(
    val stationId: UUID? = null,
    val refreshToken: String? = null,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)

data class MeResponse(
    val userId: String,
    val login: String,
    val fullName: String,
    val isSuperuser: Boolean,
    val permissions: Set<String>,
    val stationId: String?,
)

private fun TokenPair.toResponse() = TokenResponse(accessToken, refreshToken, expiresIn = expiresInSeconds)
private fun AuthenticatedUser.toResponse() = MeResponse(
    userId = userId.toString(),
    login = login,
    fullName = fullName,
    isSuperuser = isSuperuser,
    permissions = permissions,
    stationId = stationId?.toString(),
)
