package ru.transora.app.iam

import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.iam.security.JwtService
import ru.transora.app.iam.security.flattenPermissions
import ru.transora.app.iam.security.withStation
import ru.transora.iam.domain.AuthenticatedUser
import ru.transora.iam.domain.IamUser
import ru.transora.iam.domain.UserType
import ru.transora.iam.domain.TokenPair
import ru.transora.iam.permissions.RolePermissionMatrix
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authAuditRepository: AuthAuditRepository,
    private val loginLockoutService: LoginLockoutService,
    private val jwtService: JwtService,
    private val iamProperties: ru.transora.app.iam.security.IamProperties,
) {
    private val passwordEncoder = BCryptPasswordEncoder(12)

    @Transactional
    fun login(request: LoginRequest, userAgent: String?, ipAddress: String?): TokenPair {
        if (loginLockoutService.isLocked(request.login)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Account locked")
        }

        val user = userRepository.findByLogin(request.login)
        if (user == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            loginLockoutService.recordFailure(request.login)
            authAuditRepository.log(user?.id, "LOGIN_FAILED", ipAddress, userAgent)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        if (!user.isActive) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account disabled")
        }
        if (user.userType == UserType.SERVICE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Service accounts cannot use password login")
        }

        loginLockoutService.reset(request.login)
        val authUser = buildAuthenticatedUser(user, request.stationId)
        val refreshToken = newRefreshTokenValue()
        val accessToken = jwtService.createAccessToken(authUser.withStation(request.stationId))
        refreshTokenRepository.insert(
            userId = user.id,
            tokenHash = TokenHashing.sha256(refreshToken),
            expiresAt = Instant.now().plus(iamProperties.refreshTokenTtlDays, ChronoUnit.DAYS),
            userAgent = userAgent,
            ipAddress = ipAddress,
        )
        userRepository.updateLastLogin(user.id, Instant.now())
        authAuditRepository.log(user.id, "LOGIN_SUCCESS", ipAddress, userAgent)
        return TokenPair(accessToken, refreshToken, iamProperties.accessTokenTtlSeconds)
    }

    @Transactional
    fun refresh(refreshToken: String, stationId: UUID?): TokenPair {
        val userId = refreshTokenRepository.findActiveByHash(TokenHashing.sha256(refreshToken))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
        val user = userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
        if (!user.isActive) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account disabled")
        }
        val authUser = buildAuthenticatedUser(user, stationId)
        val newRefresh = newRefreshTokenValue()
        refreshTokenRepository.revoke(TokenHashing.sha256(refreshToken))
        refreshTokenRepository.insert(
            userId = user.id,
            tokenHash = TokenHashing.sha256(newRefresh),
            expiresAt = Instant.now().plus(iamProperties.refreshTokenTtlDays, ChronoUnit.DAYS),
            userAgent = null,
            ipAddress = null,
        )
        return TokenPair(
            jwtService.createAccessToken(authUser.withStation(stationId)),
            newRefresh,
            iamProperties.accessTokenTtlSeconds,
        )
    }

    @Transactional
    fun logout(refreshToken: String?) {
        refreshToken?.let { refreshTokenRepository.revoke(TokenHashing.sha256(it)) }
    }

    fun me(userId: UUID, stationId: UUID?): AuthenticatedUser {
        val user = userRepository.findById(userId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return buildAuthenticatedUser(user, stationId)
    }

    private fun buildAuthenticatedUser(user: IamUser, stationId: UUID?): AuthenticatedUser {
        val assignments = stationAssignmentRepository.activeAssignmentsForUser(user.id)
        val permissions = if (user.isSuperuser) {
            RolePermissionMatrix.allPermissions
        } else {
            assignments.flattenPermissions()
        }
        return AuthenticatedUser(
            userId = user.id,
            login = user.login,
            fullName = user.fullName,
            isSuperuser = user.isSuperuser,
            assignments = assignments,
            permissions = permissions,
            stationId = stationId ?: assignments.firstOrNull()?.stationId,
        )
    }

    private fun newRefreshTokenValue(): String =
        UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
}

data class LoginRequest(
    val login: String,
    val password: String,
    val stationId: UUID? = null,
)
