package ru.transora.app.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class NomenclatureAdminRepository(
    private val jdbc: JdbcTemplate,
) {
    fun list(): List<NomenclatureRow> =
        jdbc.query(
            """
            SELECT n.*, p.name AS refund_policy_name
            FROM sales.nomenclature_items n
            LEFT JOIN sales.commerce_policies p ON p.id = n.refund_policy_id
            ORDER BY n.name
            """.trimIndent(),
            { rs, _ -> rs.toRow() },
        )

    fun findById(id: UUID): NomenclatureRow? =
        jdbc.query(
            """
            SELECT n.*, p.name AS refund_policy_name
            FROM sales.nomenclature_items n
            LEFT JOIN sales.commerce_policies p ON p.id = n.refund_policy_id
            WHERE n.id = ?
            """.trimIndent(),
            { rs, _ -> rs.toRow() },
            id,
        ).firstOrNull()

    fun listActive(): List<NomenclatureRow> =
        jdbc.query(
            """
            SELECT n.*, p.name AS refund_policy_name
            FROM sales.nomenclature_items n
            LEFT JOIN sales.commerce_policies p ON p.id = n.refund_policy_id
            WHERE n.is_active = TRUE
            ORDER BY n.name
            """.trimIndent(),
            { rs, _ -> rs.toRow() },
        )

    fun insert(row: NomenclatureRow) {
        jdbc.update(
            """
            INSERT INTO sales.nomenclature_items (
                id, code, name, category, price_cents, refund_policy_id, is_active, description, created_at,
                sale_mode, pricing_mode, route_percent, min_price_cents, max_price_cents, max_qty_per_ticket,
                refund_allowed, print_name, ffd_payment_object, ffd_payment_method, ffd_vat_tag, ffd_measure_code
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            row.id,
            row.code,
            row.name,
            row.category,
            row.priceCents,
            row.refundPolicyId,
            row.isActive,
            row.description,
            Timestamp.from(row.createdAt),
            row.saleMode,
            row.pricingMode,
            row.routePercent,
            row.minPriceCents,
            row.maxPriceCents,
            row.maxQtyPerTicket,
            row.refundAllowed,
            row.printName,
            row.ffdPaymentObject,
            row.ffdPaymentMethod,
            row.ffdVatTag,
            row.ffdMeasureCode,
        )
    }

    fun update(row: NomenclatureRow) {
        jdbc.update(
            """
            UPDATE sales.nomenclature_items
            SET code = ?, name = ?, category = ?, price_cents = ?, refund_policy_id = ?,
                is_active = ?, description = ?, sale_mode = ?, pricing_mode = ?, route_percent = ?,
                min_price_cents = ?, max_price_cents = ?, max_qty_per_ticket = ?, refund_allowed = ?,
                print_name = ?, ffd_payment_object = ?, ffd_payment_method = ?, ffd_vat_tag = ?,
                ffd_measure_code = ?
            WHERE id = ?
            """.trimIndent(),
            row.code,
            row.name,
            row.category,
            row.priceCents,
            row.refundPolicyId,
            row.isActive,
            row.description,
            row.saleMode,
            row.pricingMode,
            row.routePercent,
            row.minPriceCents,
            row.maxPriceCents,
            row.maxQtyPerTicket,
            row.refundAllowed,
            row.printName,
            row.ffdPaymentObject,
            row.ffdPaymentMethod,
            row.ffdVatTag,
            row.ffdMeasureCode,
            row.id,
        )
    }

    fun delete(id: UUID): Int = jdbc.update("DELETE FROM sales.nomenclature_items WHERE id = ?", id)

    fun countByRefundPolicyId(policyId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.nomenclature_items WHERE refund_policy_id = ?",
            Int::class.java,
            policyId,
        ) ?: 0

    private fun ResultSet.toRow(): NomenclatureRow =
        NomenclatureRow(
            id = getObject("id", UUID::class.java),
            code = getString("code"),
            name = getString("name"),
            category = getString("category"),
            priceCents = getLong("price_cents"),
            refundPolicyId = getObject("refund_policy_id") as UUID?,
            refundPolicyName = getString("refund_policy_name"),
            isActive = getBoolean("is_active"),
            description = getString("description"),
            createdAt = getTimestamp("created_at").toInstant(),
            saleMode = getString("sale_mode"),
            pricingMode = getString("pricing_mode"),
            routePercent = getBigDecimal("route_percent"),
            minPriceCents = getObject("min_price_cents") as Long?,
            maxPriceCents = getObject("max_price_cents") as Long?,
            maxQtyPerTicket = getInt("max_qty_per_ticket"),
            refundAllowed = getBoolean("refund_allowed"),
            printName = getString("print_name"),
            ffdPaymentObject = getInt("ffd_payment_object"),
            ffdPaymentMethod = getInt("ffd_payment_method"),
            ffdVatTag = getInt("ffd_vat_tag"),
            ffdMeasureCode = getInt("ffd_measure_code"),
        )
}

data class NomenclatureRow(
    val id: UUID,
    val code: String,
    val name: String,
    val category: String,
    val priceCents: Long,
    val refundPolicyId: UUID?,
    val refundPolicyName: String?,
    val isActive: Boolean,
    val description: String?,
    val createdAt: Instant,
    val saleMode: String = "STANDALONE",
    val pricingMode: String = "FIXED",
    val routePercent: java.math.BigDecimal? = null,
    val minPriceCents: Long? = null,
    val maxPriceCents: Long? = null,
    val maxQtyPerTicket: Int = 1,
    val refundAllowed: Boolean = false,
    val printName: String,
    val ffdPaymentObject: Int = 4,
    val ffdPaymentMethod: Int = 4,
    val ffdVatTag: Int = 6,
    val ffdMeasureCode: Int = 0,
)

@Repository
class TariffProfileAdminRepository(
    private val jdbc: JdbcTemplate,
) {
    fun list(): List<TariffProfileRow> =
        jdbc.query(
            """
            SELECT tp.*, rp.name AS refund_policy_name,
                   (SELECT COUNT(*) FROM sales.tariff_profile_stops s WHERE s.profile_id = tp.id) AS stop_count
            FROM sales.tariff_profiles tp
            LEFT JOIN sales.commerce_policies rp ON rp.id = tp.refund_policy_id
            ORDER BY tp.name
            """.trimIndent(),
            { rs, _ -> rs.toProfile() },
        )

    fun findById(id: UUID): TariffProfileRow? =
        jdbc.query(
            """
            SELECT tp.*, rp.name AS refund_policy_name,
                   (SELECT COUNT(*) FROM sales.tariff_profile_stops s WHERE s.profile_id = tp.id) AS stop_count
            FROM sales.tariff_profiles tp
            LEFT JOIN sales.commerce_policies rp ON rp.id = tp.refund_policy_id
            WHERE tp.id = ?
            """.trimIndent(),
            { rs, _ -> rs.toProfile() },
            id,
        ).firstOrNull()

    fun findActiveByRouteId(routeId: UUID): TariffProfileRow? =
        jdbc.query(
            """
            SELECT tp.*, rp.name AS refund_policy_name,
                   (SELECT COUNT(*) FROM sales.tariff_profile_stops s WHERE s.profile_id = tp.id) AS stop_count
            FROM sales.tariff_profiles tp
            LEFT JOIN sales.commerce_policies rp ON rp.id = tp.refund_policy_id
            WHERE tp.route_id = ? AND tp.is_active = TRUE
            ORDER BY tp.created_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toProfile() },
            routeId,
        ).firstOrNull()

    fun insert(row: TariffProfileRow) {
        jdbc.update(
            """
            INSERT INTO sales.tariff_profiles (
                id, name, route_id, valid_from, valid_to, refund_policy_id, is_active, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            row.id,
            row.name,
            row.routeId,
            row.validFrom?.let { java.sql.Date.valueOf(it) },
            row.validTo?.let { java.sql.Date.valueOf(it) },
            row.refundPolicyId,
            row.isActive,
            Timestamp.from(row.createdAt),
        )
    }

    fun update(row: TariffProfileRow) {
        jdbc.update(
            """
            UPDATE sales.tariff_profiles
            SET name = ?, route_id = ?, valid_from = ?, valid_to = ?,
                refund_policy_id = ?, is_active = ?
            WHERE id = ?
            """.trimIndent(),
            row.name,
            row.routeId,
            row.validFrom?.let { java.sql.Date.valueOf(it) },
            row.validTo?.let { java.sql.Date.valueOf(it) },
            row.refundPolicyId,
            row.isActive,
            row.id,
        )
    }

    fun delete(id: UUID): Int = jdbc.update("DELETE FROM sales.tariff_profiles WHERE id = ?", id)

    fun countByRefundPolicyId(policyId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.tariff_profiles WHERE refund_policy_id = ?",
            Int::class.java,
            policyId,
        ) ?: 0

    fun listStops(profileId: UUID): List<TariffProfileStopRow> =
        jdbc.query(
            """
            SELECT s.*, p.code AS point_code, p.name AS point_name, p.city AS point_city
            FROM sales.tariff_profile_stops s
            JOIN scheduling.points p ON p.id = s.point_id
            WHERE s.profile_id = ?
            ORDER BY s.stop_order
            """.trimIndent(),
            { rs, _ -> rs.toStop() },
            profileId,
        )

    fun replaceStops(profileId: UUID, stops: List<TariffProfileStopRow>) {
        jdbc.update("DELETE FROM sales.tariff_profile_stops WHERE profile_id = ?", profileId)
        stops.forEach { stop ->
            jdbc.update(
                """
                INSERT INTO sales.tariff_profile_stops (id, profile_id, point_id, stop_order)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                stop.id,
                profileId,
                stop.pointId,
                stop.stopOrder,
            )
        }
    }

    fun listCells(profileId: UUID): List<TariffCellRow> =
        jdbc.query(
            """
            SELECT * FROM sales.tariff_cells
            WHERE profile_id = ?
            ORDER BY from_stop_order, to_stop_order
            """.trimIndent(),
            { rs, _ -> rs.toCell() },
            profileId,
        )

    fun findCellPrice(profileId: UUID, fromStopOrder: Int, toStopOrder: Int): Long? =
        jdbc.query(
            """
            SELECT price_cents FROM sales.tariff_cells
            WHERE profile_id = ? AND from_stop_order = ? AND to_stop_order = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getLong("price_cents") },
            profileId,
            fromStopOrder,
            toStopOrder,
        ).firstOrNull()

    fun upsertCells(profileId: UUID, cells: List<TariffCellRow>) {
        cells.forEach { cell ->
            jdbc.update(
                """
                INSERT INTO sales.tariff_cells (
                    id, profile_id, from_stop_order, to_stop_order, price_cents, is_mirror_override
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (profile_id, from_stop_order, to_stop_order)
                DO UPDATE SET price_cents = EXCLUDED.price_cents,
                              is_mirror_override = EXCLUDED.is_mirror_override
                """.trimIndent(),
                cell.id,
                profileId,
                cell.fromStopOrder,
                cell.toStopOrder,
                cell.priceCents,
                cell.isMirrorOverride,
            )
        }
    }

    private fun ResultSet.toProfile(): TariffProfileRow =
        TariffProfileRow(
            id = getObject("id", UUID::class.java),
            name = getString("name"),
            routeId = getObject("route_id") as UUID?,
            validFrom = getDate("valid_from")?.toLocalDate(),
            validTo = getDate("valid_to")?.toLocalDate(),
            refundPolicyId = getObject("refund_policy_id") as UUID?,
            refundPolicyName = getString("refund_policy_name"),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
            stopCount = getInt("stop_count"),
        )

    private fun ResultSet.toStop(): TariffProfileStopRow =
        TariffProfileStopRow(
            id = getObject("id", UUID::class.java),
            profileId = getObject("profile_id", UUID::class.java),
            pointId = getObject("point_id", UUID::class.java),
            stopOrder = getInt("stop_order"),
            pointCode = getString("point_code"),
            pointName = getString("point_name"),
            pointCity = getString("point_city"),
        )

    private fun ResultSet.toCell(): TariffCellRow =
        TariffCellRow(
            id = getObject("id", UUID::class.java),
            profileId = getObject("profile_id", UUID::class.java),
            fromStopOrder = getInt("from_stop_order"),
            toStopOrder = getInt("to_stop_order"),
            priceCents = getLong("price_cents"),
            isMirrorOverride = getBoolean("is_mirror_override"),
        )
}

data class TariffProfileRow(
    val id: UUID,
    val name: String,
    val routeId: UUID?,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val refundPolicyId: UUID?,
    val refundPolicyName: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val stopCount: Int = 0,
)

data class TariffProfileStopRow(
    val id: UUID,
    val profileId: UUID,
    val pointId: UUID,
    val stopOrder: Int,
    val pointCode: String? = null,
    val pointName: String? = null,
    val pointCity: String? = null,
)

data class TariffCellRow(
    val id: UUID,
    val profileId: UUID,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val priceCents: Long,
    val isMirrorOverride: Boolean,
)
