package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.scheduling.domain.Driver
import java.time.Clock
import java.util.UUID

@Service
class DriverService(
    private val driverRepository: DriverRepository,
    private val carrierRepository: CarrierRepository,
) {
    fun list(): List<Driver> = driverRepository.list()

    fun get(id: UUID): Driver =
        driverRepository.findById(id) ?: throw NoSuchElementException("Driver $id was not found")

    @Transactional
    fun create(request: CreateDriverRequest): Driver {
        carrierRepository.findById(request.carrierId)
            ?: throw NoSuchElementException("Carrier ${request.carrierId} was not found")

        val driver = Driver(
            id = UUID.randomUUID(),
            carrierId = request.carrierId,
            fullName = request.fullName.trim(),
            licenseNo = request.licenseNo.trim(),
            phone = request.phone?.trim(),
            isActive = true,
            createdAt = Clock.systemUTC().instant(),
        )
        driverRepository.insert(driver)
        return driver
    }

    @Transactional
    fun update(id: UUID, request: UpdateDriverRequest): Driver {
        val existing = get(id)
        val updated = existing.copy(
            fullName = request.fullName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.fullName,
            licenseNo = request.licenseNo?.trim()?.takeIf { it.isNotEmpty() } ?: existing.licenseNo,
            phone = request.phone ?: existing.phone,
            isActive = request.isActive ?: existing.isActive,
        )
        driverRepository.update(updated)
        return updated
    }
}

data class CreateDriverRequest(
    val carrierId: UUID,
    val fullName: String,
    val licenseNo: String,
    val phone: String? = null,
)

data class UpdateDriverRequest(
    val fullName: String? = null,
    val licenseNo: String? = null,
    val phone: String? = null,
    val isActive: Boolean? = null,
)
