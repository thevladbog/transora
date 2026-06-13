package ru.transora.app.hardware

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import kotlin.random.Random

@Component
@ConditionalOnExpression("'\${transora.hardware.base-url:}'.isEmpty()")
class MockHardwareAgentClient : HardwareAgentClient {
    override fun openFiscalShift(request: FiscalShiftOpenRequest): FiscalShiftOpenResult =
        FiscalShiftOpenResult(fiscalShiftNo = kotlin.math.abs(request.shiftId.hashCode() % 999) + 1)

    override fun printFiscalReceipt(request: FiscalReceiptRequest): FiscalReceiptResult =
        mockFiscalResult(request.operationId)

    override fun printFiscalRefund(request: FiscalReceiptRequest): FiscalReceiptResult =
        mockFiscalResult(request.operationId)

    override fun printZReport(request: ShiftZReportRequest): FiscalReceiptResult =
        mockFiscalResult(request.shiftId)

    override fun printTicket(request: PrintTicketRequest): PrintTicketResult =
        PrintTicketResult(ticketId = request.ticketId, printed = true)

    private fun mockFiscalResult(operationId: java.util.UUID): FiscalReceiptResult =
        FiscalReceiptResult(
            operationId = operationId,
            fiscalSign = Random.nextLong(1_000_000, 9_999_999).toString(),
            fiscalDocNo = Random.nextLong(1, 999_999),
            printedAt = Clock.systemUTC().instant(),
        )
}
