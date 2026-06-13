package ru.transora.app.iam

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import ru.transora.app.iam.security.IamProperties
import java.time.Duration

@Service
open class LoginLockoutService(
    private val redis: StringRedisTemplate,
    private val properties: IamProperties,
) {
    open fun isLocked(login: String): Boolean =
        (redis.opsForValue().get(key(login))?.toIntOrNull() ?: 0) >= properties.lockoutMaxAttempts

    open fun recordFailure(login: String) {
        val key = key(login)
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) {
            redis.expire(key, Duration.ofMinutes(properties.lockoutTtlMinutes))
        }
    }

    open fun reset(login: String) {
        redis.delete(key(login))
    }

    private fun key(login: String) = "iam:lockout:$login"
}

@Service
open class TokenBlacklistService(
    private val redis: StringRedisTemplate,
) {
    open fun blacklist(jti: String, ttl: Duration) {
        redis.opsForValue().set("iam:blacklist:$jti", "1", ttl)
    }

    open fun isBlacklisted(jti: String): Boolean =
        redis.hasKey("iam:blacklist:$jti")
}
