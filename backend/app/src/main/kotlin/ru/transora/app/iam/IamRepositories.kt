package ru.transora.app.iam

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.iam.domain.IamRole
import ru.transora.iam.domain.IamUser
import ru.transora.iam.domain.RoleType
import ru.transora.iam.domain.StationAssignmentView
import ru.transora.iam.domain.UserType
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class UserRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findByLogin(login: String): IamUser? =
        jdbc.query(
            "SELECT * FROM iam.users WHERE login = ?",
            { rs, _ -> rs.toUser() },
            login,
        ).firstOrNull()

    fun findById(id: UUID): IamUser? =
        jdbc.query(
            "SELECT * FROM iam.users WHERE id = ?",
            { rs, _ -> rs.toUser() },
            id,
        ).firstOrNull()

    fun insert(user: IamUser) {
        jdbc.update(
            """
            INSERT INTO iam.users (
                id, login, password_hash, full_name, email, phone,
                user_type, is_active, is_superuser, last_login_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::iam.user_type, ?, ?, ?, ?, ?)
            """.trimIndent(),
            user.id, user.login, user.passwordHash, user.fullName, user.email, user.phone,
            user.userType.name, user.isActive, user.isSuperuser,
            user.lastLoginAt?.let { Timestamp.from(it) }, Timestamp.from(user.createdAt), Timestamp.from(user.createdAt),
        )
    }

    fun updateLastLogin(userId: UUID, at: Instant) {
        jdbc.update(
            "UPDATE iam.users SET last_login_at = ?, updated_at = ? WHERE id = ?",
            Timestamp.from(at), Timestamp.from(at), userId,
        )
    }

    fun setActive(userId: UUID, isActive: Boolean) {
        jdbc.update(
            "UPDATE iam.users SET is_active = ?, updated_at = ? WHERE id = ?",
            isActive, Timestamp.from(Instant.now()), userId,
        )
    }

    fun updatePassword(userId: UUID, passwordHash: String) {
        jdbc.update(
            "UPDATE iam.users SET password_hash = ?, updated_at = ? WHERE id = ?",
            passwordHash, Timestamp.from(Instant.now()), userId,
        )
    }

    fun countActiveSuperusers(): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM iam.users WHERE is_superuser = TRUE AND is_active = TRUE",
            Int::class.java,
        ) ?: 0

    fun listAll(): List<IamUser> =
        jdbc.query("SELECT * FROM iam.users ORDER BY login") { rs, _ -> rs.toUser() }

    private fun ResultSet.toUser(): IamUser =
        IamUser(
            id = getObject("id", UUID::class.java),
            login = getString("login"),
            passwordHash = getString("password_hash"),
            fullName = getString("full_name"),
            email = getString("email"),
            phone = getString("phone"),
            userType = UserType.valueOf(getString("user_type")),
            isActive = getBoolean("is_active"),
            isSuperuser = getBoolean("is_superuser"),
            lastLoginAt = getTimestamp("last_login_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

@Repository
class RoleRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findByCode(code: String): IamRole? =
        jdbc.query(
            "SELECT * FROM iam.roles WHERE code = ?",
            { rs, _ -> rs.toRole() },
            code,
        ).firstOrNull()

    fun insert(role: IamRole) {
        jdbc.update(
            """
            INSERT INTO iam.roles (id, code, name, description, role_type, is_active, created_at)
            VALUES (?, ?, ?, ?, ?::iam.role_type, ?, ?)
            """.trimIndent(),
            role.id, role.code, role.name, role.description, role.roleType.name, role.isActive,
            Timestamp.from(Instant.now()),
        )
    }

    fun insertPermission(roleId: UUID, permission: String) {
        jdbc.update(
            "INSERT INTO iam.role_permissions (role_id, permission) VALUES (?, ?) ON CONFLICT DO NOTHING",
            roleId, permission,
        )
    }

    fun permissionsForRole(roleId: UUID): Set<String> =
        jdbc.queryForList(
            "SELECT permission FROM iam.role_permissions WHERE role_id = ?",
            String::class.java,
            roleId,
        ).mapNotNull { it }.toSet()

    private fun ResultSet.toRole(): IamRole =
        IamRole(
            id = getObject("id", UUID::class.java),
            code = getString("code"),
            name = getString("name"),
            description = getString("description"),
            roleType = RoleType.valueOf(getString("role_type")),
            isActive = getBoolean("is_active"),
        )
}

@Repository
class StationAssignmentRepository(
    private val jdbc: JdbcTemplate,
) {
    fun activeAssignmentsForUser(userId: UUID): List<StationAssignmentView> =
        jdbc.query(
            """
            SELECT sa.id AS assignment_id, sa.station_id, r.code AS role_code, rp.permission
            FROM iam.station_assignments sa
            JOIN iam.roles r ON r.id = sa.role_id
            LEFT JOIN iam.role_permissions rp ON rp.role_id = r.id
            WHERE sa.user_id = ? AND sa.is_active = TRUE
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getObject("assignment_id", UUID::class.java),
                    rs.getObject("station_id", UUID::class.java),
                    rs.getString("role_code") to rs.getString("permission"),
                )
            },
            userId,
        ).groupBy({ it.first to it.second }, { it.third })
            .map { (key, pairs) ->
                val (assignmentId, stationId) = key
                val roleCode = pairs.first().first
                val perms = pairs.mapNotNull { it.second }.toSet()
                StationAssignmentView(assignmentId, stationId, roleCode, perms)
            }

    fun findById(assignmentId: UUID): StationAssignmentRecord? =
        jdbc.query(
            """
            SELECT id, user_id, station_id, is_active
            FROM iam.station_assignments
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                StationAssignmentRecord(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    stationId = rs.getObject("station_id", UUID::class.java),
                    isActive = rs.getBoolean("is_active"),
                )
            },
            assignmentId,
        ).firstOrNull()

    fun revoke(assignmentId: UUID, revokedBy: UUID): Int =
        jdbc.update(
            """
            UPDATE iam.station_assignments
            SET is_active = FALSE, revoked_at = ?, revoked_by = ?
            WHERE id = ? AND is_active = TRUE
            """.trimIndent(),
            Timestamp.from(Instant.now()), revokedBy, assignmentId,
        )

    fun hasActiveAssignment(userId: UUID, stationId: UUID): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM iam.station_assignments
                WHERE user_id = ? AND station_id = ? AND is_active = TRUE
            )
            """.trimIndent(),
            Boolean::class.java,
            userId, stationId,
        ) ?: false

    fun assign(userId: UUID, stationId: UUID, roleId: UUID, assignedBy: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO iam.station_assignments (
                id, user_id, station_id, role_id, is_active, assigned_by, assigned_at
            ) VALUES (?, ?, ?, ?, TRUE, ?, ?)
            """.trimIndent(),
            id, userId, stationId, roleId, assignedBy, Timestamp.from(Instant.now()),
        )
        return id
    }
}

data class StationAssignmentRecord(
    val id: UUID,
    val userId: UUID,
    val stationId: UUID,
    val isActive: Boolean,
)

@Repository
class ServiceTokenRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(userId: UUID, name: String, tokenHash: String, createdBy: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO iam.service_tokens (id, user_id, name, token_hash, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, userId, name, tokenHash, createdBy, Timestamp.from(Instant.now()),
        )
        return id
    }

    fun findActiveByHash(tokenHash: String): ServiceTokenRecord? =
        jdbc.query(
            """
            SELECT id, user_id, name, token_hash, last_used_at, created_by, created_at, revoked_at
            FROM iam.service_tokens
            WHERE token_hash = ? AND revoked_at IS NULL
            """,
            { rs, _ -> rs.toServiceToken() },
            tokenHash,
        ).firstOrNull()

    fun findById(tokenId: UUID): ServiceTokenRecord? =
        jdbc.query(
            """
            SELECT id, user_id, name, token_hash, last_used_at, created_by, created_at, revoked_at
            FROM iam.service_tokens
            WHERE id = ?
            """,
            { rs, _ -> rs.toServiceToken() },
            tokenId,
        ).firstOrNull()

    fun listActive(): List<ServiceTokenRecord> =
        jdbc.query(
            """
            SELECT id, user_id, name, token_hash, last_used_at, created_by, created_at, revoked_at
            FROM iam.service_tokens
            WHERE revoked_at IS NULL
            ORDER BY created_at DESC
            """,
        ) { rs, _ -> rs.toServiceToken() }

    fun revoke(tokenId: UUID, revokedBy: UUID): Int =
        jdbc.update(
            """
            UPDATE iam.service_tokens
            SET revoked_at = ?, revoked_by = ?
            WHERE id = ? AND revoked_at IS NULL
            """,
            Timestamp.from(Instant.now()), revokedBy, tokenId,
        )

    fun updateLastUsed(tokenId: UUID) {
        jdbc.update(
            "UPDATE iam.service_tokens SET last_used_at = ? WHERE id = ?",
            Timestamp.from(Instant.now()), tokenId,
        )
    }

    private fun ResultSet.toServiceToken(): ServiceTokenRecord =
        ServiceTokenRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            name = getString("name"),
            tokenHash = getString("token_hash"),
            lastUsedAt = getTimestamp("last_used_at")?.toInstant(),
            createdBy = getObject("created_by", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
            revokedAt = getTimestamp("revoked_at")?.toInstant(),
        )
}

data class ServiceTokenRecord(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val tokenHash: String,
    val lastUsedAt: Instant?,
    val createdBy: UUID,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

@Repository
class RefreshTokenRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(userId: UUID, tokenHash: String, expiresAt: Instant, userAgent: String?, ipAddress: String?) {
        jdbc.update(
            """
            INSERT INTO iam.refresh_tokens (id, user_id, token_hash, issued_at, expires_at, user_agent, ip_address)
            VALUES (?, ?, ?, ?, ?, ?, ?::inet)
            """.trimIndent(),
            UUID.randomUUID(), userId, tokenHash, Timestamp.from(Instant.now()), Timestamp.from(expiresAt),
            userAgent, ipAddress,
        )
    }

    fun findActiveByHash(tokenHash: String): UUID? =
        jdbc.query(
            """
            SELECT user_id FROM iam.refresh_tokens
            WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > NOW()
            """,
            { rs, _ -> rs.getObject("user_id", UUID::class.java) },
            tokenHash,
        ).firstOrNull()

    fun revoke(tokenHash: String) {
        jdbc.update(
            "UPDATE iam.refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            Timestamp.from(Instant.now()), tokenHash,
        )
    }

    fun revokeAllForUser(userId: UUID) {
        jdbc.update(
            "UPDATE iam.refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
            Timestamp.from(Instant.now()), userId,
        )
    }
}

@Repository
class AuthAuditRepository(
    private val jdbc: JdbcTemplate,
) {
    fun log(userId: UUID?, eventType: String, ipAddress: String?, userAgent: String?, details: String? = null) {
        jdbc.update(
            """
            INSERT INTO iam.auth_audit_log (id, user_id, event_type, ip_address, user_agent, details_json, created_at)
            VALUES (?, ?, ?, ?::inet, ?, ?::jsonb, ?)
            """.trimIndent(),
            UUID.randomUUID(), userId, eventType, ipAddress, userAgent,
            details?.let { """{"message":"$it"}""" }, Timestamp.from(Instant.now()),
        )
    }
}
