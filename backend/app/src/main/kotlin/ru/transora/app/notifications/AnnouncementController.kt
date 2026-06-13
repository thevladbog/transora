package ru.transora.app.notifications

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
@Tag(name = "Announcements", description = "Dispatcher announcement queue and audio playback")
class AnnouncementController(
    private val announcementService: AnnouncementService,
    private val announcementPlaybackService: AnnouncementPlaybackService,
) {
    @GetMapping("/queue")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "List announcement queue for current station")
    fun queue(): AnnouncementQueueResponse = announcementService.listQueue()

    @GetMapping("/templates")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "List active announcement templates")
    fun templates(): List<AnnouncementTemplateResponse> = announcementService.listTemplates()

    @PostMapping("/queue/pause")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Pause announcement playback for current station")
    fun pauseQueue() {
        announcementService.pauseQueue()
    }

    @PostMapping("/queue/resume")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Resume announcement playback for current station")
    fun resumeQueue() {
        announcementService.resumeQueue()
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Get announcement by id")
    fun get(@PathVariable id: UUID): AnnouncementResponse = announcementService.get(id)

    @GetMapping("/{id}/audio")
    @RequirePermission(Permissions.ANNOUNCEMENTS_PLAY_AUDIO)
    @Operation(summary = "Download synthesized announcement audio")
    fun audio(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (contentType, bytes) = announcementPlaybackService.getAudioContent(id)
            ?: throw NoSuchElementException("Announcement audio $id was not found")
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"announcement-$id.wav\"")
            .contentType(MediaType.parseMediaType(contentType))
            .body(bytes)
    }

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

data class AnnouncementTemplateResponse(
    val code: String,
    val name: String,
    val templateText: String,
    val priority: String,
)

data class AnnouncementQueueResponse(
    val stationCode: String,
    val queuePaused: Boolean,
    val items: List<AnnouncementResponse>,
)
