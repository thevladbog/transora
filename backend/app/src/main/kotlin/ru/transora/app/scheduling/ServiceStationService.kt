package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.scheduling.domain.ServiceStation
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ServiceStationService(
    private val serviceStationRepository: ServiceStationRepository,
) {
    fun list(): List<ServiceStation> = serviceStationRepository.list()

    fun get(id: UUID): ServiceStation =
        serviceStationRepository.findById(id) ?: throw NoSuchElementException("Station $id was not found")

    fun getByCode(code: String): ServiceStation =
        serviceStationRepository.findByCode(code.trim())
            ?: throw NoSuchElementException("Station with code $code was not found")

    @Transactional
    fun create(request: CreateServiceStationRequest): ServiceStation {
        val now = Clock.systemUTC().instant()
        val station = ServiceStation(
            id = UUID.randomUUID(),
            code = request.code.trim().uppercase(),
            name = request.name.trim(),
            city = request.city.trim(),
            timezone = request.timezone?.trim()?.takeIf { it.isNotEmpty() } ?: "Europe/Moscow",
            address = request.address?.trim(),
            isActive = true,
            createdAt = now,
        )
        serviceStationRepository.insert(station)
        return station
    }

    @Transactional
    fun update(id: UUID, request: UpdateServiceStationRequest): ServiceStation {
        val existing = get(id)
        val updated = existing.copy(
            name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
            city = request.city?.trim()?.takeIf { it.isNotEmpty() } ?: existing.city,
            timezone = request.timezone?.trim()?.takeIf { it.isNotEmpty() } ?: existing.timezone,
            address = request.address ?: existing.address,
            isActive = request.isActive ?: existing.isActive,
        )
        serviceStationRepository.update(updated)
        return updated
    }
}

data class CreateServiceStationRequest(
    val code: String,
    val name: String,
    val city: String,
    val timezone: String? = null,
    val address: String? = null,
)

data class UpdateServiceStationRequest(
    val name: String? = null,
    val city: String? = null,
    val timezone: String? = null,
    val address: String? = null,
    val isActive: Boolean? = null,
)
