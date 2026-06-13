package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.scheduling.domain.Schedule
import ru.transora.scheduling.domain.ScheduleEntry
import ru.transora.scheduling.domain.ScheduleType
import ru.transora.scheduling.domain.ScheduleWithEntries
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
class ScheduleService(
    private val scheduleRepository: ScheduleRepository,
    private val routeRepository: RouteRepository,
    private val schedulingEventPublisher: SchedulingEventPublisher,
) {
    fun list(): List<Schedule> = scheduleRepository.list()

    fun get(id: UUID): ScheduleWithEntries =
        scheduleRepository.findWithEntries(id) ?: throw NoSuchElementException("Schedule $id was not found")

    @Transactional
    fun create(request: CreateScheduleRequest): ScheduleWithEntries {
        routeRepository.findById(request.routeId)
            ?: throw NoSuchElementException("Route ${request.routeId} was not found")
        validateScheduleDates(request.scheduleType, request.validFrom, request.validTo)

        val principal = currentPrincipal()
            ?: throw IllegalStateException("Authenticated user required to create schedule")
        val now = Clock.systemUTC().instant()
        val scheduleId = UUID.randomUUID()
        val schedule = Schedule(
            id = scheduleId,
            routeId = request.routeId,
            name = request.name.trim(),
            scheduleType = request.scheduleType,
            validFrom = request.validFrom,
            validTo = request.validTo,
            isActive = true,
            createdBy = principal.userId,
            createdAt = now,
            updatedAt = now,
        )
        scheduleRepository.insert(schedule)

        val entries = request.entries.map { entryRequest ->
            val entry = ScheduleEntry(
                id = UUID.randomUUID(),
                scheduleId = scheduleId,
                tripNumber = entryRequest.tripNumber.trim(),
                departureTime = entryRequest.departureTime,
                daysOfWeek = entryRequest.daysOfWeek,
                defaultVehicleId = entryRequest.defaultVehicleId,
                isActive = true,
                createdAt = now,
            )
            scheduleRepository.insertEntry(entry)
            entry
        }

        return ScheduleWithEntries(schedule, entries)
    }

    @Transactional
    fun update(id: UUID, request: UpdateScheduleRequest): ScheduleWithEntries {
        val existing = get(id)
        val scheduleType = request.scheduleType ?: existing.schedule.scheduleType
        val validFrom = request.validFrom ?: existing.schedule.validFrom
        val validTo = request.validTo ?: existing.schedule.validTo
        validateScheduleDates(scheduleType, validFrom, validTo)

        val now = Clock.systemUTC().instant()
        val updatedSchedule = existing.schedule.copy(
            name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.schedule.name,
            scheduleType = scheduleType,
            validFrom = validFrom,
            validTo = validTo,
            isActive = request.isActive ?: existing.schedule.isActive,
            updatedAt = now,
        )
        scheduleRepository.update(updatedSchedule)

        val entries = if (request.entries != null) {
            scheduleRepository.deleteEntries(id)
            request.entries.map { entryRequest ->
                val entry = ScheduleEntry(
                    id = UUID.randomUUID(),
                    scheduleId = id,
                    tripNumber = entryRequest.tripNumber.trim(),
                    departureTime = entryRequest.departureTime,
                    daysOfWeek = entryRequest.daysOfWeek,
                    defaultVehicleId = entryRequest.defaultVehicleId,
                    isActive = entryRequest.isActive ?: true,
                    createdAt = now,
                )
                scheduleRepository.insertEntry(entry)
                entry
            }
        } else {
            existing.entries
        }

        schedulingEventPublisher.publishScheduleUpdated(updatedSchedule.id, updatedSchedule.routeId)

        return ScheduleWithEntries(updatedSchedule, entries)
    }

    private fun validateScheduleDates(scheduleType: ScheduleType, validFrom: LocalDate?, validTo: LocalDate?) {
        when (scheduleType) {
            ScheduleType.PERMANENT -> {
                if (validFrom != null || validTo != null) {
                    throw DomainRuleViolation("PERMANENT schedule must not have validity dates")
                }
            }
            ScheduleType.SEASONAL -> {
                if (validFrom == null || validTo == null) {
                    throw DomainRuleViolation("SEASONAL schedule requires valid_from and valid_to")
                }
                if (!validTo.isAfter(validFrom)) {
                    throw DomainRuleViolation("valid_to must be after valid_from")
                }
            }
            ScheduleType.EXCEPTION -> {
                if (validFrom == null || validTo == null) {
                    throw DomainRuleViolation("EXCEPTION schedule requires valid_from and valid_to")
                }
                if (validFrom != validTo) {
                    throw DomainRuleViolation("EXCEPTION schedule must have valid_from equal to valid_to")
                }
            }
        }
    }
}

data class ScheduleEntryRequest(
    val tripNumber: String,
    val departureTime: java.time.LocalTime,
    val daysOfWeek: List<Int>,
    val defaultVehicleId: UUID? = null,
    val isActive: Boolean? = null,
)

data class CreateScheduleRequest(
    val routeId: UUID,
    val name: String,
    val scheduleType: ScheduleType,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val entries: List<ScheduleEntryRequest>,
)

data class UpdateScheduleRequest(
    val name: String? = null,
    val scheduleType: ScheduleType? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val isActive: Boolean? = null,
    val entries: List<ScheduleEntryRequest>? = null,
)
