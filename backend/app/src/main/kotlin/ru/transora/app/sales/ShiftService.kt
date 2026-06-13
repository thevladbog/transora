package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.app.inventory.ReservationRepository
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.sales.domain.CashierShift
import ru.transora.sales.domain.ShiftStatus
import java.time.Clock
import java.util.UUID

@Service
class ShiftService(
    private val shiftRepository: ShiftRepository,
    private val reservationRepository: ReservationRepository,
    private val outboxEventRepository: OutboxEventRepository,
) {
    @Transactional
    fun open(request: OpenShiftRequest): CashierShift {
        val cashierName = request.cashierName.trim()
        val stationName = request.stationName.trim()
        val posId = request.posId?.trim()?.takeIf { it.isNotEmpty() } ?: cashierName

        if (shiftRepository.findOpenByCashierForUpdate(cashierName) != null) {
            throw DomainRuleViolation("Cashier $cashierName already has an open shift")
        }
        if (shiftRepository.findOpenByPosForUpdate(posId) != null) {
            throw DomainRuleViolation("POS $posId already has an open shift")
        }

        val shift = CashierShift(
            id = UUID.randomUUID(),
            stationName = stationName,
            cashierName = cashierName,
            posId = posId,
            openingBalanceCents = request.openingBalanceCents,
            closingBalanceCents = null,
            status = ShiftStatus.OPEN,
            openedAt = Clock.systemUTC().instant(),
            closedAt = null,
        )

        shiftRepository.insert(shift)
        outboxEventRepository.append(
            aggregateType = "shift",
            aggregateId = shift.id.toString(),
            eventType = "shift.opened",
            payload = mapOf(
                "shiftId" to shift.id,
                "stationName" to shift.stationName,
                "cashierName" to shift.cashierName,
                "posId" to shift.posId,
                "openingBalanceCents" to shift.openingBalanceCents,
                "openedAt" to shift.openedAt,
            ),
        )

        return shift
    }

    fun listOpen(): List<CashierShift> =
        shiftRepository.listOpen()

    @Transactional
    fun close(shiftId: UUID, request: CloseShiftRequest = CloseShiftRequest()): CashierShift {
        val shift = shiftRepository.findOpenByIdForUpdate(shiftId)
            ?: throw NoSuchElementException("Open shift $shiftId was not found")

        val sessionId = currentPrincipal()?.jti
        if (sessionId != null && reservationRepository.countActiveBySession(sessionId) > 0) {
            throw DomainRuleViolation("Shift cannot be closed while open reservations exist (BR-SAL-054)")
        }

        val closedAt = Clock.systemUTC().instant()
        val closingBalanceCents = request.closingBalanceCents
        shiftRepository.close(shiftId, closedAt, closingBalanceCents)
        outboxEventRepository.append(
            aggregateType = "shift",
            aggregateId = shift.id.toString(),
            eventType = "shift.closed",
            payload = mapOf(
                "shiftId" to shift.id,
                "stationName" to shift.stationName,
                "cashierName" to shift.cashierName,
                "posId" to shift.posId,
                "openingBalanceCents" to shift.openingBalanceCents,
                "closingBalanceCents" to closingBalanceCents,
                "openedAt" to shift.openedAt,
                "closedAt" to closedAt,
            ),
        )

        return shift.copy(
            status = ShiftStatus.CLOSED,
            closedAt = closedAt,
            closingBalanceCents = closingBalanceCents,
        )
    }
}

data class OpenShiftRequest(
    val stationName: String,
    val cashierName: String,
    val posId: String? = null,
    val openingBalanceCents: Long = 0,
)

data class CloseShiftRequest(
    val closingBalanceCents: Long? = null,
)
