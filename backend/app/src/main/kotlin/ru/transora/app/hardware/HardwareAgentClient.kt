package ru.transora.app.hardware

import java.time.Instant
import java.util.UUID

data class FiscalReceiptLine(
    val printName: String,
    val quantity: Int,
    val priceCents: Long,
    val paymentObject: Int,
    val paymentMethod: Int,
    val vatTag: Int,
    val measureCode: Int = 0,
)

data class FiscalReceiptRequest(
    val operationId: UUID,
    val amountCents: Long,
    val cashierName: String,
    val description: String,
    val lines: List<FiscalReceiptLine> = emptyList(),
)

data class FiscalReceiptResult(
    val operationId: UUID,
    val fiscalSign: String,
    val fiscalDocNo: Long,
    val printedAt: Instant,
)

data class PrintTicketRequest(
    val ticketId: UUID,
    val ticketNumber: String,
    val passengerName: String,
    val seatNumber: Int,
    val routeNumber: String,
    val departureTime: Instant,
)

data class PrintTicketResult(
    val ticketId: UUID,
    val printed: Boolean,
)

data class ShiftZReportRequest(
    val shiftId: UUID,
    val cashierName: String,
    val ticketsSold: Int,
    val ticketsRefunded: Int,
    val cashSalesCents: Long,
    val cardSalesCents: Long,
    val refundsCents: Long,
)

data class FiscalShiftOpenRequest(
    val shiftId: UUID,
    val cashierName: String,
    val posId: String,
)

data class FiscalShiftOpenResult(
    val fiscalShiftNo: Int,
)

interface HardwareAgentClient {
    fun openFiscalShift(request: FiscalShiftOpenRequest): FiscalShiftOpenResult

    fun printFiscalReceipt(request: FiscalReceiptRequest): FiscalReceiptResult

    fun printFiscalRefund(request: FiscalReceiptRequest): FiscalReceiptResult

    fun printZReport(request: ShiftZReportRequest): FiscalReceiptResult

    fun printTicket(request: PrintTicketRequest): PrintTicketResult
}
