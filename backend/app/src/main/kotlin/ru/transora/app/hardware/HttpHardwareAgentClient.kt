package ru.transora.app.hardware

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

@Component
@ConditionalOnExpression("!'\${transora.hardware.base-url:}'.isEmpty()")
class HttpHardwareAgentClient(
    properties: HardwareAgentProperties,
) : HardwareAgentClient {
    private val restClient = RestClient.builder()
        .baseUrl(properties.baseUrl.trimEnd('/'))
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader("X-Agent-Token", properties.agentToken)
        .build()

    override fun openFiscalShift(request: FiscalShiftOpenRequest): FiscalShiftOpenResult =
        restClient.post()
            .uri("/hw/fiscal/shift/open")
            .body(request)
            .retrieve()
            .body(FiscalShiftOpenResponse::class.java)
            ?.toResult()
            ?: throw IllegalStateException("Empty response from hardware agent for fiscal shift open")

    override fun printFiscalReceipt(request: FiscalReceiptRequest): FiscalReceiptResult =
        restClient.post()
            .uri("/hw/fiscal/receipt")
            .body(request)
            .retrieve()
            .body(FiscalReceiptResponse::class.java)
            ?.toResult(request.operationId)
            ?: throw IllegalStateException("Empty response from hardware agent for fiscal receipt")

    override fun printFiscalRefund(request: FiscalReceiptRequest): FiscalReceiptResult =
        restClient.post()
            .uri("/hw/fiscal/refund")
            .body(request)
            .retrieve()
            .body(FiscalReceiptResponse::class.java)
            ?.toResult(request.operationId)
            ?: throw IllegalStateException("Empty response from hardware agent for fiscal refund")

    override fun printZReport(request: ShiftZReportRequest): FiscalReceiptResult =
        restClient.post()
            .uri("/hw/fiscal/z-report")
            .body(request)
            .retrieve()
            .body(FiscalReceiptResponse::class.java)
            ?.toResult(request.shiftId)
            ?: throw IllegalStateException("Empty response from hardware agent for Z-report")

    override fun printTicket(request: PrintTicketRequest): PrintTicketResult =
        restClient.post()
            .uri("/hw/print/ticket")
            .body(request)
            .retrieve()
            .body(PrintTicketResponse::class.java)
            ?.toResult(request.ticketId)
            ?: throw IllegalStateException("Empty response from hardware agent for ticket print")

    private data class FiscalShiftOpenResponse(
        val fiscalShiftNo: Int,
    ) {
        fun toResult(): FiscalShiftOpenResult = FiscalShiftOpenResult(fiscalShiftNo = fiscalShiftNo)
    }

    private data class FiscalReceiptResponse(
        val operationId: UUID?,
        val fiscalSign: String,
        val fiscalDocNo: Long,
        val printedAt: Instant,
    ) {
        fun toResult(fallbackOperationId: UUID): FiscalReceiptResult =
            FiscalReceiptResult(
                operationId = operationId ?: fallbackOperationId,
                fiscalSign = fiscalSign,
                fiscalDocNo = fiscalDocNo,
                printedAt = printedAt,
            )
    }

    private data class PrintTicketResponse(
        val ticketId: UUID?,
        val printed: Boolean,
    ) {
        fun toResult(fallbackTicketId: UUID): PrintTicketResult =
            PrintTicketResult(
                ticketId = ticketId ?: fallbackTicketId,
                printed = printed,
            )
    }
}
