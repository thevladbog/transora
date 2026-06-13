package ru.transora.app.inventory

import org.springframework.stereotype.Component
import ru.transora.app.domain.DomainRuleViolation
import java.util.UUID

@Component
class SeatBlockEnforcer(
    private val seatBlockRepository: SeatBlockRepository,
) {
    fun assertNotBlocked(tripId: UUID, seatNumber: Int) {
        if (seatBlockRepository.isBlocked(tripId, seatNumber)) {
            throw DomainRuleViolation("MANUAL_BLOCK")
        }
    }

    fun isBlocked(tripId: UUID, seatNumber: Int): Boolean =
        seatBlockRepository.isBlocked(tripId, seatNumber)
}
