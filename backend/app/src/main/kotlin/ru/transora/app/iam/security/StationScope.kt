package ru.transora.app.iam.security

import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

object StationScope {
    fun currentStationId(): UUID? = currentPrincipal()?.stationId

    fun requireStationId(): UUID {
        val stationId = currentStationId()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Station context required (login with stationId)")
        return stationId
    }

    fun assertStationAccess(stationId: UUID) {
        val principal = currentPrincipal() ?: throw AccessDeniedException("Unauthenticated")
        if (principal.isSuperuser) return
        if (principal.stationId != stationId) {
            throw AccessDeniedException("Operation not allowed for station $stationId")
        }
    }

    fun assertSameStationOrSuperuser(stationId: UUID?) {
        if (stationId == null) return
        assertStationAccess(stationId)
    }
}

fun currentUserId(): UUID =
    currentPrincipal()?.userId ?: throw AccessDeniedException("Unauthenticated")
