package ru.transora.app.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

enum class BoardCacheType {
    DEPARTURES,
    ARRIVALS,
    PLATFORM,
}

@Service
class BoardCacheService(
    private val objectMapper: ObjectMapper,
    @Autowired(required = false) private val redis: StringRedisTemplate?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val localCache = ConcurrentHashMap<String, String>()

    fun cacheKey(stationCode: String, type: BoardCacheType, platformNumber: String? = null): String {
        val suffix = if (type == BoardCacheType.PLATFORM && platformNumber != null) {
            ":$platformNumber"
        } else {
            ""
        }
        return "board:${stationCode.uppercase()}:${type.name.lowercase()}$suffix"
    }

    fun put(key: String, payload: Any) {
        val json = objectMapper.writeValueAsString(payload)
        if (writeToRedis(key, json)) {
            return
        }
        localCache[key] = json
    }

    fun get(key: String): String? {
        readFromRedis(key)?.let { return it }
        return localCache[key]
    }

    fun getOrEmpty(key: String): String =
        get(key) ?: "{}"

    private fun writeToRedis(key: String, json: String): Boolean {
        val template = redis ?: return false
        return runCatching {
            template.opsForValue().set(key, json, Duration.ofHours(6))
        }.onFailure { ex ->
            log.debug("Redis board cache write failed for {}: {}", key, ex.message)
        }.isSuccess
    }

    private fun readFromRedis(key: String): String? {
        val template = redis ?: return null
        return runCatching {
            template.opsForValue().get(key)
        }.onFailure { ex ->
            log.debug("Redis board cache read failed for {}: {}", key, ex.message)
        }.getOrNull()
    }
}
