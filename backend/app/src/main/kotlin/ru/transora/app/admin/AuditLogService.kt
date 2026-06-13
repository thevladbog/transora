package ru.transora.app.admin

import org.springframework.stereotype.Service
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentUserId
import java.util.UUID

@Service
class AuditLogService(
    private val auditLogRepository: AuditLogRepository,
) {
    fun record(
        module: String,
        action: String,
        stationId: UUID? = StationScope.currentStationId(),
        entityType: String? = null,
        entityId: String? = null,
        detailsJson: String? = null,
    ) {
        auditLogRepository.append(
            actorId = runCatching { currentUserId() }.getOrNull(),
            stationId = stationId,
            module = module,
            action = action,
            entityType = entityType,
            entityId = entityId,
            detailsJson = detailsJson,
        )
    }
}
