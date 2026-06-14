package ru.transora.app.sales

import org.springframework.stereotype.Service
import ru.transora.app.hardware.FiscalReceiptResult
import ru.transora.sales.domain.FiscalReceipt
import ru.transora.sales.domain.FiscalReceiptType
import ru.transora.sales.domain.OfdStatus
import java.time.Clock
import java.util.UUID

@Service
class FiscalReceiptService(
    private val fiscalReceiptRepository: FiscalReceiptRepository,
    private val orderRepository: OrderRepository,
    private val refundRepository: RefundRepository,
    private val shiftRepository: ShiftRepository,
) {
    fun recordSale(
        shiftId: UUID,
        orderId: UUID,
        amountCents: Long,
        result: FiscalReceiptResult,
    ): UUID {
        val receiptId = insertReceipt(
            shiftId = shiftId,
            orderId = orderId,
            refundId = null,
            receiptType = FiscalReceiptType.SALE,
            amountCents = amountCents,
            result = result,
        )
        orderRepository.updateFiscalReceiptId(orderId, receiptId)
        return receiptId
    }

    fun recordRefund(
        shiftId: UUID,
        refundId: UUID,
        amountCents: Long,
        result: FiscalReceiptResult,
    ): UUID {
        val receiptId = insertReceipt(
            shiftId = shiftId,
            orderId = null,
            refundId = refundId,
            receiptType = FiscalReceiptType.REFUND,
            amountCents = amountCents,
            result = result,
        )
        refundRepository.updateFiscalReceiptId(refundId, receiptId)
        return receiptId
    }

    fun recordNomenclatureRefund(
        shiftId: UUID,
        amountCents: Long,
        result: FiscalReceiptResult,
    ): UUID =
        insertReceipt(
            shiftId = shiftId,
            orderId = null,
            refundId = null,
            receiptType = FiscalReceiptType.REFUND,
            amountCents = amountCents,
            result = result,
        )

    fun recordZReport(
        shiftId: UUID,
        summary: ShiftFiscalSummary,
        result: FiscalReceiptResult,
    ): UUID {
        val receiptId = insertReceipt(
            shiftId = shiftId,
            orderId = null,
            refundId = null,
            receiptType = FiscalReceiptType.Z_REPORT,
            amountCents = summary.netSalesCents(),
            result = result,
        )
        shiftRepository.updateZReportReceiptId(shiftId, receiptId)
        return receiptId
    }

    private fun insertReceipt(
        shiftId: UUID,
        orderId: UUID?,
        refundId: UUID?,
        receiptType: FiscalReceiptType,
        amountCents: Long,
        result: FiscalReceiptResult,
    ): UUID {
        val id = UUID.randomUUID()
        fiscalReceiptRepository.insert(
            FiscalReceipt(
                id = id,
                shiftId = shiftId,
                orderId = orderId,
                refundId = refundId,
                receiptType = receiptType,
                amountCents = amountCents,
                fiscalSign = result.fiscalSign,
                fiscalDocNo = result.fiscalDocNo,
                fiscalDriveNo = DEFAULT_FISCAL_DRIVE_NO,
                ofdStatus = OfdStatus.PENDING,
                createdAt = Clock.systemUTC().instant(),
            ),
        )
        return id
    }

    companion object {
        private const val DEFAULT_FISCAL_DRIVE_NO = "MOCK-FN"
    }
}
