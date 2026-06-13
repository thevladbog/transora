package ru.transora.app.sales

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.sales.domain.Ticket
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/tickets")
class TicketController(
    private val ticketService: TicketService,
) {
    @PostMapping
    fun issue(@Valid @RequestBody request: IssueTicketRequest): TicketResponse =
        ticketService.issue(request).toResponse()
}

data class IssueTicketRequest(
    @field:NotNull val reservationId: UUID?,
    @field:NotNull val shiftId: UUID?,
    @field:NotBlank val passengerName: String,
    @field:Min(0) val priceCents: Long,
)

data class TicketResponse(
    val id: String,
    val reservationId: String,
    val shiftId: String,
    val tripId: String,
    val seatNumber: Int,
    val passengerName: String,
    val priceCents: Long,
    val status: String,
    val issuedAt: Instant,
)

private fun Ticket.toResponse(): TicketResponse =
    TicketResponse(
        id = id.toString(),
        reservationId = reservationId.toString(),
        shiftId = shiftId.toString(),
        tripId = tripId.toString(),
        seatNumber = seatNumber,
        passengerName = passengerName,
        priceCents = priceCents,
        status = status.name,
        issuedAt = issuedAt,
    )
