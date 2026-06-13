package ru.transora.app.notifications

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.admin.AuditLogService
import ru.transora.app.iam.security.StationScope
import ru.transora.app.scheduling.ServiceStationRepository
import java.time.Clock
import java.util.UUID

@Service
class AnnouncementService(
    private val announcementRepository: AnnouncementRepository,
    private val serviceStationRepository: ServiceStationRepository,
    private val auditLogService: AuditLogService,
) {
    fun listQueue(): AnnouncementQueueResponse {
        val stationCode = resolveStationCode()
        val items = announcementRepository.listQueue(stationCode).map { it.toResponse() }
        return AnnouncementQueueResponse(stationCode = stationCode, items = items)
    }

    fun get(id: UUID): AnnouncementResponse {
        val stationCode = resolveStationCode()
        return announcementRepository.findById(id, stationCode)?.toResponse()
            ?: throw NoSuchElementException("Announcement $id was not found")
    }

    @Transactional
    fun create(request: CreateAnnouncementRequest): AnnouncementResponse {
        val stationCode = resolveStationCode()
        val now = Clock.systemUTC().instant()
        val row = AnnouncementRow(
            id = UUID.randomUUID(),
            stationCode = stationCode,
            tripId = request.tripId,
            priority = request.priority ?: "MEDIUM",
            textContent = request.textContent.trim(),
            status = "QUEUED",
            scheduledAt = request.scheduledAt ?: now,
            createdAt = now,
        )
        announcementRepository.insert(row)
        auditLogService.record(
            module = "notifications",
            action = "announcement.created",
            entityType = "announcement",
            entityId = row.id.toString(),
        )
        return row.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpdateAnnouncementRequest): AnnouncementResponse {
        val stationCode = resolveStationCode()
        val existing = announcementRepository.findById(id, stationCode)
            ?: throw NoSuchElementException("Announcement $id was not found")
        val updated = existing.copy(
            tripId = request.tripId ?: existing.tripId,
            priority = request.priority ?: existing.priority,
            textContent = request.textContent?.trim() ?: existing.textContent,
            status = request.status ?: existing.status,
            scheduledAt = request.scheduledAt ?: existing.scheduledAt,
        )
        announcementRepository.update(updated)
        auditLogService.record(
            module = "notifications",
            action = "announcement.updated",
            entityType = "announcement",
            entityId = id.toString(),
        )
        return updated.toResponse()
    }

    @Transactional
    fun delete(id: UUID) {
        val stationCode = resolveStationCode()
        val deleted = announcementRepository.delete(id, stationCode)
        if (deleted == 0) {
            throw NoSuchElementException("Announcement $id was not found")
        }
        auditLogService.record(
            module = "notifications",
            action = "announcement.deleted",
            entityType = "announcement",
            entityId = id.toString(),
        )
    }

    private fun resolveStationCode(): String {
        val stationId = StationScope.requireStationId()
        return serviceStationRepository.findById(stationId)?.code ?: "T1"
    }
}

private fun AnnouncementRow.toResponse() = AnnouncementResponse(
    id = id.toString(),
    stationCode = stationCode,
    tripId = tripId?.toString(),
    priority = priority,
    textContent = textContent,
    status = status,
    scheduledAt = scheduledAt,
    createdAt = createdAt,
)
