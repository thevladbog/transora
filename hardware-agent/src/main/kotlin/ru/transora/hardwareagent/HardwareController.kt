package ru.transora.hardwareagent

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

@RestController
@RequestMapping("/hw")
class HardwareController {
    @PostMapping("/fiscal/receipt")
    fun fiscalReceipt(@RequestBody request: FiscalReceiptRequest): FiscalReceiptResponse =
        FiscalReceiptResponse(
            operationId = request.operationId,
            fiscalSign = Random.nextLong(1_000_000, 9_999_999).toString(),
            fiscalDocNo = Random.nextLong(1, 999_999),
            printedAt = Clock.systemUTC().instant(),
        )

    @PostMapping("/fiscal/refund")
    fun fiscalRefund(@RequestBody request: FiscalReceiptRequest): FiscalReceiptResponse =
        fiscalReceipt(request)

    @PostMapping("/fiscal/z-report")
    fun fiscalZReport(@RequestBody request: ShiftZReportRequest): FiscalReceiptResponse =
        FiscalReceiptResponse(
            operationId = request.shiftId,
            fiscalSign = Random.nextLong(1_000_000, 9_999_999).toString(),
            fiscalDocNo = Random.nextLong(1, 999_999),
            printedAt = Clock.systemUTC().instant(),
        )

    @PostMapping("/print/ticket")
    fun printTicket(@RequestBody request: PrintTicketRequest): PrintTicketResponse =
        PrintTicketResponse(ticketId = request.ticketId, printed = true)
}

data class FiscalReceiptRequest(
    val operationId: UUID,
    val amountCents: Long,
    val cashierName: String,
    val description: String,
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

data class FiscalReceiptResponse(
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

data class PrintTicketResponse(
    val ticketId: UUID,
    val printed: Boolean,
)
