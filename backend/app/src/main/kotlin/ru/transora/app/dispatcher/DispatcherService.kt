package ru.transora.app.dispatcher

import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.admin.AuditLogService
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.app.iam.security.currentUserId
import ru.transora.app.scheduling.ScheduleEntryRepository
import ru.transora.app.scheduling.TripRepository
import java.time.Clock
import java.util.UUID

@Service
class DispatcherService(
    private val dispatcherRepository: DispatcherRepository,
    private val tripRepository: TripRepository,
    private val scheduleEntryRepository: ScheduleEntryRepository,
    private val auditLogService: AuditLogService,
) {
    @Transactional
    fun createSalesRestriction(request: CreateSalesRestrictionRequest): SalesRestrictionResponse {
        val tripId = request.tripId
        val scheduleEntryId = request.scheduleEntryId
        if ((tripId == null) == (scheduleEntryId == null)) {
            throw DomainRuleViolation("Exactly one of tripId or scheduleEntryId must be provided")
        }
        if (request.allowedSeats.isEmpty()) {
            throw DomainRuleViolation("allowedSeats must not be empty")
        }

        val stationId = StationScope.requireStationId()
        val scope = when {
            tripId != null -> {
                tripRepository.findById(tripId)
                    ?: throw NoSuchElementException("Trip $tripId was not found")
                request.scope?.takeIf { it == "SPECIFIC_TRIP" } ?: "SPECIFIC_TRIP"
            }
            else -> {
                val entryId = scheduleEntryId!!
                if (!scheduleEntryRepository.findById(entryId)) {
                    throw NoSuchElementException("Schedule entry $entryId was not found")
                }
                request.scope?.takeIf { it == "SCHEDULE_ENTRY" } ?: "SCHEDULE_ENTRY"
            }
        }

        val id = UUID.randomUUID()
        dispatcherRepository.insertSalesRestriction(
            id = id,
            tripId = tripId,
            scheduleEntryId = scheduleEntryId,
            stationId = stationId,
            allowedSeats = request.allowedSeats.toIntArray(),
            scope = scope,
        )
        auditLogService.record(
            module = "dispatcher",
            action = "sales_restriction.created",
            entityType = "sales_restriction",
            entityId = id.toString(),
        )
        return dispatcherRepository.findSalesRestriction(id)!!.toResponse()
    }

    @Transactional
    fun pauseSalesRestriction(restrictionId: UUID): SalesRestrictionResponse {
        val restriction = requireRestriction(restrictionId)
        assertRestrictionStationAccess(restriction.stationId)
        if (restriction.status != "PAUSED") {
            dispatcherRepository.updateSalesRestrictionStatus(restrictionId, "PAUSED")
            auditLogService.record(
                module = "dispatcher",
                action = "sales_restriction.paused",
                entityType = "sales_restriction",
                entityId = restrictionId.toString(),
            )
        }
        return dispatcherRepository.findSalesRestriction(restrictionId)!!.toResponse()
    }

    @Transactional
    fun resumeSalesRestriction(restrictionId: UUID): SalesRestrictionResponse {
        val restriction = requireRestriction(restrictionId)
        assertRestrictionStationAccess(restriction.stationId)
        if (restriction.status != "ACTIVE") {
            dispatcherRepository.updateSalesRestrictionStatus(restrictionId, "ACTIVE")
            auditLogService.record(
                module = "dispatcher",
                action = "sales_restriction.resumed",
                entityType = "sales_restriction",
                entityId = restrictionId.toString(),
            )
        }
        return dispatcherRepository.findSalesRestriction(restrictionId)!!.toResponse()
    }

    @Transactional
    fun createSeatBlock(request: CreateSeatBlockRequest): SeatBlockResponse {
        val stationId = StationScope.requireStationId()
        val tripId = requireNotNull(request.tripId)
        tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")

        val id = UUID.randomUUID()
        dispatcherRepository.insertSeatBlock(
            id = id,
            tripId = tripId,
            seatNumber = request.seatNumber,
            blockType = request.blockType ?: "MANUAL",
            reason = request.reason,
            blockedBy = currentUserId(),
            stationId = stationId,
        )
        auditLogService.record(
            module = "dispatcher",
            action = "seat_block.created",
            entityType = "seat_block",
            entityId = id.toString(),
        )
        return dispatcherRepository.findSeatBlock(id)!!.toResponse()
    }

    @Transactional
    fun releaseSeatBlock(blockId: UUID): SeatBlockResponse {
        val block = dispatcherRepository.findActiveSeatBlock(blockId)
            ?: throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Seat block $blockId is not active or was already released",
            )

        val principal = currentPrincipal() ?: throw AccessDeniedException("Unauthenticated")
        if (!principal.isSuperuser && block.blockedBy != null && block.blockedBy != principal.userId) {
            throw AccessDeniedException("Only the dispatcher who created the block or a system admin can release it")
        }

        val releasedAt = Clock.systemUTC().instant()
        val updated = dispatcherRepository.releaseSeatBlock(blockId, principal.userId, releasedAt)
        if (updated == 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Seat block $blockId is not active or was already released",
            )
        }

        auditLogService.record(
            module = "dispatcher",
            action = "seat_block.released",
            entityType = "seat_block",
            entityId = blockId.toString(),
        )
        return dispatcherRepository.findSeatBlock(blockId)!!.toResponse()
    }

    private fun requireRestriction(restrictionId: UUID): SalesRestrictionRow =
        dispatcherRepository.findSalesRestriction(restrictionId)
            ?: throw NoSuchElementException("Sales restriction $restrictionId was not found")

    private fun assertRestrictionStationAccess(stationId: UUID?) {
        if (stationId != null) {
            StationScope.assertStationAccess(stationId)
        }
    }
}

private fun SalesRestrictionRow.toResponse() = SalesRestrictionResponse(
    id = id.toString(),
    tripId = tripId?.toString(),
    scheduleEntryId = scheduleEntryId?.toString(),
    stationId = stationId?.toString(),
    allowedSeats = allowedSeats,
    status = status,
    scope = scope,
    createdAt = createdAt,
)

private fun SeatBlockRow.toResponse() = SeatBlockResponse(
    id = id.toString(),
    tripId = tripId.toString(),
    seatNumber = seatNumber,
    blockType = blockType,
    reason = reason,
    createdAt = createdAt,
    releasedAt = releasedAt,
)
