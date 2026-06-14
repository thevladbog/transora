package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.OrderNomenclatureLineStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class OrderNomenclatureRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(line: OrderNomenclatureLineRow) {
        jdbc.update(
            """
            INSERT INTO sales.order_nomenclature_lines (
                id, order_id, order_item_id, nomenclature_item_id, quantity, unit_price_cents,
                total_price_cents, status, print_name, ffd_payment_object, ffd_payment_method,
                ffd_vat_tag, ffd_measure_code, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            line.id,
            line.orderId,
            line.orderItemId,
            line.nomenclatureItemId,
            line.quantity,
            line.unitPriceCents,
            line.totalPriceCents,
            line.status.name,
            line.printName,
            line.ffdPaymentObject,
            line.ffdPaymentMethod,
            line.ffdVatTag,
            line.ffdMeasureCode,
            Timestamp.from(line.createdAt),
        )
    }

    fun findByOrderId(orderId: UUID): List<OrderNomenclatureLineRow> =
        jdbc.query(
            "SELECT * FROM sales.order_nomenclature_lines WHERE order_id = ? ORDER BY created_at",
            { rs, _ -> rs.toRow() },
            orderId,
        )

    fun findById(id: UUID): OrderNomenclatureLineRow? = findByIdInternal(id, forUpdate = false)

    fun findByIdForUpdate(id: UUID): OrderNomenclatureLineRow? = findByIdInternal(id, forUpdate = true)

    fun updateStatus(id: UUID, status: OrderNomenclatureLineStatus) {
        jdbc.update(
            "UPDATE sales.order_nomenclature_lines SET status = ? WHERE id = ?",
            status.name,
            id,
        )
    }

    fun sumRefundedQuantity(lineId: UUID): Int =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(quantity), 0)
            FROM sales.nomenclature_refunds
            WHERE order_nomenclature_line_id = ?
            """.trimIndent(),
            Int::class.java,
            lineId,
        ) ?: 0

    private fun findByIdInternal(id: UUID, forUpdate: Boolean): OrderNomenclatureLineRow? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return jdbc.query(
            "SELECT * FROM sales.order_nomenclature_lines WHERE id = ?$lock",
            { rs, _ -> rs.toRow() },
            id,
        ).firstOrNull()
    }

    private fun ResultSet.toRow(): OrderNomenclatureLineRow =
        OrderNomenclatureLineRow(
            id = getObject("id", UUID::class.java),
            orderId = getObject("order_id", UUID::class.java),
            orderItemId = getObject("order_item_id") as UUID?,
            nomenclatureItemId = getObject("nomenclature_item_id", UUID::class.java),
            quantity = getInt("quantity"),
            unitPriceCents = getLong("unit_price_cents"),
            totalPriceCents = getLong("total_price_cents"),
            status = OrderNomenclatureLineStatus.valueOf(getString("status")),
            printName = getString("print_name"),
            ffdPaymentObject = getInt("ffd_payment_object"),
            ffdPaymentMethod = getInt("ffd_payment_method"),
            ffdVatTag = getInt("ffd_vat_tag"),
            ffdMeasureCode = getInt("ffd_measure_code"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

data class OrderNomenclatureLineRow(
    val id: UUID,
    val orderId: UUID,
    val orderItemId: UUID?,
    val nomenclatureItemId: UUID,
    val quantity: Int,
    val unitPriceCents: Long,
    val totalPriceCents: Long,
    val status: OrderNomenclatureLineStatus,
    val printName: String,
    val ffdPaymentObject: Int,
    val ffdPaymentMethod: Int,
    val ffdVatTag: Int,
    val ffdMeasureCode: Int,
    val createdAt: Instant,
)

@Repository
class NomenclatureRefundRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(refund: NomenclatureRefundRow) {
        jdbc.update(
            """
            INSERT INTO sales.nomenclature_refunds (
                id, order_nomenclature_line_id, policy_id, quantity, penalty_cents,
                service_fee_cents, refund_cents, refund_type, fiscal_receipt_id, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            refund.id,
            refund.orderNomenclatureLineId,
            refund.policyId,
            refund.quantity,
            refund.penaltyCents,
            refund.serviceFeeCents,
            refund.refundCents,
            refund.refundType.name,
            refund.fiscalReceiptId,
            Timestamp.from(refund.createdAt),
        )
    }

    fun updateFiscalReceiptId(refundId: UUID, fiscalReceiptId: UUID) {
        jdbc.update(
            "UPDATE sales.nomenclature_refunds SET fiscal_receipt_id = ? WHERE id = ?",
            fiscalReceiptId,
            refundId,
        )
    }
}

data class NomenclatureRefundRow(
    val id: UUID,
    val orderNomenclatureLineId: UUID,
    val policyId: UUID,
    val quantity: Int,
    val penaltyCents: Long,
    val serviceFeeCents: Long,
    val refundCents: Long,
    val refundType: ru.transora.sales.domain.RefundType,
    val fiscalReceiptId: UUID?,
    val createdAt: Instant,
)
