package ru.transora.app.scheduling

import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.admin.PointAdminRepository
import ru.transora.app.admin.PointRow
import ru.transora.app.iam.RoleRepository
import ru.transora.app.iam.ServiceTokenRepository
import ru.transora.app.iam.StationAssignmentRepository
import ru.transora.app.iam.TokenHashing
import ru.transora.app.iam.UserRepository
import ru.transora.iam.domain.IamUser
import ru.transora.iam.domain.UserType
import ru.transora.iam.permissions.RoleCodes
import ru.transora.scheduling.domain.ServiceStation
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class StationAdminService(
    private val serviceStationRepository: ServiceStationRepository,
    private val pointAdminRepository: PointAdminRepository,
    private val provisioningRepository: StationProvisioningRepository,
    private val agentStatusRepository: StationAgentStatusRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
    private val serviceTokenRepository: ServiceTokenRepository,
) {
    private val passwordEncoder = BCryptPasswordEncoder(12)
    private val secureRandom = SecureRandom()

    fun listWithStatus(): List<AdminStationResponse> =
        serviceStationRepository.list().map { station ->
            val status = agentStatusRepository.findByStationId(station.id)
            station.toAdminResponse(status)
        }

    fun getWithStatus(id: UUID): AdminStationResponse {
        val station = serviceStationRepository.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found")
        val status = agentStatusRepository.findByStationId(id)
        return station.toAdminResponse(status)
    }

    @Transactional
    fun create(request: CreateAdminStationRequest, createdBy: UUID?): AdminStationResponse {
        val now = Clock.systemUTC().instant()
        val code = normalizeCode(request.code ?: generateStationCode(request.city))
        if (serviceStationRepository.findByCode(code) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Station code already exists")
        }
        val stationId = UUID.randomUUID()
        val pointId = request.point?.let { createPoint(stationId, code, request, it, now) }
            ?: request.pointId
        val station = ServiceStation(
            id = stationId,
            code = code,
            name = request.name.trim(),
            city = request.city.trim(),
            timezone = request.timezone?.trim()?.takeIf { it.isNotEmpty() } ?: "Europe/Moscow",
            address = request.address?.trim(),
            pointId = pointId,
            description = request.description?.trim(),
            contactPhone = request.contactPhone?.trim(),
            isActive = true,
            createdAt = now,
        )
        serviceStationRepository.insert(station)
        return station.toAdminResponse(null)
    }

    @Transactional
    fun update(id: UUID, request: UpdateAdminStationRequest): AdminStationResponse {
        val existing = serviceStationRepository.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found")
        val pointId = when {
            request.point != null -> upsertPoint(existing, request)
            request.pointId != null -> request.pointId
            else -> existing.pointId
        }
        val updated = existing.copy(
            name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
            city = request.city?.trim()?.takeIf { it.isNotEmpty() } ?: existing.city,
            timezone = request.timezone?.trim()?.takeIf { it.isNotEmpty() } ?: existing.timezone,
            address = request.address ?: existing.address,
            pointId = pointId,
            description = request.description ?: existing.description,
            contactPhone = request.contactPhone ?: existing.contactPhone,
            isActive = request.isActive ?: existing.isActive,
        )
        serviceStationRepository.update(updated)
        val status = agentStatusRepository.findByStationId(id)
        return updated.toAdminResponse(status)
    }

    fun createProvisioningToken(stationId: UUID, createdBy: UUID, ttlHours: Long = 72): ProvisioningTokenResponse {
        serviceStationRepository.findById(stationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found")
        val plainCode = generateProvisioningCode()
        val tokenHash = TokenHashing.sha256(plainCode)
        val expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS)
        provisioningRepository.insert(stationId, tokenHash, expiresAt, createdBy)
        return ProvisioningTokenResponse(
            code = plainCode,
            expiresAt = expiresAt.toString(),
        )
    }

    fun agentStatus(stationId: UUID): StationAgentStatusResponse {
        serviceStationRepository.findById(stationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found")
        val status = agentStatusRepository.findByStationId(stationId)
        return StationAgentStatusResponse(
            stationId = stationId.toString(),
            connected = status?.connected ?: false,
            lastSeenAt = status?.lastSeenAt?.toString(),
            agentVersion = status?.agentVersion,
        )
    }

    @Transactional
    fun provision(code: String, agentLabel: String?): StationProvisionResponse {
        val normalized = code.trim().uppercase()
        val tokenHash = TokenHashing.sha256(normalized)
        val token = provisioningRepository.findActiveByHash(tokenHash)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired provisioning code")
        val station = serviceStationRepository.findById(token.stationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found")
        if (!station.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Station is inactive")
        }
        val actorId = token.createdBy ?: userRepository.listAll().firstOrNull { it.isSuperuser }?.id
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No provisioning actor")
        val login = "station_agent_${station.code.lowercase().replace("-", "_")}"
        val agentUser = userRepository.findByLogin(login) ?: createServiceUser(login, station.name, actorId)
        val role = roleRepository.findByCode(RoleCodes.STATION_AGENT)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STATION_AGENT role missing")
        if (!stationAssignmentRepository.hasActiveAssignment(agentUser.id, station.id)) {
            stationAssignmentRepository.assign(agentUser.id, station.id, role.id, actorId)
        }
        val tokenValue = TokenHashing.newServiceTokenValue()
        serviceTokenRepository.insert(
            userId = agentUser.id,
            name = agentLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "station-agent-${station.code}",
            tokenHash = TokenHashing.sha256(tokenValue),
            createdBy = actorId,
        )
        provisioningRepository.markUsed(token.id)
        return StationProvisionResponse(
            stationId = station.id.toString(),
            code = station.code,
            name = station.name,
            serviceToken = tokenValue,
        )
    }

    private fun createServiceUser(login: String, stationName: String, createdBy: UUID): IamUser {
        val user = IamUser(
            id = UUID.randomUUID(),
            login = login,
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())!!,
            fullName = "Station Agent ($stationName)",
            email = null,
            phone = null,
            userType = UserType.SERVICE,
            isActive = true,
            isSuperuser = false,
            lastLoginAt = null,
            createdAt = Instant.now(),
        )
        userRepository.insert(user)
        return user
    }

    private fun createPoint(
        stationId: UUID,
        code: String,
        request: CreateAdminStationRequest,
        point: StationPointInput,
        now: Instant,
    ): UUID {
        val row = PointRow(
            id = stationId,
            code = code,
            name = request.name.trim(),
            city = request.city.trim(),
            address = request.address,
            latitude = point.latitude,
            longitude = point.longitude,
            timezone = request.timezone?.trim()?.takeIf { it.isNotEmpty() } ?: "Europe/Moscow",
            isActive = true,
            createdAt = now,
        )
        pointAdminRepository.insert(row)
        return row.id
    }

    private fun upsertPoint(existing: ServiceStation, request: UpdateAdminStationRequest): UUID {
        val point = request.point ?: return existing.pointId ?: UUID.randomUUID()
        val now = Instant.now()
        val pointId = existing.pointId ?: existing.id
        val row = PointRow(
            id = pointId,
            code = existing.code,
            name = request.name?.trim() ?: existing.name,
            city = request.city?.trim() ?: existing.city,
            address = request.address ?: existing.address,
            latitude = point.latitude,
            longitude = point.longitude,
            timezone = request.timezone?.trim() ?: existing.timezone,
            isActive = request.isActive ?: existing.isActive,
            createdAt = now,
        )
        if (existing.pointId != null) {
            pointAdminRepository.update(row)
        } else {
            pointAdminRepository.insert(row)
        }
        return pointId
    }

    private fun normalizeCode(raw: String): String =
        raw.trim().uppercase().replace(Regex("[^A-Z0-9-]"), "-").replace(Regex("-+"), "-")

    private fun generateStationCode(city: String): String {
        val slug = city.trim().uppercase()
            .replace(Regex("[^A-ZА-Я0-9]"), "")
            .take(3)
            .ifEmpty { "ST" }
        val suffix = secureRandom.nextInt(90) + 10
        return "$slug-$suffix"
    }

    private fun generateProvisioningCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun segment() = (1..4).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
        return "TR-${segment()}-${segment()}"
    }

    private fun ServiceStation.toAdminResponse(status: StationAgentStatusRow?) = AdminStationResponse(
        id = id.toString(),
        code = code,
        name = name,
        city = city,
        timezone = timezone,
        address = address,
        pointId = pointId?.toString(),
        description = description,
        contactPhone = contactPhone,
        isActive = isActive,
        agentConnected = status?.connected ?: false,
        agentLastSeenAt = status?.lastSeenAt?.toString(),
        agentVersion = status?.agentVersion,
    )
}

data class StationPointInput(
    val latitude: Double,
    val longitude: Double,
)

data class CreateAdminStationRequest(
    val code: String? = null,
    val name: String,
    val city: String,
    val timezone: String? = null,
    val address: String? = null,
    val pointId: UUID? = null,
    val point: StationPointInput? = null,
    val description: String? = null,
    val contactPhone: String? = null,
)

data class UpdateAdminStationRequest(
    val name: String? = null,
    val city: String? = null,
    val timezone: String? = null,
    val address: String? = null,
    val pointId: UUID? = null,
    val point: StationPointInput? = null,
    val description: String? = null,
    val contactPhone: String? = null,
    val isActive: Boolean? = null,
)

data class AdminStationResponse(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val timezone: String,
    val address: String?,
    val pointId: String?,
    val description: String?,
    val contactPhone: String?,
    val isActive: Boolean,
    val agentConnected: Boolean,
    val agentLastSeenAt: String?,
    val agentVersion: String?,
)

data class ProvisioningTokenResponse(
    val code: String,
    val expiresAt: String,
)

data class StationAgentStatusResponse(
    val stationId: String,
    val connected: Boolean,
    val lastSeenAt: String?,
    val agentVersion: String?,
)

data class StationProvisionResponse(
    val stationId: String,
    val code: String,
    val name: String,
    val serviceToken: String,
)

data class StationProvisionRequest(
    val code: String,
    val agentLabel: String? = null,
)
