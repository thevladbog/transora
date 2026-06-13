package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.OrderItem
import ru.transora.sales.domain.DocType
import java.sql.ResultSet
import java.util.UUID

@Repository
class OrderItemRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(item: OrderItem) {
        jdbc.update(
            """
            INSERT INTO sales.order_items (
                id, order_id, reservation_id, trip_id, seat_number, passenger_name,
                doc_type, doc_number, from_stop_order, to_stop_order, tariff_id, price_cents
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            item.id,
            item.orderId,
            item.reservationId,
            item.tripId,
            item.seatNumber,
            item.passengerName,
            item.docType.name,
            item.docNumber,
            item.fromStopOrder,
            item.toStopOrder,
            item.tariffId,
            item.priceCents,
        )
    }

    fun findByOrderId(orderId: UUID): List<OrderItem> =
        jdbc.query(
            "SELECT * FROM sales.order_items WHERE order_id = ?",
            { rs, _ -> rs.toOrderItem() },
            orderId,
        )

    private fun ResultSet.toOrderItem(): OrderItem =
        OrderItem(
            id = getObject("id", UUID::class.java),
            orderId = getObject("order_id", UUID::class.java),
            reservationId = getObject("reservation_id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            seatNumber = getInt("seat_number"),
            passengerName = getString("passenger_name"),
            docType = DocType.valueOf(getString("doc_type")),
            docNumber = getString("doc_number"),
            fromStopOrder = getInt("from_stop_order"),
            toStopOrder = getInt("to_stop_order"),
            tariffId = getObject("tariff_id", UUID::class.java),
            priceCents = getLong("price_cents"),
        )
}
