package ru.transora.app.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class RouteDistanceSource {
    ROAD,
    STRAIGHT_LINE,
}

data class RouteLegDistance(
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val distanceKm: Double,
    val durationMin: Int?,
)

data class RouteDistanceResult(
    val distanceKm: Double?,
    val distanceSource: RouteDistanceSource?,
    val durationMin: Int?,
    val legs: List<RouteLegDistance>,
)

data class RouteWaypoint(
    val stopOrder: Int,
    val latitude: Double,
    val longitude: Double,
)

@Service
class OsrmRoutingService(
    private val properties: RoutingProperties,
) {
    private val mapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, CachedDistance>()
    private val client by lazy {
        RestClient.builder()
            .baseUrl(properties.osrmBaseUrl.trimEnd('/'))
            .defaultHeader("User-Agent", "TransoraAdmin/1.0")
            .build()
    }

    fun calculate(waypoints: List<RouteWaypoint>): RouteDistanceResult {
        if (waypoints.size < 2) {
            return RouteDistanceResult(null, null, null, emptyList())
        }
        val sorted = waypoints.sortedBy { it.stopOrder }
        val cacheKey = sorted.joinToString("|") { "${it.stopOrder}:${it.latitude},${it.longitude}" }
        cache[cacheKey]?.takeIf { !it.isExpired() }?.let { return it.result }

        val road = if (properties.enabled) tryRoadRoute(sorted) else null
        val result = road ?: straightLineRoute(sorted)
        cache[cacheKey] = CachedDistance(result, properties.cacheTtlMinutes)
        return result
    }

    private fun tryRoadRoute(waypoints: List<RouteWaypoint>): RouteDistanceResult? {
        return try {
            val coordinates = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
            val path = "/route/v1/driving/$coordinates?overview=false&steps=false"
            val body = client.get().uri(path).retrieve().body(String::class.java) ?: return null
            val root = mapper.readTree(body)
            if (root.path("code").asText() != "Ok") {
                return null
            }
            val route = root.path("routes").firstOrNull() ?: return null
            val legsNode = route.path("legs")
            if (!legsNode.isArray || legsNode.size() == 0) {
                return null
            }
            val legs = legsNode.mapIndexed { index, leg ->
                val fromOrder = waypoints[index].stopOrder
                val toOrder = waypoints[index + 1].stopOrder
                RouteLegDistance(
                    fromStopOrder = fromOrder,
                    toStopOrder = toOrder,
                    distanceKm = leg.path("distance").asDouble() / 1000.0,
                    durationMin = (leg.path("duration").asDouble() / 60.0).toInt(),
                )
            }
            RouteDistanceResult(
                distanceKm = legs.sumOf { it.distanceKm },
                distanceSource = RouteDistanceSource.ROAD,
                durationMin = legs.mapNotNull { it.durationMin }.takeIf { it.isNotEmpty() }?.sum(),
                legs = legs,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun straightLineRoute(waypoints: List<RouteWaypoint>): RouteDistanceResult {
        val legs = waypoints.zipWithNext { from, to ->
            RouteLegDistance(
                fromStopOrder = from.stopOrder,
                toStopOrder = to.stopOrder,
                distanceKm = haversineKm(from.latitude, from.longitude, to.latitude, to.longitude),
                durationMin = null,
            )
        }
        return RouteDistanceResult(
            distanceKm = legs.sumOf { it.distanceKm },
            distanceSource = RouteDistanceSource.STRAIGHT_LINE,
            durationMin = null,
            legs = legs,
        )
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2 * r * asin(sqrt(a))
    }

    private data class CachedDistance(
        val result: RouteDistanceResult,
        val expiresAt: Instant,
    ) {
        constructor(result: RouteDistanceResult, ttlMinutes: Long) : this(
            result = result,
            expiresAt = Instant.now().plusSeconds(ttlMinutes * 60),
        )

        fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
    }
}
