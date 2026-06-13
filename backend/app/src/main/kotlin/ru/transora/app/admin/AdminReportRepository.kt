package ru.transora.app.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.admin.domain.PassengerFlowReport
import ru.transora.admin.domain.StationRevenueReport
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class AdminReportRepository(
    private val jdbc: JdbcTemplate,
) {
    fun stationRevenue(stationId: UUID?, from: Instant, to: Instant): StationRevenueReport {
        val stationFilter = if (stationId != null) "AND s.station_name IS NOT NULL" else ""
        val params = mutableListOf<Any>(Timestamp.from(from), Timestamp.from(to))
        val soldSql = """
            SELECT COUNT(*), COALESCE(SUM(t.price_cents), 0)
            FROM sales.tickets t
            JOIN sales.shifts s ON s.id = t.shift_id
            WHERE t.status IN ('ISSUED', 'USED')
              AND t.issued_at >= ? AND t.issued_at <= ?
              $stationFilter
        """.trimIndent()
        val sold = jdbc.queryForMap(soldSql, *params.toTypedArray())
        val ticketsSold = (sold["count"] as Number).toInt()
        val grossRevenue = (sold["coalesce"] as Number).toLong()

        val refundSql = """
            SELECT COUNT(*), COALESCE(SUM(r.refund_cents), 0)
            FROM sales.refunds r
            JOIN sales.tickets t ON t.id = r.ticket_id
            WHERE r.created_at >= ? AND r.created_at <= ?
        """.trimIndent()
        val refunds = jdbc.queryForMap(refundSql, Timestamp.from(from), Timestamp.from(to))
        val refundsCount = (refunds["count"] as Number).toInt()
        val refundsCents = (refunds["coalesce"] as Number).toLong()

        return StationRevenueReport(
            stationId = stationId,
            from = from,
            to = to,
            ticketsSold = ticketsSold,
            grossRevenueCents = grossRevenue,
            refundsCount = refundsCount,
            refundsCents = refundsCents,
            netRevenueCents = grossRevenue - refundsCents,
        )
    }

    fun passengerFlow(stationId: UUID?, from: Instant, to: Instant): PassengerFlowReport {
        val tripsCount = jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT trip_id)
            FROM sales.tickets
            WHERE issued_at >= ? AND issued_at <= ?
            """.trimIndent(),
            Int::class.java,
            Timestamp.from(from),
            Timestamp.from(to),
        ) ?: 0
        val issued = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.tickets
            WHERE issued_at >= ? AND issued_at <= ?
            """.trimIndent(),
            Int::class.java,
            Timestamp.from(from),
            Timestamp.from(to),
        ) ?: 0
        val boarded = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.tickets
            WHERE status = 'USED' AND issued_at >= ? AND issued_at <= ?
            """.trimIndent(),
            Int::class.java,
            Timestamp.from(from),
            Timestamp.from(to),
        ) ?: 0
        val refunded = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.tickets
            WHERE status = 'REFUNDED' AND issued_at >= ? AND issued_at <= ?
            """.trimIndent(),
            Int::class.java,
            Timestamp.from(from),
            Timestamp.from(to),
        ) ?: 0
        return PassengerFlowReport(
            stationId = stationId,
            from = from,
            to = to,
            tripsCount = tripsCount,
            passengersIssued = issued,
            passengersBoarded = boarded,
            passengersRefunded = refunded,
        )
    }
}
