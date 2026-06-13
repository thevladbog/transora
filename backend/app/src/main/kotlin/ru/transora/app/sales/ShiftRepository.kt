package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.CashierShift
import ru.transora.sales.domain.ShiftStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ShiftRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(shift: CashierShift) {
        jdbc.update(
            """
            INSERT INTO sales.shifts (
                id, station_name, cashier_name, status, opened_at, closed_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            shift.id,
            shift.stationName,
            shift.cashierName,
            shift.status.name,
            Timestamp.from(shift.openedAt),
            shift.closedAt?.let { Timestamp.from(it) },
        )
    }

    fun findOpenByCashierForUpdate(cashierName: String): CashierShift? =
        jdbc.query(
            """
            SELECT *
            FROM sales.shifts
            WHERE cashier_name = ? AND status = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toShift() },
            cashierName,
            ShiftStatus.OPEN.name,
        ).firstOrNull()

    fun findOpenByIdForUpdate(id: UUID): CashierShift? =
        jdbc.query(
            """
            SELECT *
            FROM sales.shifts
            WHERE id = ? AND status = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toShift() },
            id,
            ShiftStatus.OPEN.name,
        ).firstOrNull()

    fun listOpen(): List<CashierShift> =
        jdbc.query(
            """
            SELECT *
            FROM sales.shifts
            WHERE status = ?
            ORDER BY opened_at
            """.trimIndent(),
            { rs, _ -> rs.toShift() },
            ShiftStatus.OPEN.name,
        )

    fun close(id: UUID, closedAt: Instant) {
        jdbc.update(
            """
            UPDATE sales.shifts
            SET status = ?, closed_at = ?
            WHERE id = ? AND status = ?
            """.trimIndent(),
            ShiftStatus.CLOSED.name,
            Timestamp.from(closedAt),
            id,
            ShiftStatus.OPEN.name,
        )
    }

    private fun ResultSet.toShift(): CashierShift =
        CashierShift(
            id = getObject("id", UUID::class.java),
            stationName = getString("station_name"),
            cashierName = getString("cashier_name"),
            status = ShiftStatus.valueOf(getString("status")),
            openedAt = getTimestamp("opened_at").toInstant(),
            closedAt = getTimestamp("closed_at")?.toInstant(),
        )
}

