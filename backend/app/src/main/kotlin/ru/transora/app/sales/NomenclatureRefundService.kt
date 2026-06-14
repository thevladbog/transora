package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.admin.NomenclatureAdminRepository
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.hardware.FiscalReceiptLine
import ru.transora.app.hardware.FiscalReceiptRequest
import ru.transora.app.hardware.HardwareAgentClient
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.sales.domain.OrderNomenclatureLineStatus
import ru.transora.sales.domain.RefundPreview
import ru.transora.sales.domain.RefundType
import java.time.Clock
import java.util.UUID

data class NomenclatureRefundResult(
    val refundId: String,
    val preview: RefundPreview,
    val quantity: Int,
)

@Service
class NomenclatureRefundService(
    private val orderNomenclatureRepository: OrderNomenclatureRepository,
    private val nomenclatureRefundRepository: NomenclatureRefundRepository,
    private val nomenclatureAdminRepository: NomenclatureAdminRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val refundCalculator: RefundCalculator,
    private val shiftRepository: ShiftRepository,
    private val hardwareAgentClient: HardwareAgentClient,
    private val fiscalReceiptService: FiscalReceiptService,
    private val outboxEventRepository: OutboxEventRepository,
) {
    fun preview(lineId: UUID, quantity: Int? = null): RefundPreview {
        val line = orderNomenclatureRepository.findById(lineId)
            ?: throw NoSuchElementException("Nomenclature line $lineId was not found")
        val qty = resolveRefundQuantity(line, quantity)
        return previewForQuantity(line, qty)
    }

    @Transactional
    fun refund(lineId: UUID, quantity: Int? = null, refundType: RefundType = RefundType.CASH): NomenclatureRefundResult {
        val line = orderNomenclatureRepository.findByIdForUpdate(lineId)
            ?: throw NoSuchElementException("Nomenclature line $lineId was not found")
        val qty = resolveRefundQuantity(line, quantity)
        val preview = previewForQuantity(line, qty)
        if (!preview.refundAllowed) {
            throw DomainRuleViolation("Refund is not allowed for this nomenclature line")
        }

        val item = nomenclatureAdminRepository.findById(line.nomenclatureItemId)
            ?: throw NoSuchElementException("Nomenclature item ${line.nomenclatureItemId} was not found")
        val policyId = item.refundPolicyId
            ?: throw DomainRuleViolation("Nomenclature item has no refund policy configured")

        val order = orderRepository.findById(line.orderId)
            ?: throw NoSuchElementException("Order ${line.orderId} was not found")
        val shift = shiftRepository.findById(order.shiftId)
            ?: throw NoSuchElementException("Shift ${order.shiftId} was not found")

        val tripId = line.orderItemId?.let { orderItemId ->
            orderItemRepository.findByOrderId(line.orderId)
                .firstOrNull { it.id == orderItemId }
                ?.tripId
        } ?: orderItemRepository.findByOrderId(line.orderId).firstOrNull()?.tripId
            ?: throw DomainRuleViolation("Cannot resolve trip for nomenclature refund")

        val fiscalResult = runCatching {
            hardwareAgentClient.printFiscalRefund(
                FiscalReceiptRequest(
                    operationId = UUID.randomUUID(),
                    amountCents = preview.refundCents,
                    cashierName = shift.cashierName,
                    description = "Refund ${line.printName} x$qty",
                    lines = listOf(
                        FiscalReceiptLine(
                            printName = line.printName,
                            quantity = qty,
                            priceCents = line.unitPriceCents,
                            paymentObject = line.ffdPaymentObject,
                            paymentMethod = line.ffdPaymentMethod,
                            vatTag = line.ffdVatTag,
                            measureCode = line.ffdMeasureCode,
                        ),
                    ),
                ),
            )
        }.getOrElse { ex ->
            throw DomainRuleViolation("Fiscal refund failed: ${ex.message}")
        }

        val now = Clock.systemUTC().instant()
        val refundId = UUID.randomUUID()
        nomenclatureRefundRepository.insert(
            NomenclatureRefundRow(
                id = refundId,
                orderNomenclatureLineId = line.id,
                policyId = policyId,
                quantity = qty,
                penaltyCents = preview.penaltyCents,
                serviceFeeCents = preview.serviceFeeCents,
                refundCents = preview.refundCents,
                refundType = refundType,
                fiscalReceiptId = null,
                createdAt = now,
            ),
        )
        val fiscalReceiptId = fiscalReceiptService.recordNomenclatureRefund(
            shiftId = order.shiftId,
            amountCents = preview.refundCents,
            result = fiscalResult,
        )
        nomenclatureRefundRepository.updateFiscalReceiptId(refundId, fiscalReceiptId)

        val refundedTotal = orderNomenclatureRepository.sumRefundedQuantity(line.id)
        val newStatus = when {
            refundedTotal >= line.quantity -> OrderNomenclatureLineStatus.REFUNDED
            refundedTotal > 0 -> OrderNomenclatureLineStatus.PARTIALLY_REFUNDED
            else -> line.status
        }
        orderNomenclatureRepository.updateStatus(line.id, newStatus)

        outboxEventRepository.append(
            aggregateType = "nomenclature_line",
            aggregateId = line.id.toString(),
            eventType = "sales.nomenclature.refunded",
            payload = mapOf(
                "lineId" to line.id,
                "orderId" to line.orderId,
                "refundId" to refundId,
                "quantity" to qty,
                "refundCents" to preview.refundCents,
                "tripId" to tripId,
            ),
        )

        return NomenclatureRefundResult(
            refundId = refundId.toString(),
            preview = preview,
            quantity = qty,
        )
    }

    private fun previewForQuantity(line: OrderNomenclatureLineRow, quantity: Int): RefundPreview {
        val item = nomenclatureAdminRepository.findById(line.nomenclatureItemId)
            ?: throw NoSuchElementException("Nomenclature item ${line.nomenclatureItemId} was not found")
        if (!item.refundAllowed) {
            return RefundPreview(
                penaltyPercent = java.math.BigDecimal.ZERO,
                penaltyCents = line.unitPriceCents * quantity,
                serviceFeeCents = 0,
                refundCents = 0,
                refundAllowed = false,
            )
        }
        val policyId = item.refundPolicyId
            ?: throw DomainRuleViolation("refund_policy_id is required when refund is allowed")

        val tripId = line.orderItemId?.let { orderItemId ->
            orderItemRepository.findByOrderId(line.orderId)
                .firstOrNull { it.id == orderItemId }
                ?.tripId
        } ?: orderItemRepository.findByOrderId(line.orderId).firstOrNull()?.tripId
            ?: throw DomainRuleViolation("Cannot resolve trip for nomenclature refund preview")

        return refundCalculator.preview(
            ticketPriceCents = line.unitPriceCents * quantity,
            tripId = tripId,
            refundAt = Clock.systemUTC().instant(),
            policyId = policyId,
        )
    }

    private fun resolveRefundQuantity(line: OrderNomenclatureLineRow, requested: Int?): Int {
        val alreadyRefunded = orderNomenclatureRepository.sumRefundedQuantity(line.id)
        val remaining = line.quantity - alreadyRefunded
        if (remaining <= 0) {
            throw DomainRuleViolation("Nomenclature line is fully refunded")
        }
        val qty = requested ?: remaining
        if (qty < 1 || qty > remaining) {
            throw DomainRuleViolation("Refund quantity must be between 1 and $remaining")
        }
        return qty
    }
}
