package ru.transora.app.iam.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import ru.transora.iam.domain.AuthenticatedUser
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

@Component
class JwtService(
    private val properties: IamProperties,
    keyPairProvider: JwtKeyPairProvider,
) {
    private val privateKey = keyPairProvider.keyPair.private as RSAPrivateKey
    private val publicKey = keyPairProvider.keyPair.public as RSAPublicKey
    private val signer = RSASSASigner(privateKey)
    private val verifier = RSASSAVerifier(publicKey)

    fun createAccessToken(user: AuthenticatedUser, jti: String = UUID.randomUUID().toString()): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(user.userId.toString())
            .claim("login", user.login)
            .claim("name", user.fullName)
            .claim("type", "ACCESS")
            .claim("scope", "user")
            .claim("is_superuser", user.isSuperuser)
            .claim("station_id", user.stationId?.toString())
            .claim(
                "assignments",
                user.assignments.map {
                    mapOf(
                        "sid" to it.stationId.toString(),
                        "role" to it.roleCode,
                        "perms" to it.permissions.toList(),
                    )
                },
            )
            .claim("permissions", user.permissions.toList())
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(properties.accessTokenTtlSeconds)))
            .build()

        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(properties.jwtKeyId).build(), claims)
        signed.sign(signer)
        return signed.serialize()
    }

    fun parseAccessToken(token: String): JwtPrincipal {
        val signed = SignedJWT.parse(token)
        if (!signed.verify(verifier)) {
            throw InvalidTokenException("Invalid token signature")
        }
        val claims = signed.jwtClaimsSet
        if (claims.expirationTime.before(Date())) {
            throw InvalidTokenException("Token expired")
        }
        @Suppress("UNCHECKED_CAST")
        val permissions = (claims.getClaim("permissions") as? List<String>)?.toSet() ?: emptySet()
        val stationId = claims.getStringClaim("station_id")?.let(UUID::fromString)
        return JwtPrincipal(
            userId = UUID.fromString(claims.subject),
            login = claims.getStringClaim("login"),
            fullName = claims.getStringClaim("name"),
            isSuperuser = claims.getBooleanClaim("is_superuser") ?: false,
            permissions = permissions,
            stationId = stationId,
            jti = claims.jwtid,
            expiresAt = claims.expirationTime.toInstant(),
        )
    }

    fun publicJwk(): Map<String, Any> {
        val jwk = RSAKey.Builder(publicKey).keyID(properties.jwtKeyId).build()
        return mapOf("keys" to listOf(jwk.toJSONObject()))
    }
}

@Component
class JwtKeyPairProvider {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}

data class JwtPrincipal(
    val userId: UUID,
    val login: String,
    val fullName: String,
    val isSuperuser: Boolean,
    val permissions: Set<String>,
    val stationId: UUID?,
    val jti: String,
    val expiresAt: Instant,
) {
    fun withStation(stationId: UUID?) = copy(stationId = stationId ?: this.stationId)
}

class InvalidTokenException(message: String) : RuntimeException(message)

fun List<ru.transora.iam.domain.StationAssignmentView>.flattenPermissions(): Set<String> =
    flatMap { it.permissions }.toSet()

fun effectivePermissions(
    isSuperuser: Boolean,
    assignments: List<ru.transora.iam.domain.StationAssignmentView>,
    stationId: UUID?,
): Set<String> {
    if (isSuperuser) {
        return ru.transora.iam.permissions.RolePermissionMatrix.allPermissions
    }
    if (stationId == null) {
        return emptySet()
    }
    return assignments.firstOrNull { it.stationId == stationId }?.permissions ?: emptySet()
}

fun AuthenticatedUser.withStation(stationId: UUID?): AuthenticatedUser {
    if (isSuperuser) {
        return copy(stationId = stationId)
    }
    val resolved = stationId ?: this.stationId ?: assignments.firstOrNull()?.stationId
    return copy(
        stationId = resolved,
        permissions = effectivePermissions(isSuperuser, assignments, resolved),
    )
}
