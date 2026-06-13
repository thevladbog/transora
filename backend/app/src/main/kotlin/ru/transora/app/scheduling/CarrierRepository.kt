package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.Carrier
import ru.transora.scheduling.domain.ContractType
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class CarrierRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(carrier: Carrier) {
        jdbc.update(
            """
            INSERT INTO scheduling.carriers (
                id, name, legal_name, inn, contract_type, is_active, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            carrier.id,
            carrier.name,
            carrier.legalName,
            carrier.inn,
            carrier.contractType.name,
            carrier.isActive,
            Timestamp.from(carrier.createdAt),
            Timestamp.from(carrier.updatedAt),
        )
    }

    fun findById(id: UUID): Carrier? =
        jdbc.query(
            "SELECT * FROM scheduling.carriers WHERE id = ?",
            { rs, _ -> rs.toCarrier() },
            id,
        ).firstOrNull()

    fun list(): List<Carrier> =
        jdbc.query(
            "SELECT * FROM scheduling.carriers ORDER BY name",
        ) { rs, _ -> rs.toCarrier() }

    fun update(carrier: Carrier): Int =
        jdbc.update(
            """
            UPDATE scheduling.carriers
            SET name = ?, legal_name = ?, inn = ?, contract_type = ?, is_active = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            carrier.name,
            carrier.legalName,
            carrier.inn,
            carrier.contractType.name,
            carrier.isActive,
            Timestamp.from(carrier.updatedAt),
            carrier.id,
        )

    private fun ResultSet.toCarrier(): Carrier =
        Carrier(
            id = getObject("id", UUID::class.java),
            name = getString("name"),
            legalName = getString("legal_name"),
            inn = getString("inn"),
            contractType = ContractType.valueOf(getString("contract_type")),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
}
