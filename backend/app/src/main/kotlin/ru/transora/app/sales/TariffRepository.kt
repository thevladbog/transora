package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.Tariff
import java.sql.ResultSet
import java.util.UUID

@Repository
class TariffRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findActive(routeNumber: String, fromStopOrder: Int, toStopOrder: Int): Tariff? {
        val exact = jdbc.query(
            """
            SELECT *
            FROM sales.tariffs
            WHERE route_number = ?
              AND from_stop_order = ?
              AND to_stop_order = ?
              AND is_active = TRUE
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toTariff() },
            routeNumber,
            fromStopOrder,
            toStopOrder,
        ).firstOrNull()
        if (exact != null) return exact

        return jdbc.query(
            """
            SELECT *
            FROM sales.tariffs
            WHERE route_number = 'DEFAULT'
              AND from_stop_order <= ?
              AND to_stop_order >= ?
              AND is_active = TRUE
            ORDER BY to_stop_order - from_stop_order
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toTariff() },
            fromStopOrder,
            toStopOrder,
        ).firstOrNull()
    }

    private fun ResultSet.toTariff(): Tariff =
        Tariff(
            id = getObject("id", UUID::class.java),
            routeNumber = getString("route_number"),
            fromStopOrder = getInt("from_stop_order"),
            toStopOrder = getInt("to_stop_order"),
            priceCents = getLong("price_cents"),
            isActive = getBoolean("is_active"),
        )
}
