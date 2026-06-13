package ru.transora.iam.domain

import java.time.Instant
import java.util.UUID

enum class UserType { USER, SERVICE }

enum class RoleType { SYSTEM, CUSTOM }

data class IamUser(
    val id: UUID,
    val login: String,
    val passwordHash: String,
    val fullName: String,
    val email: String?,
    val phone: String?,
    val userType: UserType,
    val isActive: Boolean,
    val isSuperuser: Boolean,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
)

data class IamRole(
    val id: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val roleType: RoleType,
    val isActive: Boolean,
)

data class StationAssignment(
    val id: UUID,
    val userId: UUID,
    val stationId: UUID,
    val roleId: UUID,
    val roleCode: String,
    val permissions: Set<String>,
    val isActive: Boolean,
)

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

data class AuthenticatedUser(
    val userId: UUID,
    val login: String,
    val fullName: String,
    val isSuperuser: Boolean,
    val assignments: List<StationAssignmentView>,
    val permissions: Set<String>,
    val stationId: UUID?,
)

data class StationAssignmentView(
    val assignmentId: UUID,
    val stationId: UUID,
    val roleCode: String,
    val permissions: Set<String>,
)
