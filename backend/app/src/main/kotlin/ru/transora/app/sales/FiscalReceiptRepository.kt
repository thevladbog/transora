package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.FiscalReceipt
import ru.transora.sales.domain.FiscalReceiptType
import ru.transora.sales.domain.OfdStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class FiscalReceiptRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(receipt: FiscalReceipt) {
        jdbc.update(
            """
            INSERT INTO sales.fiscal_receipts (
                id, shift_id, order_id, refund_id, receipt_type, amount_cents,
                fiscal_sign, fiscal_doc_no, fiscal_drive_no, ofd_status, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            receipt.id,
            receipt.shiftId,
            receipt.orderId,
            receipt.refundId,
            receipt.receiptType.name,
            receipt.amountCents,
            receipt.fiscalSign,
            receipt.fiscalDocNo,
            receipt.fiscalDriveNo,
            receipt.ofdStatus.name,
            Timestamp.from(receipt.createdAt),
        )
    }

    fun findByShiftAndType(shiftId: UUID, receiptType: FiscalReceiptType): FiscalReceipt? =
        jdbc.query(
            """
            SELECT *
            FROM sales.fiscal_receipts
            WHERE shift_id = ? AND receipt_type = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toFiscalReceipt() },
            shiftId,
            receiptType.name,
        ).firstOrNull()

    fun countByShiftAndType(shiftId: UUID, receiptType: FiscalReceiptType): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.fiscal_receipts
            WHERE shift_id = ? AND receipt_type = ?
            """.trimIndent(),
            Int::class.java,
            shiftId,
            receiptType.name,
        ) ?: 0

    private fun ResultSet.toFiscalReceipt(): FiscalReceipt =
        FiscalReceipt(
            id = getObject("id", UUID::class.java),
            shiftId = getObject("shift_id", UUID::class.java),
            orderId = getObject("order_id", UUID::class.java),
            refundId = getObject("refund_id", UUID::class.java),
            receiptType = FiscalReceiptType.valueOf(getString("receipt_type")),
            amountCents = getLong("amount_cents"),
            fiscalSign = getString("fiscal_sign"),
            fiscalDocNo = getLong("fiscal_doc_no"),
            fiscalDriveNo = getString("fiscal_drive_no"),
            ofdStatus = OfdStatus.valueOf(getString("ofd_status")),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
