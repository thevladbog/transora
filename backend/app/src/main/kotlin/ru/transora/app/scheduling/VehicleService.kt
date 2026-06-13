package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.scheduling.domain.Vehicle
import java.time.Clock
import java.util.UUID

@Service
class VehicleService(
    private val vehicleRepository: VehicleRepository,
    private val carrierRepository: CarrierRepository,
) {
    fun list(): List<Vehicle> = vehicleRepository.list()

    fun get(id: UUID): Vehicle =
        vehicleRepository.findById(id) ?: throw NoSuchElementException("Vehicle $id was not found")

    @Transactional
    fun create(request: CreateVehicleRequest): Vehicle {
        carrierRepository.findById(request.carrierId)
            ?: throw NoSuchElementException("Carrier ${request.carrierId} was not found")

        val now = Clock.systemUTC().instant()
        val vehicle = Vehicle(
            id = UUID.randomUUID(),
            carrierId = request.carrierId,
            model = request.model.trim(),
            plateNumber = request.plateNumber.trim().uppercase(),
            seatLayoutId = request.seatLayoutId,
            totalSeats = request.totalSeats,
            year = request.year,
            notes = request.notes?.trim(),
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
        vehicleRepository.insert(vehicle)
        return vehicle
    }

    @Transactional
    fun update(id: UUID, request: UpdateVehicleRequest): Vehicle {
        val existing = get(id)
        val now = Clock.systemUTC().instant()
        val updated = existing.copy(
            model = request.model?.trim()?.takeIf { it.isNotEmpty() } ?: existing.model,
            plateNumber = request.plateNumber?.trim()?.uppercase() ?: existing.plateNumber,
            seatLayoutId = request.seatLayoutId ?: existing.seatLayoutId,
            totalSeats = request.totalSeats ?: existing.totalSeats,
            year = request.year ?: existing.year,
            notes = request.notes ?: existing.notes,
            isActive = request.isActive ?: existing.isActive,
            updatedAt = now,
        )
        vehicleRepository.update(updated)
        return updated
    }
}

data class CreateVehicleRequest(
    val carrierId: UUID,
    val model: String,
    val plateNumber: String,
    val seatLayoutId: UUID,
    val totalSeats: Int,
    val year: Int? = null,
    val notes: String? = null,
)

data class UpdateVehicleRequest(
    val model: String? = null,
    val plateNumber: String? = null,
    val seatLayoutId: UUID? = null,
    val totalSeats: Int? = null,
    val year: Int? = null,
    val notes: String? = null,
    val isActive: Boolean? = null,
)
