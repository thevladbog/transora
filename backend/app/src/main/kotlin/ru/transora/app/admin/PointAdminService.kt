package ru.transora.app.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import ru.transora.app.domain.DomainRuleViolation
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class GeocodeService {
    private val nominatimClient = RestClient.builder()
        .baseUrl("https://nominatim.openstreetmap.org")
        .defaultHeader("User-Agent", "TransoraAdmin/1.0 (dev@transora.local)")
        .build()
    private val photonClient = RestClient.builder()
        .baseUrl("https://photon.komoot.io")
        .build()
    private val mapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, CachedResult>()
    private var lastRequestAt = 0L

    fun search(query: String, limit: Int = 10, city: String? = null): List<GeocodeResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return emptyList()
        }
        val boundedLimit = limit.coerceIn(1, 10)
        val cacheKey = "search:$trimmed:${city.orEmpty()}:$boundedLimit"
        cache[cacheKey]?.takeIf { !it.isExpired() }?.let { return it.results }

        val results = linkedMapOf<String, GeocodeResult>()
        for (variant in buildGeocodeSearchQueries(trimmed, city)) {
            mergeResults(results, searchNominatim(variant, boundedLimit))
            if (results.size >= boundedLimit) {
                break
            }
        }
        if (results.isEmpty()) {
            for (variant in buildGeocodeSearchQueries(trimmed, city)) {
                mergeResults(results, searchPhoton(variant, boundedLimit))
                if (results.isNotEmpty()) {
                    break
                }
            }
        }

        val list = results.values.take(boundedLimit)
        cache[cacheKey] = CachedResult(list)
        return list
    }

    fun reverse(lat: Double, lon: Double): GeocodeResult? {
        val key = "reverse:$lat:$lon"
        cache[key]?.takeIf { !it.isExpired() }?.let { return it.results.firstOrNull() }
        throttle()
        val uri = UriComponentsBuilder.fromPath("/reverse")
            .queryParam("lat", lat)
            .queryParam("lon", lon)
            .queryParam("format", "json")
            .queryParam("addressdetails", "1")
            .queryParam("accept-language", "ru")
            .queryParam("zoom", 18)
            .build()
            .encode()
            .toUri()
        val body = nominatimClient.get().uri(uri).retrieve().body(String::class.java) ?: return null
        val node = mapper.readTree(body)
        val result = node.toGeocodeResult() ?: return null
        cache[key] = CachedResult(listOf(result))
        return result
    }

    private fun searchNominatim(query: String, limit: Int): List<GeocodeResult> {
        throttle()
        val uri = UriComponentsBuilder.fromPath("/search")
            .queryParam("q", query)
            .queryParam("format", "json")
            .queryParam("addressdetails", "1")
            .queryParam("limit", limit)
            .queryParam("countrycodes", "ru")
            .queryParam("accept-language", "ru")
            .build()
            .encode()
            .toUri()
        val body = nominatimClient.get().uri(uri).retrieve().body(String::class.java) ?: return emptyList()
        return mapper.readTree(body).mapNotNull { node -> node.toGeocodeResult() }
    }

    private fun searchPhoton(query: String, limit: Int): List<GeocodeResult> {
        throttle()
        val uri = UriComponentsBuilder.fromPath("/api/")
            .queryParam("q", query)
            .queryParam("limit", limit)
            .queryParam("bbox", "19.2,41.2,180,82.0")
            .build()
            .encode()
            .toUri()
        val body = photonClient.get().uri(uri).retrieve().body(String::class.java) ?: return emptyList()
        val features = mapper.readTree(body).path("features")
        if (!features.isArray) {
            return emptyList()
        }
        return features.mapNotNull { feature -> feature.toPhotonGeocodeResult() }
    }

    private fun mergeResults(target: LinkedHashMap<String, GeocodeResult>, found: List<GeocodeResult>) {
        for (result in found) {
            val key = "${"%.5f".format(result.latitude)}:${"%.5f".format(result.longitude)}"
            target.putIfAbsent(key, result)
        }
    }

    private fun throttle() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val wait = 1000L - (now - lastRequestAt)
            if (wait > 0) {
                Thread.sleep(wait)
            }
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun JsonNode.toGeocodeResult(): GeocodeResult? {
        val lat = get("lat")?.asText()?.toDoubleOrNull() ?: return null
        val lon = get("lon")?.asText()?.toDoubleOrNull() ?: return null
        val address = get("address")
        return GeocodeResult(
            displayName = get("display_name")?.asText() ?: "$lat, $lon",
            latitude = lat,
            longitude = lon,
            city = extractLocalityName(address),
            address = formatRussianAddress(address) ?: get("display_name")?.asText(),
        )
    }

    private fun JsonNode.toPhotonGeocodeResult(): GeocodeResult? {
        val coordinates = path("geometry").path("coordinates")
        if (coordinates.size() < 2) {
            return null
        }
        val lon = coordinates[0].asDouble()
        val lat = coordinates[1].asDouble()
        val props = path("properties")
        val street = props.textOrNull("street")
        val housenumber = props.textOrNull("housenumber")
        val city = props.textOrNull("city")
            ?: props.textOrNull("town")
            ?: props.textOrNull("village")
            ?: props.textOrNull("hamlet")
            ?: ""
        val formattedAddress = formatPhotonAddress(
            postcode = props.textOrNull("postcode"),
            country = props.textOrNull("country"),
            state = props.textOrNull("state"),
            county = props.textOrNull("county"),
            city = city,
            street = street,
            housenumber = housenumber,
        )
        return GeocodeResult(
            displayName = formattedAddress ?: props.textOrNull("name") ?: "$lat, $lon",
            latitude = lat,
            longitude = lon,
            city = city,
            address = formattedAddress,
        )
    }

    private data class CachedResult(val results: List<GeocodeResult>, val createdAt: Instant = Instant.now()) {
        fun isExpired(): Boolean = Instant.now().isAfter(createdAt.plusSeconds(86_400))
    }
}

data class GeocodeResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val address: String?,
)

@Service
class PointAdminService(
    private val pointAdminRepository: PointAdminRepository,
    private val auditLogService: AuditLogService,
) {
    fun list(): List<PointResponse> = pointAdminRepository.list().map { it.toResponse() }

    fun get(id: java.util.UUID): PointResponse =
        pointAdminRepository.findById(id)?.toResponse()
            ?: throw NoSuchElementException("Point $id was not found")

    fun create(request: UpsertPointRequest): PointResponse {
        val code = request.code.trim().ifBlank { generateCode(request.name) }
        if (pointAdminRepository.existsByCode(code)) {
            throw DomainRuleViolation("Point code already exists")
        }
        val row = PointRow(
            id = java.util.UUID.randomUUID(),
            code = code,
            name = request.name.trim(),
            city = request.city?.trim().orEmpty(),
            address = request.address?.trim(),
            latitude = request.latitude,
            longitude = request.longitude,
            timezone = request.timezone?.trim().takeUnless { it.isNullOrBlank() } ?: "Europe/Moscow",
            isActive = request.isActive ?: true,
            createdAt = java.time.Clock.systemUTC().instant(),
        )
        pointAdminRepository.insert(row)
        auditLogService.record(module = "admin", action = "point.created", entityType = "point", entityId = row.id.toString())
        return row.toResponse()
    }

    fun update(id: java.util.UUID, request: UpsertPointRequest): PointResponse {
        val existing = pointAdminRepository.findById(id)
            ?: throw NoSuchElementException("Point $id was not found")
        val code = request.code.trim().ifBlank { existing.code }
        if (pointAdminRepository.existsByCode(code, excludeId = id)) {
            throw DomainRuleViolation("Point code already exists")
        }
        val updated = existing.copy(
            code = code,
            name = request.name.trim(),
            city = request.city?.trim().orEmpty(),
            address = request.address?.trim(),
            latitude = request.latitude,
            longitude = request.longitude,
            timezone = request.timezone?.trim().takeUnless { it.isNullOrBlank() } ?: existing.timezone,
            isActive = request.isActive ?: existing.isActive,
        )
        pointAdminRepository.update(updated)
        auditLogService.record(module = "admin", action = "point.updated", entityType = "point", entityId = id.toString())
        return updated.toResponse()
    }

    fun delete(id: java.util.UUID) {
        if (pointAdminRepository.delete(id) == 0) {
            throw NoSuchElementException("Point $id was not found")
        }
        auditLogService.record(module = "admin", action = "point.deleted", entityType = "point", entityId = id.toString())
    }

    private fun generateCode(name: String): String =
        name.uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .take(20)
            .ifBlank { "POINT" } + "_" + java.util.UUID.randomUUID().toString().take(4).uppercase()
}

private fun PointRow.toResponse() = PointResponse(
    id = id.toString(),
    code = code,
    name = name,
    city = city,
    address = address,
    latitude = latitude,
    longitude = longitude,
    timezone = timezone,
    isActive = isActive,
    createdAt = createdAt,
)
