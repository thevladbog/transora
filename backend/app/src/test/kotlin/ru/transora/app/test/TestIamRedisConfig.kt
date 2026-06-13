package ru.transora.app.test

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import ru.transora.app.iam.LoginLockoutService
import ru.transora.app.iam.TokenBlacklistService
import ru.transora.app.iam.security.IamProperties
import ru.transora.app.notifications.BoardCacheService
import java.time.Duration

@TestConfiguration(proxyBeanMethods = false)
class TestIamRedisConfig {
    @Bean
    @Primary
    fun testBoardCacheService(objectMapper: ObjectMapper): BoardCacheService =
        BoardCacheService(objectMapper, redis = null)

    @Bean
    @Primary
    fun testLoginLockoutService(properties: IamProperties): LoginLockoutService =
        object : LoginLockoutService(
            redis = org.springframework.data.redis.core.StringRedisTemplate(),
            properties = properties,
        ) {
            override fun isLocked(login: String): Boolean = false
            override fun recordFailure(login: String) = Unit
            override fun reset(login: String) = Unit
        }

    @Bean
    @Primary
    fun testTokenBlacklistService(): TokenBlacklistService =
        object : TokenBlacklistService(
            redis = org.springframework.data.redis.core.StringRedisTemplate(),
        ) {
            override fun blacklist(jti: String, ttl: Duration) = Unit
            override fun isBlacklisted(jti: String): Boolean = false
        }
}
