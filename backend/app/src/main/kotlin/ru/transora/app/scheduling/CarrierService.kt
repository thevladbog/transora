package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.scheduling.domain.Carrier
import ru.transora.scheduling.domain.ContractType
import java.time.Clock
import java.util.UUID

@Service
class CarrierService(
    private val carrierRepository: CarrierRepository,
) {
    fun list(): List<Carrier> = carrierRepository.list()

    fun get(id: UUID): Carrier =
        carrierRepository.findById(id) ?: throw NoSuchElementException("Carrier $id was not found")

    @Transactional
    fun create(request: CreateCarrierRequest): Carrier {
        val now = Clock.systemUTC().instant()
        val carrier = Carrier(
            id = UUID.randomUUID(),
            name = request.name.trim(),
            legalName = request.legalName.trim(),
            inn = request.inn.trim(),
            contractType = request.contractType,
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
        carrierRepository.insert(carrier)
        return carrier
    }

    @Transactional
    fun update(id: UUID, request: UpdateCarrierRequest): Carrier {
        val existing = get(id)
        val now = Clock.systemUTC().instant()
        val updated = existing.copy(
            name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
            legalName = request.legalName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.legalName,
            inn = request.inn?.trim()?.takeIf { it.isNotEmpty() } ?: existing.inn,
            contractType = request.contractType ?: existing.contractType,
            isActive = request.isActive ?: existing.isActive,
            updatedAt = now,
        )
        carrierRepository.update(updated)
        return updated
    }
}

data class CreateCarrierRequest(
    val name: String,
    val legalName: String,
    val inn: String,
    val contractType: ContractType,
)

data class UpdateCarrierRequest(
    val name: String? = null,
    val legalName: String? = null,
    val inn: String? = null,
    val contractType: ContractType? = null,
    val isActive: Boolean? = null,
)
