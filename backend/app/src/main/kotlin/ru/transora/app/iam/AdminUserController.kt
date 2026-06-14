package ru.transora.app.iam

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.iam.security.RequirePermission
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.iam.domain.IamUser
import ru.transora.iam.domain.UserType
import ru.transora.iam.permissions.Permissions
import java.util.UUID

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Admin Users", description = "IAM user management")
class AdminUserController(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
    private val userAdminService: UserAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.USERS_VIEW)
    fun list(@RequestParam(required = false) stationId: UUID?): List<UserSummaryResponse> {
        val principal = currentPrincipal() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val filterStationId = stationId ?: StationScope.currentStationId()
        val users = userRepository.listAll()
        if (filterStationId == null) {
            if (!principal.isSuperuser) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Station context required")
            }
            return users.map { it.toSummary() }
        }
        val assignedUserIds = users.mapNotNull { user ->
            val assignments = stationAssignmentRepository.activeAssignmentsForUser(user.id)
            if (assignments.any { it.stationId == filterStationId }) user.id else null
        }.toSet()
        return users.filter { it.id in assignedUserIds }.map { it.toSummary() }
    }

    @GetMapping("/{userId}")
    @RequirePermission(Permissions.USERS_VIEW)
    fun get(@PathVariable userId: UUID): UserDetailResponse {
        val user = userRepository.findById(userId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val assignments = stationAssignmentRepository.activeAssignmentsForUser(userId)
        return UserDetailResponse(
            userId = user.id.toString(),
            login = user.login,
            fullName = user.fullName,
            email = user.email,
            isActive = user.isActive,
            isSuperuser = user.isSuperuser,
            userType = user.userType.name,
            assignments = assignments.map {
                AssignmentResponse(
                    assignmentId = it.assignmentId.toString(),
                    stationId = it.stationId.toString(),
                    roleCode = it.roleCode,
                    permissions = it.permissions.toList(),
                )
            },
        )
    }

    @PostMapping
    @RequirePermission(Permissions.USERS_CREATE)
    fun create(@Valid @RequestBody request: CreateUserRequest): UserSummaryResponse {
        val principal = currentPrincipal() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return userAdminService.createUser(request, principal.userId).toSummary()
    }

    @PostMapping("/{userId}/assignments")
    @RequirePermission(Permissions.USERS_CREATE)
    fun assign(
        @PathVariable userId: UUID,
        @Valid @RequestBody request: AssignmentRequest,
    ): AssignmentResponse {
        userRepository.findById(userId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val principal = currentPrincipal() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val role = roleRepository.findByCode(request.roleCode)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role ${request.roleCode}")
        if (!principal.isSuperuser) {
            StationScope.assertStationAccess(request.stationId)
        }
        val assignmentId = stationAssignmentRepository.assign(userId, request.stationId, role.id, principal.userId)
        val view = stationAssignmentRepository.activeAssignmentsForUser(userId)
            .first { it.assignmentId == assignmentId }
        return AssignmentResponse(
            assignmentId = view.assignmentId.toString(),
            stationId = view.stationId.toString(),
            roleCode = view.roleCode,
            permissions = view.permissions.toList(),
        )
    }

    @PostMapping("/{userId}/deactivate")
    @RequirePermission(Permissions.USERS_DEACTIVATE)
    fun deactivate(@PathVariable userId: UUID) {
        val principal = currentPrincipal() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        userAdminService.deactivate(userId, principal.userId)
    }

    @PostMapping("/{userId}/activate")
    @RequirePermission(Permissions.USERS_DEACTIVATE)
    fun activate(@PathVariable userId: UUID) {
        userAdminService.activate(userId)
    }

    @PostMapping("/{userId}/change-password")
    @RequirePermission(Permissions.USERS_EDIT)
    fun changePassword(
        @PathVariable userId: UUID,
        @Valid @RequestBody request: ChangePasswordRequest,
    ) {
        userAdminService.changePassword(userId, request.newPassword)
    }

    @DeleteMapping("/{userId}/assignments/{assignmentId}")
    @RequirePermission(Permissions.USERS_EDIT)
    fun revokeAssignment(
        @PathVariable userId: UUID,
        @PathVariable assignmentId: UUID,
    ) {
        val principal = currentPrincipal() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        userAdminService.revokeAssignment(userId, assignmentId, principal.userId, principal.isSuperuser)
    }
}

@RestController
@RequestMapping("/api/admin/service-tokens")
@Tag(name = "Service Tokens", description = "IAM service token management (superuser only)")
class ServiceTokenController(
    private val serviceTokenService: ServiceTokenService,
) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreateServiceTokenRequest): ServiceTokenCreatedResponse {
        requireSuperuser()
        val principal = currentPrincipal()!!
        return serviceTokenService.create(request.userId, request.name, principal.userId)
    }

    @GetMapping
    fun list(): List<ServiceTokenSummaryResponse> {
        requireSuperuser()
        return serviceTokenService.listActive()
    }

    @DeleteMapping("/{tokenId}")
    fun revoke(@PathVariable tokenId: UUID) {
        requireSuperuser()
        val principal = currentPrincipal()!!
        serviceTokenService.revoke(tokenId, principal.userId)
    }

    private fun requireSuperuser() {
        val principal = currentPrincipal() ?: throw AccessDeniedException("Unauthenticated")
        if (!principal.isSuperuser) {
            throw AccessDeniedException("Superuser required")
        }
    }
}

data class CreateUserRequest(
    @field:NotBlank val login: String,
    val password: String? = null,
    @field:NotBlank val fullName: String,
    val email: String? = null,
    val phone: String? = null,
    val userType: UserType? = null,
    val assignments: List<AssignmentRequest>? = null,
)

data class AssignmentRequest(
    val stationId: UUID,
    @field:NotBlank val roleCode: String,
)

data class ChangePasswordRequest(
    @field:NotBlank val newPassword: String,
)

data class CreateServiceTokenRequest(
    val userId: UUID,
    @field:NotBlank val name: String,
)

data class UserSummaryResponse(
    val userId: String,
    val login: String,
    val fullName: String,
    val isActive: Boolean,
    val isSuperuser: Boolean,
)

data class UserDetailResponse(
    val userId: String,
    val login: String,
    val fullName: String,
    val email: String?,
    val isActive: Boolean,
    val isSuperuser: Boolean,
    val userType: String,
    val assignments: List<AssignmentResponse>,
)

data class AssignmentResponse(
    val assignmentId: String,
    val stationId: String,
    val roleCode: String,
    val permissions: List<String>,
)

private fun IamUser.toSummary() = UserSummaryResponse(
    userId = id.toString(),
    login = login,
    fullName = fullName,
    isActive = isActive,
    isSuperuser = isSuperuser,
)
