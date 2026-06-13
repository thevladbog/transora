package ru.transora.app.iam

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.transora.iam.domain.UserType
import java.util.UUID

@Service
class ServiceTokenService(
    private val userRepository: UserRepository,
    private val serviceTokenRepository: ServiceTokenRepository,
) {
    @Transactional
    fun create(userId: UUID, name: String, createdBy: UUID): ServiceTokenCreatedResponse {
        val user = userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        if (user.userType != UserType.SERVICE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Service tokens require a SERVICE user")
        }
        if (!user.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User is inactive")
        }
        val tokenValue = TokenHashing.newServiceTokenValue()
        val tokenId = serviceTokenRepository.insert(
            userId = userId,
            name = name.trim(),
            tokenHash = TokenHashing.sha256(tokenValue),
            createdBy = createdBy,
        )
        return ServiceTokenCreatedResponse(
            tokenId = tokenId.toString(),
            userId = userId.toString(),
            name = name.trim(),
            tokenValue = tokenValue,
        )
    }

    fun listActive(): List<ServiceTokenSummaryResponse> =
        serviceTokenRepository.listActive().map {
            ServiceTokenSummaryResponse(
                tokenId = it.id.toString(),
                userId = it.userId.toString(),
                name = it.name,
                lastUsedAt = it.lastUsedAt?.toString(),
                createdAt = it.createdAt.toString(),
            )
        }

    @Transactional
    fun revoke(tokenId: UUID, revokedBy: UUID) {
        serviceTokenRepository.findById(tokenId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val updated = serviceTokenRepository.revoke(tokenId, revokedBy)
        if (updated == 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Token already revoked")
        }
    }
}

data class ServiceTokenCreatedResponse(
    val tokenId: String,
    val userId: String,
    val name: String,
    val tokenValue: String,
)

data class ServiceTokenSummaryResponse(
    val tokenId: String,
    val userId: String,
    val name: String,
    val lastUsedAt: String?,
    val createdAt: String,
)
