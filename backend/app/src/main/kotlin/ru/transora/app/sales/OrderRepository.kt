package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.Order
import ru.transora.sales.domain.OrderStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class OrderRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(order: Order) {
        jdbc.update(
            """
            INSERT INTO sales.orders (id, shift_id, status, total_cents, created_at, expires_at, paid_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            order.id,
            order.shiftId,
            order.status.name,
            order.totalCents,
            Timestamp.from(order.createdAt),
            Timestamp.from(order.expiresAt),
            order.paidAt?.let(Timestamp::from),
        )
    }

    fun updateStatus(id: UUID, status: OrderStatus, paidAt: java.time.Instant? = null) {
        jdbc.update(
            """
            UPDATE sales.orders
            SET status = ?, paid_at = COALESCE(?, paid_at)
            WHERE id = ?
            """.trimIndent(),
            status.name,
            paidAt?.let(Timestamp::from),
            id,
        )
    }

    fun findById(id: UUID): Order? =
        jdbc.query(
            "SELECT * FROM sales.orders WHERE id = ?",
            { rs, _ -> rs.toOrder() },
            id,
        ).firstOrNull()

    fun updateFiscalReceiptId(orderId: UUID, fiscalReceiptId: UUID) {
        jdbc.update(
            "UPDATE sales.orders SET fiscal_receipt_id = ? WHERE id = ?",
            fiscalReceiptId,
            orderId,
        )
    }

    fun findFiscalReceiptId(orderId: UUID): UUID? =
        jdbc.queryForObject(
            "SELECT fiscal_receipt_id FROM sales.orders WHERE id = ?",
            UUID::class.java,
            orderId,
        )

    private fun ResultSet.toOrder(): Order =
        Order(
            id = getObject("id", UUID::class.java),
            shiftId = getObject("shift_id", UUID::class.java),
            status = OrderStatus.valueOf(getString("status")),
            totalCents = getLong("total_cents"),
            createdAt = getTimestamp("created_at").toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
            paidAt = getTimestamp("paid_at")?.toInstant(),
        )
}
