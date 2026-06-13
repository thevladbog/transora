package ru.transora.app.dev

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
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

object DevStationIds {
    val TERMINAL_1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
}

@Component
@ConditionalOnProperty(prefix = "transora.iam", name = ["seed-data"], havingValue = "true")
class IamDevSeeder(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val stationAssignmentRepository: StationAssignmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val passwordEncoder = BCryptPasswordEncoder(12)

    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        if (userRepository.findByLogin("admin") != null) {
            log.info("IAM dev seed skipped: users already exist")
            return
        }

        val adminUserId = UUID.randomUUID()
        val adminUser = IamUser(
            id = adminUserId,
            login = "admin",
            passwordHash = passwordEncoder.encode("admin")!!,
            fullName = "System Administrator",
            email = "admin@transora.local",
            phone = null,
            userType = UserType.USER,
            isActive = true,
            isSuperuser = true,
            lastLoginAt = null,
            createdAt = Instant.now(),
        )
        userRepository.insert(adminUser)

        val roleIds = seedRoles(adminUserId)
        seedDemoUsers(adminUserId, roleIds)
        log.info("IAM dev seed created roles and demo users (admin/admin, cashier/cashier, dispatcher/dispatcher)")
    }

    private fun seedRoles(createdBy: UUID): Map<String, UUID> {
        val roles = listOf(
            Triple(RoleCodes.CASHIER, "Cashier", RoleType.SYSTEM),
            Triple(RoleCodes.DISPATCHER, "Dispatcher", RoleType.SYSTEM),
            Triple(RoleCodes.STATION_ADMIN, "Station Admin", RoleType.SYSTEM),
            Triple(RoleCodes.INSPECTOR, "Inspector", RoleType.SYSTEM),
            Triple(RoleCodes.STATION_AGENT, "Station Agent", RoleType.SYSTEM),
        )
        return roles.associate { (code, name, type) ->
            val id = UUID.randomUUID()
            roleRepository.insert(
                IamRole(id = id, code = code, name = name, description = null, roleType = type, isActive = true),
            )
            RolePermissionMatrix.permissionsFor(code).forEach { roleRepository.insertPermission(id, it) }
            code to id
        }
    }

    private fun seedDemoUsers(adminUserId: UUID, roleIds: Map<String, UUID>) {
        val stationId = DevStationIds.TERMINAL_1
        listOf(
            DemoUser("cashier", "Demo Cashier", RoleCodes.CASHIER),
            DemoUser("dispatcher", "Demo Dispatcher", RoleCodes.DISPATCHER),
            DemoUser("inspector", "Demo Inspector", RoleCodes.INSPECTOR),
            DemoUser("station_agent", "Station Agent T1", RoleCodes.STATION_AGENT),
        ).forEach { demo ->
            val userId = UUID.randomUUID()
            userRepository.insert(
                IamUser(
                    id = userId,
                    login = demo.login,
                    passwordHash = passwordEncoder.encode(demo.login)!!,
                    fullName = demo.fullName,
                    email = "${demo.login}@transora.local",
                    phone = null,
                    userType = UserType.USER,
                    isActive = true,
                    isSuperuser = false,
                    lastLoginAt = null,
                    createdAt = Instant.now(),
                ),
            )
            stationAssignmentRepository.assign(userId, stationId, roleIds.getValue(demo.roleCode), adminUserId)
        }
    }

    private data class DemoUser(val login: String, val fullName: String, val roleCode: String)
}
