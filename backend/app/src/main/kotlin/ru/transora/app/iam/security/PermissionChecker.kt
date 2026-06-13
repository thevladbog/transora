package ru.transora.app.iam.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import ru.transora.iam.permissions.RolePermissionMatrix

@Component("perm")
class PermissionChecker {
    fun check(permission: String): Boolean {
        val auth = SecurityContextHolder.getContext().authentication ?: return false
        val principal = auth.principal as? JwtPrincipal ?: return false
        if (principal.isSuperuser) return true
        return auth.authorities.any { it.authority == permission }
    }
}

fun grantAuthorities(principal: JwtPrincipal) =
    if (principal.isSuperuser) {
        RolePermissionMatrix.allPermissions.map { org.springframework.security.core.authority.SimpleGrantedAuthority(it) }
    } else {
        principal.permissions.map { org.springframework.security.core.authority.SimpleGrantedAuthority(it) }
    }
