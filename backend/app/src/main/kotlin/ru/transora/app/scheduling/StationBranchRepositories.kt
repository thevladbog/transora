package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ProvisioningTokenRow(
    val id: UUID,
    val stationId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val createdBy: UUID?,
)

data class StationAgentStatusRow(
    val stationId: UUID,
    val connected: Boolean,
    val lastSeenAt: Instant?,
    val agentVersion: String?,
    val updatedAt: Instant,
)

@Repository
class StationProvisioningRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(stationId: UUID, tokenHash: String, expiresAt: Instant, createdBy: UUID?): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO scheduling.station_provisioning_tokens (
                id, station_id, token_hash, expires_at, created_by, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            stationId,
            tokenHash,
            Timestamp.from(expiresAt),
            createdBy,
            Timestamp.from(Instant.now()),
        )
        return id
    }

    fun findActiveByHash(tokenHash: String): ProvisioningTokenRow? =
        jdbc.query(
            """
            SELECT id, station_id, token_hash, expires_at, used_at, created_by
            FROM scheduling.station_provisioning_tokens
            WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
            """,
            { rs, _ -> rs.toProvisioningToken() },
            tokenHash,
        ).firstOrNull()

    fun markUsed(id: UUID) {
        jdbc.update(
            "UPDATE scheduling.station_provisioning_tokens SET used_at = ? WHERE id = ? AND used_at IS NULL",
            Timestamp.from(Instant.now()),
            id,
        )
    }

    private fun ResultSet.toProvisioningToken(): ProvisioningTokenRow =
        ProvisioningTokenRow(
            id = getObject("id", UUID::class.java),
            stationId = getObject("station_id", UUID::class.java),
            tokenHash = getString("token_hash"),
            expiresAt = getTimestamp("expires_at").toInstant(),
            usedAt = getTimestamp("used_at")?.toInstant(),
            createdBy = getObject("created_by", UUID::class.java),
        )
}

@Repository
class StationAgentStatusRepository(
    private val jdbc: JdbcTemplate,
) {
    fun upsertConnected(stationId: UUID, connected: Boolean, agentVersion: String?) {
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            INSERT INTO scheduling.station_agent_status (
                station_id, connected, last_seen_at, agent_version, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (station_id) DO UPDATE SET
                connected = EXCLUDED.connected,
                last_seen_at = EXCLUDED.last_seen_at,
                agent_version = COALESCE(EXCLUDED.agent_version, scheduling.station_agent_status.agent_version),
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            stationId,
            connected,
            now,
            agentVersion,
            now,
        )
    }

    fun touch(stationId: UUID, agentVersion: String?) {
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            INSERT INTO scheduling.station_agent_status (
                station_id, connected, last_seen_at, agent_version, updated_at
            ) VALUES (?, TRUE, ?, ?, ?)
            ON CONFLICT (station_id) DO UPDATE SET
                connected = TRUE,
                last_seen_at = EXCLUDED.last_seen_at,
                agent_version = COALESCE(EXCLUDED.agent_version, scheduling.station_agent_status.agent_version),
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            stationId,
            now,
            agentVersion,
            now,
        )
    }

    fun findByStationId(stationId: UUID): StationAgentStatusRow? =
        jdbc.query(
            """
            SELECT station_id, connected, last_seen_at, agent_version, updated_at
            FROM scheduling.station_agent_status
            WHERE station_id = ?
            """,
            { rs, _ -> rs.toStatus() },
            stationId,
        ).firstOrNull()

    private fun ResultSet.toStatus(): StationAgentStatusRow =
        StationAgentStatusRow(
            stationId = getObject("station_id", UUID::class.java),
            connected = getBoolean("connected"),
            lastSeenAt = getTimestamp("last_seen_at")?.toInstant(),
            agentVersion = getString("agent_version"),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
}
