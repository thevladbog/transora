package ru.transora.app.outbox.handlers

import org.springframework.stereotype.Component
import ru.transora.app.admin.AuditLogService
import ru.transora.app.notifications.BoardRefreshCoordinator
import java.util.UUID

@Component
class InventoryOutboxSideEffects(
    private val auditLogService: AuditLogService,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
) {
    fun audit(action: String, entityType: String, entityId: String, detailsJson: String? = null) {
        auditLogService.record(
            module = "inventory",
            action = action,
            entityType = entityType,
            entityId = entityId,
            detailsJson = detailsJson,
        )
    }

    fun refreshBoard(tripId: UUID) {
        boardRefreshCoordinator.refreshForTrip(tripId, null, "inventory.updated")
    }
}
