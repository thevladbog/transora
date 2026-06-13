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
                id, station_name, cashier_name, pos_id, opening_balance_cents,
                closing_balance_cents, status, opened_at, closed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            shift.id,
            shift.stationName,
            shift.cashierName,
            shift.posId,
            shift.openingBalanceCents,
            shift.closingBalanceCents,
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

    fun findOpenByPosForUpdate(posId: String): CashierShift? =
        jdbc.query(
            """
            SELECT *
            FROM sales.shifts
            WHERE pos_id = ? AND status = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toShift() },
            posId,
            ShiftStatus.OPEN.name,
        ).firstOrNull()

    fun findById(id: UUID): CashierShift? =
        jdbc.query(
            "SELECT * FROM sales.shifts WHERE id = ?",
            { rs, _ -> rs.toShift() },
            id,
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

    fun close(id: UUID, closedAt: Instant, closingBalanceCents: Long?) {
        jdbc.update(
            """
            UPDATE sales.shifts
            SET status = ?, closed_at = ?, closing_balance_cents = ?
            WHERE id = ? AND status = ?
            """.trimIndent(),
            ShiftStatus.CLOSED.name,
            Timestamp.from(closedAt),
            closingBalanceCents,
            id,
            ShiftStatus.OPEN.name,
        )
    }

    fun findFiscalShiftNo(shiftId: UUID): Int? =
        jdbc.queryForObject(
            "SELECT fiscal_shift_no FROM sales.shifts WHERE id = ?",
            Int::class.java,
            shiftId,
        )

    fun updateFiscalShiftNo(shiftId: UUID, fiscalShiftNo: Int) {
        jdbc.update(
            "UPDATE sales.shifts SET fiscal_shift_no = ? WHERE id = ?",
            fiscalShiftNo,
            shiftId,
        )
    }

    fun updateZReportReceiptId(shiftId: UUID, fiscalReceiptId: UUID) {
        jdbc.update(
            "UPDATE sales.shifts SET z_report_fiscal_receipt_id = ? WHERE id = ?",
            fiscalReceiptId,
            shiftId,
        )
    }

    fun findZReportReceiptId(shiftId: UUID): UUID? =
        jdbc.queryForObject(
            "SELECT z_report_fiscal_receipt_id FROM sales.shifts WHERE id = ?",
            UUID::class.java,
            shiftId,
        )

    private fun ResultSet.toShift(): CashierShift =
        CashierShift(
            id = getObject("id", UUID::class.java),
            stationName = getString("station_name"),
            cashierName = getString("cashier_name"),
            posId = getString("pos_id"),
            openingBalanceCents = getLong("opening_balance_cents"),
            closingBalanceCents = getObject("closing_balance_cents")?.let { getLong("closing_balance_cents") },
            status = ShiftStatus.valueOf(getString("status")),
            openedAt = getTimestamp("opened_at").toInstant(),
            closedAt = getTimestamp("closed_at")?.toInstant(),
        )
}
