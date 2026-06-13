package ru.transora.app.sales

import org.springframework.stereotype.Service
import ru.transora.app.scheduling.TripRepository
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class TicketNumberGenerator(
    private val ticketRepository: TicketRepository,
    private val tripRepository: TripRepository,
) {
    fun generate(tripId: java.util.UUID): String {
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val stationCode = trip.departureStationCode
        val issueDate = LocalDate.now(ZoneOffset.UTC)
        val sequence = ticketRepository.nextDailySequence(stationCode, issueDate)
        return "${issueDate.year}${stationCode.padStart(3, '0')}${sequence.toString().padStart(6, '0')}"
    }
}
