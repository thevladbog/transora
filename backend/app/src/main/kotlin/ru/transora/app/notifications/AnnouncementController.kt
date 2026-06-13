package ru.transora.app.notifications

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/announcements")
@Tag(name = "Announcements", description = "Dispatcher announcement queue")
class AnnouncementController(
    private val announcementService: AnnouncementService,
) {
    @GetMapping("/queue")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "List announcement queue for current station")
    fun queue(): AnnouncementQueueResponse = announcementService.listQueue()

    @GetMapping("/{id}")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Get announcement by id")
    fun get(@PathVariable id: UUID): AnnouncementResponse = announcementService.get(id)

    @PostMapping
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Create announcement")
    fun create(@Valid @RequestBody request: CreateAnnouncementRequest): AnnouncementResponse =
        announcementService.create(request)

    @PutMapping("/{id}")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Update announcement")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateAnnouncementRequest,
    ): AnnouncementResponse = announcementService.update(id, request)

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Delete announcement")
    fun delete(@PathVariable id: UUID) {
        announcementService.delete(id)
    }
}

data class CreateAnnouncementRequest(
    val tripId: UUID? = null,
    val announcementType: String? = null,
    val priority: String? = null,
    @field:NotBlank val textContent: String,
    val scheduledAt: Instant? = null,
)

data class UpdateAnnouncementRequest(
    val tripId: UUID? = null,
    val announcementType: String? = null,
    val priority: String? = null,
    val textContent: String? = null,
    val status: String? = null,
    val scheduledAt: Instant? = null,
)

data class AnnouncementResponse(
    val id: String,
    val stationCode: String,
    val tripId: String?,
    val priority: String,
    val textContent: String,
    val status: String,
    val scheduledAt: Instant?,
    val createdAt: Instant,
)

data class AnnouncementQueueResponse(
    val stationCode: String,
    val items: List<AnnouncementResponse>,
)
