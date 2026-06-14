package ru.transora.app.sales

import org.springframework.stereotype.Service
import ru.transora.app.admin.TariffProfileAdminRepository
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.scheduling.TripRepository
import java.util.UUID

data class TariffQuote(
    val tariffId: UUID,
    val priceCents: Long,
)

@Service
class TariffCalculator(
    private val tariffRepository: TariffRepository,
    private val tariffProfileAdminRepository: TariffProfileAdminRepository,
    private val tripRepository: TripRepository,
) {
    fun calculate(tripId: UUID, fromStopOrder: Int, toStopOrder: Int): TariffQuote {
        if (toStopOrder <= fromStopOrder) {
            throw DomainRuleViolation("toStopOrder must be greater than fromStopOrder")
        }
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")

        trip.routeId?.let { routeId ->
            val profile = tariffProfileAdminRepository.findActiveByRouteId(routeId)
            if (profile != null) {
                val price = tariffProfileAdminRepository.findCellPrice(profile.id, fromStopOrder, toStopOrder)
                    ?: tariffProfileAdminRepository.findCellPrice(profile.id, toStopOrder, fromStopOrder)
                if (price != null) {
                    return TariffQuote(profile.id, price)
                }
            }
        }

        val tariff = tariffRepository.findActive(trip.routeNumber, fromStopOrder, toStopOrder)
            ?: throw DomainRuleViolation("No active tariff for route ${trip.routeNumber} segment $fromStopOrder-$toStopOrder")
        return TariffQuote(tariff.id, tariff.priceCents)
    }
}
