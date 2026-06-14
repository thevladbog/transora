package ru.transora.app.admin

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.scheduling.CarrierRepository
import ru.transora.app.scheduling.CreateRouteRequest
import ru.transora.app.scheduling.RouteRepository
import ru.transora.app.scheduling.RouteService
import ru.transora.app.scheduling.RouteStopRequest
import ru.transora.app.scheduling.ServiceStationRepository
import ru.transora.app.scheduling.UpdateRouteRequest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
class RoutePricingAdminService(
    private val routeService: RouteService,
    private val routeRepository: RouteRepository,
    private val carrierRepository: CarrierRepository,
    private val pointAdminRepository: PointAdminRepository,
    private val serviceStationRepository: ServiceStationRepository,
    private val tariffProfileAdminService: TariffProfileAdminService,
    private val tariffProfileAdminRepository: TariffProfileAdminRepository,
    private val osrmRoutingService: OsrmRoutingService,
    private val auditLogService: AuditLogService,
) {
    fun list(): List<RoutePricingSummaryResponse> =
        routeService.list().map { route ->
            val profile = tariffProfileAdminRepository.findActiveByRouteId(route.id)
            val stopCount = profile?.stopCount ?: 0
            val cellCount = profile?.let { tariffProfileAdminRepository.listCells(it.id).size } ?: 0
            RoutePricingSummaryResponse(
                routeId = route.id.toString(),
                carrierId = route.carrierId.toString(),
                carrierName = carrierRepository.findById(route.carrierId)?.name,
                code = route.code,
                routeNumber = route.routeNumber,
                name = route.name,
                isActive = route.isActive,
                tariffProfileId = profile?.id?.toString(),
                stopCount = stopCount,
                matrixCellCount = cellCount,
            )
        }

    fun get(routeId: UUID): RoutePricingBundleResponse = buildBundle(routeId)

    @Transactional
    fun create(request: CreateRoutePricingRequest): RoutePricingBundleResponse {
        carrierRepository.findById(request.carrierId)
            ?: throw NoSuchElementException("Carrier ${request.carrierId} was not found")
        val created = routeService.create(
            CreateRouteRequest(
                carrierId = request.carrierId,
                name = request.name,
                code = request.code,
                routeNumber = request.routeNumber.orEmpty(),
                description = request.description,
                stops = emptyList(),
            ),
        )
        tariffProfileAdminService.create(
            UpsertTariffProfileRequest(
                name = request.name,
                routeId = created.route.id,
                isActive = true,
            ),
        )
        auditLogService.record(
            module = "admin",
            action = "route_pricing.created",
            entityType = "route",
            entityId = created.route.id.toString(),
        )
        return buildBundle(created.route.id)
    }

    @Transactional
    fun updateMeta(routeId: UUID, request: UpdateRoutePricingRequest): RoutePricingBundleResponse {
        val profile = ensureProfile(routeId)
        val validFrom = request.validFrom ?: profile.validFrom
        val validTo = request.validTo ?: profile.validTo
        validateDateRange(validFrom, validTo)
        routeService.update(
            routeId,
            UpdateRouteRequest(
                name = request.name,
                code = request.code,
                routeNumber = request.routeNumber,
                description = request.description,
                isActive = request.isActive,
            ),
        )
        tariffProfileAdminService.update(
            profile.id,
            UpsertTariffProfileRequest(
                name = request.name ?: profile.name,
                routeId = routeId,
                validFrom = validFrom,
                validTo = validTo,
                refundPolicyId = profile.refundPolicyId,
                isActive = request.isActive ?: profile.isActive,
            ),
        )
        return buildBundle(routeId)
    }

    @Transactional
    fun syncStops(routeId: UUID, request: SyncRouteStopsRequest): RoutePricingBundleResponse {
        val profile = ensureProfile(routeId)
        if (request.pointIds.isEmpty()) {
            throw DomainRuleViolation("At least one stop is required")
        }
        val distinct = request.pointIds.distinct()
        if (distinct.size != request.pointIds.size) {
            throw DomainRuleViolation("Duplicate points are not allowed")
        }

        tariffProfileAdminService.replaceStops(profile.id, request.pointIds)

        if (request.pointIds.size >= 2) {
            val routeStops = buildRouteStops(request.pointIds, request.legDurationsMin)
            routeService.update(routeId, UpdateRouteRequest(stops = routeStops))
        } else {
            routeRepository.deleteStops(routeId)
        }

        auditLogService.record(
            module = "admin",
            action = "route_pricing.stops_synced",
            entityType = "route",
            entityId = routeId.toString(),
        )
        return buildBundle(routeId)
    }

    @Transactional
    fun upsertMatrix(routeId: UUID, request: UpsertTariffMatrixRequest): RoutePricingBundleResponse {
        val profile = ensureProfile(routeId)
        tariffProfileAdminService.upsertMatrix(profile.id, request)
        return buildBundle(routeId)
    }

    @Transactional
    fun deactivate(routeId: UUID): RoutePricingBundleResponse {
        routeService.update(routeId, UpdateRouteRequest(isActive = false))
        return buildBundle(routeId)
    }

    private fun buildBundle(routeId: UUID): RoutePricingBundleResponse {
        val routeWithStops = routeService.get(routeId)
        val route = routeWithStops.route
        val profile = ensureProfile(routeId)
        val matrix = tariffProfileAdminService.getMatrix(profile.id)
        val stops = matrix.stops.map { stop ->
            val station = serviceStationRepository.findByPointId(UUID.fromString(stop.pointId))
            val routeStop = routeWithStops.stops.find { it.pointId?.toString() == stop.pointId }
            RoutePricingStopResponse(
                stopOrder = stop.stopOrder,
                pointId = stop.pointId,
                pointCode = stop.pointCode,
                pointName = stop.pointName,
                pointCity = stop.pointCity,
                stationId = station?.id?.toString(),
                stationCode = station?.code,
                isBranch = station != null,
                scheduledDurationMin = routeStop?.scheduledDurationMin,
            )
        }
        val waypoints = matrix.stops.mapNotNull { stop ->
            val point = pointAdminRepository.findById(UUID.fromString(stop.pointId)) ?: return@mapNotNull null
            RouteWaypoint(stopOrder = stop.stopOrder, latitude = point.latitude, longitude = point.longitude)
        }
        val distance = osrmRoutingService.calculate(waypoints)
        return RoutePricingBundleResponse(
            routeId = route.id.toString(),
            carrierId = route.carrierId.toString(),
            code = route.code,
            routeNumber = route.routeNumber,
            name = route.name,
            description = route.description,
            isActive = route.isActive,
            tariffProfileId = profile.id.toString(),
            validFrom = profile.validFrom,
            validTo = profile.validTo,
            stops = stops,
            cells = matrix.cells,
            distanceKm = distance.distanceKm,
            distanceSource = distance.distanceSource?.name,
            durationMin = distance.durationMin,
            legs = distance.legs.map {
                RouteLegDistanceResponse(
                    fromStopOrder = it.fromStopOrder,
                    toStopOrder = it.toStopOrder,
                    distanceKm = it.distanceKm,
                    durationMin = it.durationMin,
                )
            },
        )
    }

    private fun ensureProfile(routeId: UUID): TariffProfileRow {
        val existing = tariffProfileAdminRepository.findActiveByRouteId(routeId)
        if (existing != null) {
            return existing
        }
        val route = routeRepository.findById(routeId)
            ?: throw NoSuchElementException("Route $routeId was not found")
        tariffProfileAdminService.create(
            UpsertTariffProfileRequest(
                name = route.name,
                routeId = routeId,
                isActive = true,
            ),
        )
        return tariffProfileAdminRepository.findActiveByRouteId(routeId)
            ?: error("Failed to create tariff profile for route $routeId")
    }

    private fun buildRouteStops(pointIds: List<UUID>, legDurationsMin: List<Int>?): List<RouteStopRequest> =
        pointIds.mapIndexed { index, pointId ->
            val point = pointAdminRepository.findById(pointId)
                ?: throw DomainRuleViolation("Point $pointId was not found")
            val station = serviceStationRepository.findByPointId(pointId)
            val stopOrder = index + 1
            val duration = if (stopOrder > 1) {
                legDurationsMin?.getOrNull(index - 1)?.takeIf { it > 0 } ?: DEFAULT_LEG_DURATION_MIN
            } else {
                null
            }
            if (station != null) {
                RouteStopRequest(
                    stopOrder = stopOrder,
                    stopName = point.name,
                    stationId = station.id,
                    pointId = pointId,
                    isExternal = false,
                    scheduledDurationMin = duration,
                )
            } else {
                RouteStopRequest(
                    stopOrder = stopOrder,
                    stopName = point.name,
                    stationId = null,
                    pointId = pointId,
                    isExternal = true,
                    scheduledDurationMin = duration,
                )
            }
        }

    companion object {
        private const val DEFAULT_LEG_DURATION_MIN = 60

        private fun validateDateRange(validFrom: LocalDate?, validTo: LocalDate?) {
            if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
                throw DomainRuleViolation("validTo must be on or after validFrom")
            }
        }
    }
}
