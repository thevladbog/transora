package ru.transora.app.iam.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "transora.iam")
data class IamProperties(
    val accessTokenTtlSeconds: Long = 900,
    val refreshTokenTtlDays: Long = 30,
    val lockoutMaxAttempts: Int = 5,
    val lockoutTtlMinutes: Long = 15,
    val jwtKeyId: String = "transora-dev",
)
