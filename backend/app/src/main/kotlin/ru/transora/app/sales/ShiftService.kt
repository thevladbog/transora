package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.sales.domain.CashierShift
import ru.transora.sales.domain.ShiftStatus
import java.time.Clock
import java.util.UUID

@Service
class ShiftService(
    private val shiftRepository: ShiftRepository,
    private val outboxEventRepository: OutboxEventRepository,
) {
    @Transactional
    fun open(request: OpenShiftRequest): CashierShift {
        val cashierName = request.cashierName.trim()
        val stationName = request.stationName.trim()

        if (shiftRepository.findOpenByCashierForUpdate(cashierName) != null) {
            throw DomainRuleViolation("Cashier $cashierName already has an open shift")
        }

        val shift = CashierShift(
            id = UUID.randomUUID(),
            stationName = stationName,
            cashierName = cashierName,
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
                "openedAt" to shift.openedAt,
            ),
        )

        return shift
    }

    fun listOpen(): List<CashierShift> =
        shiftRepository.listOpen()

    @Transactional
    fun close(shiftId: UUID): CashierShift {
        val shift = shiftRepository.findOpenByIdForUpdate(shiftId)
            ?: throw NoSuchElementException("Open shift $shiftId was not found")

        val closedAt = Clock.systemUTC().instant()
        shiftRepository.close(shiftId, closedAt)
        outboxEventRepository.append(
            aggregateType = "shift",
            aggregateId = shift.id.toString(),
            eventType = "shift.closed",
            payload = mapOf(
                "shiftId" to shift.id,
                "stationName" to shift.stationName,
                "cashierName" to shift.cashierName,
                "openedAt" to shift.openedAt,
                "closedAt" to closedAt,
            ),
        )

        return shift.copy(status = ShiftStatus.CLOSED, closedAt = closedAt)
    }
}

