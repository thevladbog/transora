package ru.transora.app.iam.security

import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import ru.transora.iam.permissions.RolePermissionMatrix

@Aspect
@Component
class RequirePermissionAspect {
    @Before("@annotation(requirePermission)")
    fun check(joinPoint: JoinPoint, requirePermission: RequirePermission) {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw AccessDeniedException("Unauthenticated")
        val principal = auth.principal as? JwtPrincipal ?: throw AccessDeniedException("Unauthenticated")
        if (principal.isSuperuser) return
        if (auth.authorities.none { it.authority == requirePermission.value }) {
            throw AccessDeniedException("Missing permission ${requirePermission.value}")
        }
    }

    @Before("@within(requirePermission) && execution(* *(..)) && !@annotation(ru.transora.app.iam.security.RequirePermission)")
    fun checkClass(joinPoint: JoinPoint, requirePermission: RequirePermission) {
        check(joinPoint, requirePermission)
    }
}
