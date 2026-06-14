package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.scheduling.domain.Route
import ru.transora.scheduling.domain.RouteStop
import ru.transora.scheduling.domain.RouteWithStops
import java.time.Clock
import java.util.UUID

@Service
class RouteService(
    private val routeRepository: RouteRepository,
    private val carrierRepository: CarrierRepository,
) {
    fun list(): List<Route> = routeRepository.list()

    fun get(id: UUID): RouteWithStops =
        routeRepository.findWithStops(id) ?: throw NoSuchElementException("Route $id was not found")

    @Transactional
    fun create(request: CreateRouteRequest): RouteWithStops {
        carrierRepository.findById(request.carrierId)
            ?: throw NoSuchElementException("Carrier ${request.carrierId} was not found")
        if (request.stops.isNotEmpty()) {
            validateStops(request.stops)
        }

        val now = Clock.systemUTC().instant()
        val routeId = UUID.randomUUID()
        val routeNumber = request.routeNumber.trim().ifBlank {
            request.code?.trim()?.takeIf { it.isNotEmpty() } ?: request.name.trim()
        }
        val route = Route(
            id = routeId,
            carrierId = request.carrierId,
            name = request.name.trim(),
            code = request.code?.trim()?.takeIf { it.isNotEmpty() },
            routeNumber = routeNumber,
            description = request.description?.trim(),
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
        routeRepository.insert(route)

        val stops = request.stops.map { stopRequest ->
            val stop = stopRequest.toRouteStop(routeId)
            routeRepository.insertStop(stop)
            stop
        }

        return RouteWithStops(route, stops)
    }

    @Transactional
    fun update(id: UUID, request: UpdateRouteRequest): RouteWithStops {
        val existing = get(id)
        if (request.isActive == false) {
            if (routeRepository.countActiveSchedules(id) > 0 || routeRepository.countFutureTrips(id) > 0) {
                throw DomainRuleViolation(
                    "Route cannot be deactivated while active schedules or future trips exist",
                )
            }
        }

        val now = Clock.systemUTC().instant()
        val updatedRoute = existing.route.copy(
            name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.route.name,
            code = request.code ?: existing.route.code,
            routeNumber = request.routeNumber?.trim()?.takeIf { it.isNotEmpty() } ?: existing.route.routeNumber,
            description = request.description ?: existing.route.description,
            isActive = request.isActive ?: existing.route.isActive,
            updatedAt = now,
        )
        routeRepository.update(updatedRoute)

        val stops = if (request.stops != null) {
            if (request.stops.isNotEmpty()) {
                validateStops(request.stops)
            }
            routeRepository.deleteStops(id)
            request.stops.map { stopRequest ->
                val stop = stopRequest.toRouteStop(id)
                routeRepository.insertStop(stop)
                stop
            }
        } else {
            existing.stops
        }

        return RouteWithStops(updatedRoute, stops)
    }

    private fun validateStops(stops: List<RouteStopRequest>) {
        if (stops.size < 2) {
            throw DomainRuleViolation("Route must contain at least 2 stops")
        }
        val orders = stops.map { it.stopOrder }
        if (orders.toSet().size != orders.size) {
            throw DomainRuleViolation("Stop order must be unique within a route")
        }
        if (orders.min() != 1) {
            throw DomainRuleViolation("Stop order must start from 1")
        }
        stops.filter { it.stopOrder > 1 }.forEach { stop ->
            if (stop.scheduledDurationMin == null || stop.scheduledDurationMin <= 0) {
                throw DomainRuleViolation("Scheduled duration must be > 0 for stop order ${stop.stopOrder}")
            }
        }
        stops.filter { !it.isExternal && it.stationId == null }.forEach {
            throw DomainRuleViolation("Non-external stop '${it.stopName}' must have a station")
        }
    }

    private fun RouteStopRequest.toRouteStop(routeId: UUID): RouteStop =
        RouteStop(
            id = UUID.randomUUID(),
            routeId = routeId,
            stopOrder = stopOrder,
            stopName = stopName.trim(),
            stationId = stationId,
            pointId = pointId,
            isExternal = isExternal,
            scheduledDurationMin = scheduledDurationMin,
            dwellTimeMin = dwellTimeMin,
        )
}

data class RouteStopRequest(
    val stopOrder: Int,
    val stopName: String,
    val stationId: UUID? = null,
    val pointId: UUID? = null,
    val isExternal: Boolean = false,
    val scheduledDurationMin: Int? = null,
    val dwellTimeMin: Int = 5,
)

data class CreateRouteRequest(
    val carrierId: UUID,
    val name: String,
    val code: String? = null,
    val routeNumber: String = "",
    val description: String? = null,
    val stops: List<RouteStopRequest> = emptyList(),
)

data class UpdateRouteRequest(
    val name: String? = null,
    val code: String? = null,
    val routeNumber: String? = null,
    val description: String? = null,
    val isActive: Boolean? = null,
    val stops: List<RouteStopRequest>? = null,
)
