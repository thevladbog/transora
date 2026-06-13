package ru.transora.app.iam

import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.iam.security.StationScope
import ru.transora.iam.domain.IamUser
import ru.transora.iam.domain.UserType
import java.time.Instant
import java.util.UUID

@Service
class UserAdminService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authAuditRepository: AuthAuditRepository,
) {
    private val passwordEncoder = BCryptPasswordEncoder(12)

    @Transactional
    fun createUser(request: CreateUserRequest, createdBy: UUID): IamUser {
        if (userRepository.findByLogin(request.login) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Login already exists")
        }
        val userType = request.userType ?: UserType.USER
        if (userType == UserType.USER && request.password.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password required for USER accounts")
        }
        val passwordHash = when {
            userType == UserType.SERVICE && request.password.isNullOrBlank() ->
                passwordEncoder.encode(UUID.randomUUID().toString())!!
            else -> passwordEncoder.encode(request.password!!)!!
        }
        val userId = UUID.randomUUID()
        val user = IamUser(
            id = userId,
            login = request.login.trim().lowercase(),
            passwordHash = passwordHash,
            fullName = request.fullName.trim(),
            email = request.email?.trim(),
            phone = request.phone?.trim(),
            userType = userType,
            isActive = true,
            isSuperuser = false,
            lastLoginAt = null,
            createdAt = Instant.now(),
        )
        userRepository.insert(user)
        request.assignments.orEmpty().forEach { assignment ->
            val role = roleRepository.findByCode(assignment.roleCode)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role ${assignment.roleCode}")
            stationAssignmentRepository.assign(userId, assignment.stationId, role.id, createdBy)
        }
        return user
    }

    @Transactional
    fun deactivate(userId: UUID, actorId: UUID) {
        if (userId == actorId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot deactivate your own account")
        }
        val user = userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!user.isActive) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User already inactive")
        }
        if (user.isSuperuser) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot deactivate superuser account")
        }
        userRepository.setActive(userId, false)
        refreshTokenRepository.revokeAllForUser(userId)
        authAuditRepository.log(userId, "USER_DEACTIVATED", null, null)
    }

    @Transactional
    fun activate(userId: UUID) {
        val user = userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.isActive) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User already active")
        }
        userRepository.setActive(userId, true)
        authAuditRepository.log(userId, "USER_ACTIVATED", null, null)
    }

    @Transactional
    fun changePassword(userId: UUID, newPassword: String) {
        if (newPassword.length < 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters")
        }
        userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        userRepository.updatePassword(userId, passwordEncoder.encode(newPassword)!!)
        authAuditRepository.log(userId, "PASSWORD_CHANGED", null, null)
    }

    @Transactional
    fun revokeAssignment(userId: UUID, assignmentId: UUID, actorId: UUID, isSuperuser: Boolean) {
        userRepository.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val assignment = stationAssignmentRepository.findById(assignmentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found")
        if (assignment.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found")
        }
        if (!assignment.isActive) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Assignment already revoked")
        }
        if (!isSuperuser) {
            StationScope.assertStationAccess(assignment.stationId)
        }
        val updated = stationAssignmentRepository.revoke(assignmentId, actorId)
        if (updated == 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Assignment already revoked")
        }
        authAuditRepository.log(userId, "ASSIGNMENT_REVOKED", null, null, "assignmentId=$assignmentId")
    }
}
