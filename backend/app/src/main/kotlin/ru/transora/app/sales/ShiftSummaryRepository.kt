package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.PaymentStatus
import ru.transora.sales.domain.PaymentType
import ru.transora.sales.domain.TicketStatus
import java.util.UUID

data class ShiftFiscalSummary(
    val ticketsSold: Int,
    val ticketsRefunded: Int,
    val cashSalesCents: Long,
    val cardSalesCents: Long,
    val refundsCents: Long,
) {
    fun netSalesCents(): Long = cashSalesCents + cardSalesCents - refundsCents
}

@Repository
class ShiftSummaryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun aggregate(shiftId: UUID): ShiftFiscalSummary {
        val ticketsSold = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.tickets
            WHERE shift_id = ? AND status IN (?, ?)
            """.trimIndent(),
            Int::class.java,
            shiftId,
            TicketStatus.ISSUED.name,
            TicketStatus.USED.name,
        ) ?: 0

        val ticketsRefunded = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.tickets
            WHERE shift_id = ? AND status = ?
            """.trimIndent(),
            Int::class.java,
            shiftId,
            TicketStatus.REFUNDED.name,
        ) ?: 0

        val cashSalesCents = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(p.amount_cents), 0)
            FROM sales.payments p
            JOIN sales.orders o ON o.id = p.order_id
            WHERE o.shift_id = ?
              AND o.status = 'PAID'
              AND p.status = ?
              AND p.payment_type = ?
            """.trimIndent(),
            Long::class.java,
            shiftId,
            PaymentStatus.COMPLETED.name,
            PaymentType.CASH.name,
        ) ?: 0L

        val cardSalesCents = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(p.amount_cents), 0)
            FROM sales.payments p
            JOIN sales.orders o ON o.id = p.order_id
            WHERE o.shift_id = ?
              AND o.status = 'PAID'
              AND p.status = ?
              AND p.payment_type = ?
            """.trimIndent(),
            Long::class.java,
            shiftId,
            PaymentStatus.COMPLETED.name,
            PaymentType.CARD.name,
        ) ?: 0L

        val refundsCents = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(r.refund_cents), 0)
            FROM sales.refunds r
            JOIN sales.tickets t ON t.id = r.ticket_id
            WHERE t.shift_id = ?
            """.trimIndent(),
            Long::class.java,
            shiftId,
        ) ?: 0L

        return ShiftFiscalSummary(
            ticketsSold = ticketsSold,
            ticketsRefunded = ticketsRefunded,
            cashSalesCents = cashSalesCents,
            cardSalesCents = cardSalesCents,
            refundsCents = refundsCents,
        )
    }
}
