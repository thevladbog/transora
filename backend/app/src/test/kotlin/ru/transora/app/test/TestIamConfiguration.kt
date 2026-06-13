package ru.transora.app.test

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.boot.context.event.ApplicationReadyEvent
import ru.transora.app.dev.DevStationIds
import ru.transora.app.iam.RoleRepository
import ru.transora.app.iam.StationAssignmentRepository
import ru.transora.app.iam.UserRepository
import ru.transora.iam.domain.IamRole
import ru.transora.iam.domain.IamUser
import ru.transora.iam.domain.RoleType
import ru.transora.iam.domain.UserType
import ru.transora.iam.permissions.RoleCodes
import ru.transora.iam.permissions.RolePermissionMatrix
import java.time.Instant
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class TestIamConfiguration(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
) {
    private val passwordEncoder = BCryptPasswordEncoder(12)

    @EventListener(ApplicationReadyEvent::class)
    fun seedIam() {
        if (userRepository.findByLogin("cashier") != null) return

        val adminId = UUID.randomUUID()
        userRepository.insert(
            IamUser(
                id = adminId,
                login = "admin",
                passwordHash = passwordEncoder.encode("admin")!!,
                fullName = "Test Admin",
                email = null,
                phone = null,
                userType = UserType.USER,
                isActive = true,
                isSuperuser = true,
                lastLoginAt = null,
                createdAt = Instant.now(),
            ),
        )

        val roleIds = listOf(
            RoleCodes.CASHIER,
            RoleCodes.DISPATCHER,
            RoleCodes.INSPECTOR,
            RoleCodes.STATION_AGENT,
        ).associateWith { code ->
            val id = UUID.randomUUID()
            roleRepository.insert(
                IamRole(id = id, code = code, name = code, description = null, roleType = RoleType.SYSTEM, isActive = true),
            )
            RolePermissionMatrix.permissionsFor(code).forEach { roleRepository.insertPermission(id, it) }
            id
        }

        listOf(
            "cashier" to RoleCodes.CASHIER,
            "dispatcher" to RoleCodes.DISPATCHER,
            "inspector" to RoleCodes.INSPECTOR,
            "station_agent" to RoleCodes.STATION_AGENT,
        ).forEach { (login, role) ->
            val userId = UUID.randomUUID()
            userRepository.insert(
                IamUser(
                    id = userId,
                    login = login,
                    passwordHash = passwordEncoder.encode(login)!!,
                    fullName = login,
                    email = null,
                    phone = null,
                    userType = UserType.USER,
                    isActive = true,
                    isSuperuser = false,
                    lastLoginAt = null,
                    createdAt = Instant.now(),
                ),
            )
            stationAssignmentRepository.assign(userId, DevStationIds.TERMINAL_1, roleIds.getValue(role), adminId)
        }
    }
}
