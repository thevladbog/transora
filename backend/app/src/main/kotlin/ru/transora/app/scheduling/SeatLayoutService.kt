package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.scheduling.domain.SeatLayout
import java.time.Clock
import java.util.UUID

@Service
class SeatLayoutService(
    private val seatLayoutRepository: SeatLayoutRepository,
) {
    fun list(): List<SeatLayout> = seatLayoutRepository.list()

    fun get(id: UUID): SeatLayout =
        seatLayoutRepository.findById(id) ?: throw NoSuchElementException("Seat layout $id was not found")

    @Transactional
    fun create(request: CreateSeatLayoutRequest): SeatLayout {
        val layout = SeatLayout(
            id = UUID.randomUUID(),
            name = request.name.trim(),
            totalSeats = request.totalSeats,
            layoutJson = request.layoutJson.trim(),
            createdAt = Clock.systemUTC().instant(),
        )
        seatLayoutRepository.insert(layout)
        return layout
    }
}

data class CreateSeatLayoutRequest(
    val name: String,
    val totalSeats: Int,
    val layoutJson: String,
)
