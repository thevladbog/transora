package ru.transora.app.notifications

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.util.UUID

@RestController
@RequestMapping("/api/display-boards")
@Tag(name = "Display Boards", description = "Display board registration and heartbeat")
class DisplayBoardController(
    private val displayBoardService: DisplayBoardService,
) {
    @PostMapping("/register")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Register or refresh a display board agent")
    fun register(@Valid @RequestBody request: RegisterDisplayBoardRequestBody): DisplayBoardResponse =
        displayBoardService.register(
            RegisterDisplayBoardRequest(
                agentId = request.agentId,
                boardType = request.boardType,
                name = request.name,
                platformNumber = request.platformNumber,
            ),
        )

    @PostMapping("/{boardId}/heartbeat")
    @RequirePermission(Permissions.ANNOUNCEMENTS_MANAGE)
    @Operation(summary = "Heartbeat for a registered display board")
    fun heartbeat(@PathVariable boardId: UUID) {
        displayBoardService.heartbeat(boardId)
    }
}

data class RegisterDisplayBoardRequestBody(
    @field:NotBlank val agentId: String,
    @field:NotBlank val boardType: String,
    @field:NotBlank val name: String,
    val platformNumber: String? = null,
)
