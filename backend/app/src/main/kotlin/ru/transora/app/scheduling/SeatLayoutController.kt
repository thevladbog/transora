package ru.transora.app.scheduling

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import ru.transora.scheduling.domain.SeatLayout
import java.util.UUID

@RestController
@RequestMapping("/api/seat-layouts")
@Tag(name = "Seat Layouts", description = "Seat layout templates")
class SeatLayoutController(
    private val seatLayoutService: SeatLayoutService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun list(): List<SeatLayoutResponse> = seatLayoutService.list().map { it.toResponse() }

    @GetMapping("/{layoutId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun get(@PathVariable layoutId: UUID): SeatLayoutResponse =
        seatLayoutService.get(layoutId).toResponse()

    @PostMapping
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    fun create(@Valid @RequestBody request: CreateSeatLayoutBody): SeatLayoutResponse =
        seatLayoutService.create(
            CreateSeatLayoutRequest(
                name = request.name,
                totalSeats = request.totalSeats,
                layoutJson = request.layoutJson,
            ),
        ).toResponse()
}

data class CreateSeatLayoutBody(
    @field:NotBlank val name: String,
    @field:Min(1) val totalSeats: Int,
    @field:NotBlank val layoutJson: String,
)

data class SeatLayoutResponse(
    val id: String,
    val name: String,
    val totalSeats: Int,
    val layoutJson: String,
)

private fun SeatLayout.toResponse() = SeatLayoutResponse(
    id = id.toString(),
    name = name,
    totalSeats = totalSeats,
    layoutJson = layoutJson,
)
