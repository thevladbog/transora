package ru.transora.app.outbox.handlers

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.transora.app.admin.AuditLogService
import ru.transora.app.documents.TripDocumentService
import ru.transora.app.inventory.TripInventoryRepository
import ru.transora.app.inventory.TripInventoryService
import ru.transora.app.notifications.AnnouncementAutoEnqueueService
import ru.transora.app.notifications.BoardRefreshCoordinator
import ru.transora.app.hardware.FiscalShiftOpenRequest
import ru.transora.app.hardware.HardwareAgentClient
import ru.transora.app.hardware.ShiftZReportRequest
import ru.transora.app.scheduling.TripGenerationService
import ru.transora.app.sales.FiscalReceiptRepository
import ru.transora.app.sales.FiscalReceiptService
import ru.transora.app.sales.ShiftRepository
import ru.transora.app.sales.ShiftSummaryRepository
import ru.transora.app.sales.TicketRepository
import ru.transora.sales.domain.FiscalReceiptType
import ru.transora.app.stationagent.StationAgentEventPublisher
import ru.transora.app.stationagent.TicketStatusPayload
import ru.transora.inventory.domain.TripInventoryStatus
import ru.transora.app.outbox.OutboxEvent
import ru.transora.app.outbox.OutboxEventHandler
import ru.transora.app.outbox.OutboxPayloadReader
import java.time.Clock

@Component
class SchedulingTripDelayUpdatedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
    private val announcementAutoEnqueueService: AnnouncementAutoEnqueueService,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "scheduling.trip.delay_updated"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        log.info("Trip delay updated: tripId={} delay={}", tripId, payload["delayMinutes"])
        val delayMinutes = (payload["delayMinutes"] as? Number)?.toInt()
        announcementAutoEnqueueService.onTripDelayUpdated(
            tripId = tripId,
            delayMinutes = delayMinutes,
            stationCode = payload["departureStationCode"]?.toString(),
        )
        boardRefreshCoordinator.refreshForTrip(
            tripId,
            payload["departureStationCode"]?.toString(),
            "trip.delay_updated",
        )
    }
}

@Component
class SchedulingTripStatusChangedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
    private val tripInventoryRepository: TripInventoryRepository,
    private val announcementAutoEnqueueService: AnnouncementAutoEnqueueService,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "scheduling.trip.status_changed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        log.info(
            "Trip status changed: tripId={} {} -> {}",
            payload["tripId"],
            payload["previousStatus"],
            payload["status"],
        )
        val status = payload["status"]?.toString()
        val tripId = payloadReader.uuidValue(payload, "tripId")
        if (status == "COMPLETED" || status == "CANCELLED") {
            tripInventoryRepository.updateStatus(tripId, TripInventoryStatus.FROZEN)
        }
        if (status == "CANCELLED") {
            announcementAutoEnqueueService.onTripCancelled(
                tripId = tripId,
                stationCode = payload["departureStationCode"]?.toString(),
            )
        }
        boardRefreshCoordinator.refreshForTrip(tripId, payload["departureStationCode"]?.toString())
    }
}

@Component
class SchedulingTripDepartedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
    private val announcementAutoEnqueueService: AnnouncementAutoEnqueueService,
) : OutboxEventHandler {
    override val eventType: String = "scheduling.trip.departed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        announcementAutoEnqueueService.onTripDeparted(tripId, null)
        boardRefreshCoordinator.refreshForTrip(tripId, null)
    }
}

@Component
class SchedulingTripArrivedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
) : OutboxEventHandler {
    override val eventType: String = "scheduling.trip.arrived"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        boardRefreshCoordinator.refreshForTrip(tripId, null)
    }
}

@Component
class SchedulingTripStopArrivedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "scheduling.trip.stop_arrived"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        log.info(
            "Intermediate stop arrived: tripId={} stopOrder={} stationId={}",
            tripId,
            payload["stopOrder"],
            payload["stationId"],
        )
        boardRefreshCoordinator.refreshForTrip(tripId, null)
    }
}

@Component
class InventoryTransitGateOpenedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val announcementAutoEnqueueService: AnnouncementAutoEnqueueService,
) : OutboxEventHandler {
    override val eventType: String = "inventory.transit_gate.opened"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val gateId = payloadReader.uuidValue(payload, "gateId")
        val tripId = payloadReader.uuidValue(payload, "tripId")
        val stationId = payloadReader.uuidValue(payload, "stationId")
        @Suppress("UNCHECKED_CAST")
        val availableSeats = (payload["availableSeats"] as? List<Number>)?.map { it.toInt() } ?: emptyList()
        announcementAutoEnqueueService.onTransitGateOpened(tripId, gateId, stationId, availableSeats)
    }
}

@Component
class InventoryTransitGateClosedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val announcementAutoEnqueueService: AnnouncementAutoEnqueueService,
) : OutboxEventHandler {
    override val eventType: String = "inventory.transit_gate.closed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val gateId = payloadReader.uuidValue(payload, "gateId")
        val tripId = payloadReader.uuidValue(payload, "tripId")
        val stationId = payloadReader.uuidValue(payload, "stationId")
        announcementAutoEnqueueService.onTransitGateClosed(tripId, gateId, stationId)
    }
}

@Component
class SchedulingTripCancelledHandler(
    private val payloadReader: OutboxPayloadReader,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
    private val announcementAutoEnqueueService: AnnouncementAutoEnqueueService,
) : OutboxEventHandler {
    override val eventType: String = "scheduling.trip.cancelled"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        announcementAutoEnqueueService.onTripCancelled(
            tripId = tripId,
            stationCode = payload["departureStationCode"]?.toString(),
        )
        boardRefreshCoordinator.refreshForTrip(tripId, payload["departureStationCode"]?.toString())
    }
}

@Component
class BoardingStartedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val tripDocumentService: TripDocumentService,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "boarding.started"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        tripDocumentService.generateManifestAndBoardingSheet(tripId)
        boardRefreshCoordinator.refreshForTrip(tripId, payload["departureStationCode"]?.toString())
    }
}

@Component
class TripCompletedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val tripDocumentService: TripDocumentService,
) : OutboxEventHandler {
    override val eventType: String = "trip.completed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        tripDocumentService.generateCarrierReport(tripId)
    }
}

@Component
class LegacyTicketIssuedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val ticketDocumentService: ru.transora.app.documents.TicketDocumentService,
) : OutboxEventHandler {
    override val eventType: String = "ticket.issued"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val ticketId = payloadReader.uuidValue(payload, "ticketId")
        ticketDocumentService.generateForTicket(ticketId)
    }
}

@Component
class SalesTicketIssuedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val ticketDocumentService: ru.transora.app.documents.TicketDocumentService,
) : OutboxEventHandler {
    override val eventType: String = "sales.ticket.issued"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val ticketId = payloadReader.uuidValue(payload, "ticketId")
        ticketDocumentService.generateForTicket(ticketId)
    }
}

@Component
class TicketUsedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val ticketRepository: TicketRepository,
    private val stationAgentEventPublisher: StationAgentEventPublisher,
) : OutboxEventHandler {
    override val eventType: String = "ticket.used"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val ticketId = payloadReader.uuidValue(payload, "ticketId")
        val tripId = payloadReader.uuidValue(payload, "tripId")
        val stationId = payloadReader.uuidValue(payload, "stationId")
        val ticket = ticketRepository.findById(ticketId) ?: return
        stationAgentEventPublisher.notifyTicketUsed(
            stationId,
            TicketStatusPayload(
                ticketId = ticket.id.toString(),
                ticketNumber = ticket.ticketNumber,
                tripId = tripId.toString(),
                status = ticket.status.name,
                passengerName = ticket.passengerName,
                seatNumber = ticket.seatNumber,
                stationId = stationId.toString(),
                scannedAt = Clock.systemUTC().instant().toString(),
            ),
        )
    }
}

@Component
class SalesTicketRefundedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val ticketDocumentService: ru.transora.app.documents.TicketDocumentService,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "sales.ticket.refunded"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val ticketId = payloadReader.uuidValue(payload, "ticketId")
        log.info(
            "Ticket refunded: ticketId={} refundCents={}",
            ticketId,
            payload["refundCents"],
        )
        runCatching { ticketDocumentService.generateVoidedTicket(ticketId) }
            .onFailure { ex -> log.warn("Void ticket document skipped for {}: {}", ticketId, ex.message) }
    }
}

@Component
class SchedulingTripCreatedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val tripInventoryService: TripInventoryService,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
    private val transitGateProvisioner: ru.transora.app.inventory.TransitGateProvisioner,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "scheduling.trip.created"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        val seatCount = (payload["seatCount"] as Number).toInt()
        tripInventoryService.initializeForTrip(tripId, seatCount)
        transitGateProvisioner.provisionForTrip(tripId)
        val stationCode = payload["departureStationCode"]?.toString()
        boardRefreshCoordinator.refreshForTrip(tripId, stationCode, "trip.created")
        log.info("Initialized inventory for trip {} with {} seats", tripId, seatCount)
    }
}

@Component
class ShiftClosedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val shiftSummaryRepository: ShiftSummaryRepository,
    private val shiftRepository: ShiftRepository,
    private val fiscalReceiptRepository: FiscalReceiptRepository,
    private val fiscalReceiptService: FiscalReceiptService,
    private val hardwareAgentClient: HardwareAgentClient,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "shift.closed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val shiftId = payloadReader.uuidValue(payload, "shiftId")
        if (fiscalReceiptRepository.findByShiftAndType(shiftId, FiscalReceiptType.Z_REPORT) != null) {
            log.debug("Z-report already recorded for shift {}", shiftId)
            return
        }

        val shift = shiftRepository.findById(shiftId)
            ?: throw NoSuchElementException("Shift $shiftId was not found")
        val summary = shiftSummaryRepository.aggregate(shiftId)
        val result = hardwareAgentClient.printZReport(
            ShiftZReportRequest(
                shiftId = shiftId,
                cashierName = shift.cashierName,
                ticketsSold = summary.ticketsSold,
                ticketsRefunded = summary.ticketsRefunded,
                cashSalesCents = summary.cashSalesCents,
                cardSalesCents = summary.cardSalesCents,
                refundsCents = summary.refundsCents,
            ),
        )
        fiscalReceiptService.recordZReport(shiftId, summary, result)
        log.info(
            "Z-report recorded for shift {}: sold={} refunded={} netCents={}",
            shiftId,
            summary.ticketsSold,
            summary.ticketsRefunded,
            summary.netSalesCents(),
        )
    }
}

@Component
class SchedulingTripVehicleChangedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val tripInventoryService: TripInventoryService,
    private val boardRefreshCoordinator: BoardRefreshCoordinator,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "scheduling.trip.vehicle_changed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        val seatCount = (payload["seatCount"] as Number).toInt()
        val result = tripInventoryService.reaccommodateForTrip(tripId, seatCount)
        if (result.reaccommodationSeatNumbers.isEmpty()) {
            log.info("Reaccommodated inventory for trip {} to {} seats", tripId, seatCount)
        } else {
            log.info(
                "Reaccommodated inventory for trip {} to {} seats; {} seat(s) require reassignment: {}",
                tripId,
                seatCount,
                result.reaccommodationSeatNumbers.size,
                result.reaccommodationSeatNumbers,
            )
        }
        boardRefreshCoordinator.refreshForTrip(tripId, null)
    }
}

@Component
class ShiftOpenedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val shiftRepository: ShiftRepository,
    private val hardwareAgentClient: HardwareAgentClient,
    private val auditLogService: AuditLogService,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "shift.opened"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val shiftId = payloadReader.uuidValue(payload, "shiftId")
        if (shiftRepository.findFiscalShiftNo(shiftId) != null) {
            log.debug("Fiscal shift already opened for shift {}", shiftId)
            return
        }

        val result = hardwareAgentClient.openFiscalShift(
            FiscalShiftOpenRequest(
                shiftId = shiftId,
                cashierName = payload["cashierName"].toString(),
                posId = payload["posId"].toString(),
            ),
        )
        shiftRepository.updateFiscalShiftNo(shiftId, result.fiscalShiftNo)
        auditLogService.record(
            module = "sales",
            action = "shift_opened",
            entityType = "shift",
            entityId = shiftId.toString(),
            detailsJson = """{"fiscalShiftNo":${result.fiscalShiftNo}}""",
        )
        log.info("Fiscal shift opened for shift {}: fiscalShiftNo={}", shiftId, result.fiscalShiftNo)
    }
}

@Component
class SalesOrderCompletedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val auditLogService: AuditLogService,
) : OutboxEventHandler {
    override val eventType: String = "sales.order.completed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val orderId = payloadReader.uuidValue(payload, "orderId")
        val shiftId = payloadReader.uuidValue(payload, "shiftId")
        val totalCents = (payload["totalCents"] as Number).toLong()
        val ticketCount = (payload["ticketCount"] as Number).toInt()
        auditLogService.record(
            module = "sales",
            action = "order_completed",
            entityType = "order",
            entityId = orderId.toString(),
            detailsJson = """{"shiftId":"$shiftId","totalCents":$totalCents,"ticketCount":$ticketCount}""",
        )
    }
}

@Component
class SchedulingScheduleUpdatedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val tripGenerationService: TripGenerationService,
) : OutboxEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = "scheduling.schedule.updated"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val routeId = payloadReader.uuidValue(payload, "routeId")
        val result = tripGenerationService.generate(routeId = routeId)
        log.info(
            "Schedule updated for route {}: created={}, skipped={}, cancelled={}",
            routeId,
            result.createdCount,
            result.skippedCount,
            result.cancelledCount,
        )
    }
}

@Component
class ReservationCreatedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val sideEffects: InventoryOutboxSideEffects,
) : OutboxEventHandler {
    override val eventType: String = "reservation.created"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val reservationId = payloadReader.uuidValue(payload, "reservationId")
        val tripId = payloadReader.uuidValue(payload, "tripId")
        sideEffects.audit(
            action = "reservation_created",
            entityType = "reservation",
            entityId = reservationId.toString(),
            detailsJson = payloadToJson(payload),
        )
        sideEffects.refreshBoard(tripId)
    }
}

@Component
class ReservationConfirmedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val sideEffects: InventoryOutboxSideEffects,
) : OutboxEventHandler {
    override val eventType: String = "reservation.confirmed"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val reservationId = payloadReader.uuidValue(payload, "reservationId")
        sideEffects.audit(
            action = "reservation_confirmed",
            entityType = "reservation",
            entityId = reservationId.toString(),
            detailsJson = payloadToJson(payload),
        )
    }
}

@Component
class ReservationReleasedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val sideEffects: InventoryOutboxSideEffects,
) : OutboxEventHandler {
    override val eventType: String = "reservation.released"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val reservationId = payloadReader.uuidValue(payload, "reservationId")
        val tripId = payloadReader.uuidValue(payload, "tripId")
        sideEffects.audit(
            action = "reservation_released",
            entityType = "reservation",
            entityId = reservationId.toString(),
            detailsJson = payloadToJson(payload),
        )
        sideEffects.refreshBoard(tripId)
    }
}

@Component
class ReservationExpiredHandler(
    private val payloadReader: OutboxPayloadReader,
    private val sideEffects: InventoryOutboxSideEffects,
) : OutboxEventHandler {
    override val eventType: String = "reservation.expired"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val reservationId = payloadReader.uuidValue(payload, "reservationId")
        val tripId = payloadReader.uuidValue(payload, "tripId")
        sideEffects.audit(
            action = "reservation_expired",
            entityType = "reservation",
            entityId = reservationId.toString(),
            detailsJson = payloadToJson(payload),
        )
        sideEffects.refreshBoard(tripId)
    }
}

@Component
class InventorySeatReleasedHandler(
    private val payloadReader: OutboxPayloadReader,
    private val sideEffects: InventoryOutboxSideEffects,
) : OutboxEventHandler {
    override val eventType: String = "inventory.seat.released"

    override fun handle(event: OutboxEvent) {
        val payload = payloadReader.readPayload(event)
        val tripId = payloadReader.uuidValue(payload, "tripId")
        val seatNumber = (payload["seatNumber"] as Number).toInt()
        sideEffects.audit(
            action = "seat_released",
            entityType = "seat",
            entityId = "$tripId:$seatNumber",
            detailsJson = payloadToJson(payload),
        )
        sideEffects.refreshBoard(tripId)
    }
}

private fun payloadToJson(payload: Map<String, Any?>): String {
    val entries = payload.entries.joinToString(",") { (key, value) ->
        val jsonValue = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> "\"${value.toString().replace("\"", "\\\"")}\""
        }
        """"$key":$jsonValue"""
    }
    return "{$entries}"
}
