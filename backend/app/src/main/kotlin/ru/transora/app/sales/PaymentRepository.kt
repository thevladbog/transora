package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.Payment
import ru.transora.sales.domain.PaymentStatus
import ru.transora.sales.domain.PaymentType
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class PaymentRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(payment: Payment) {
        jdbc.update(
            """
            INSERT INTO sales.payments (
                id, order_id, payment_type, amount_cents, status, transaction_id, processed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            payment.id,
            payment.orderId,
            payment.paymentType.name,
            payment.amountCents,
            payment.status.name,
            payment.transactionId,
            payment.processedAt?.let(Timestamp::from),
        )
    }

    fun updateStatus(id: UUID, status: PaymentStatus, transactionId: String?, processedAt: java.time.Instant) {
        jdbc.update(
            """
            UPDATE sales.payments
            SET status = ?, transaction_id = ?, processed_at = ?
            WHERE id = ?
            """.trimIndent(),
            status.name,
            transactionId,
            Timestamp.from(processedAt),
            id,
        )
    }

    private fun ResultSet.toPayment(): Payment =
        Payment(
            id = getObject("id", UUID::class.java),
            orderId = getObject("order_id", UUID::class.java),
            paymentType = PaymentType.valueOf(getString("payment_type")),
            amountCents = getLong("amount_cents"),
            status = PaymentStatus.valueOf(getString("status")),
            transactionId = getString("transaction_id"),
            processedAt = getTimestamp("processed_at")?.toInstant(),
        )
}
