package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.hardware.FiscalReceiptRequest
import ru.transora.app.hardware.HardwareAgentClient
import ru.transora.app.hardware.PrintTicketRequest
import ru.transora.app.inventory.ReservationRepository
import ru.transora.app.inventory.ReservationService
import ru.transora.app.inventory.SegmentOccupancyService
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.scheduling.TripRepository
import ru.transora.inventory.domain.Reservation
import ru.transora.inventory.domain.ReservationStatus
import ru.transora.sales.domain.CashierShift
import ru.transora.sales.domain.DocType
import ru.transora.sales.domain.Order
import ru.transora.sales.domain.OrderItem
import ru.transora.sales.domain.OrderStatus
import ru.transora.sales.domain.Payment
import ru.transora.sales.domain.PaymentStatus
import ru.transora.sales.domain.PaymentType
import ru.transora.sales.domain.Ticket
import ru.transora.sales.domain.TicketStatus
import ru.transora.scheduling.domain.Trip
import java.time.Clock
import java.util.UUID

data class SaleItem(
    val reservation: Reservation,
    val passengerName: String,
    val docType: DocType,
    val docNumber: String,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val quote: TariffQuote,
)

@Service
class TicketSaleOrchestrator(
    private val reservationRepository: ReservationRepository,
    private val reservationService: ReservationService,
    private val segmentOccupancyService: SegmentOccupancyService,
    private val shiftRepository: ShiftRepository,
    private val tripRepository: TripRepository,
    private val tariffCalculator: TariffCalculator,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val paymentRepository: PaymentRepository,
    private val ticketRepository: TicketRepository,
    private val ticketNumberGenerator: TicketNumberGenerator,
    private val outboxEventRepository: OutboxEventRepository,
    private val hardwareAgentClient: HardwareAgentClient,
    private val fiscalReceiptService: FiscalReceiptService,
) {
    @Transactional
    fun completeFromReservation(
        shiftId: UUID,
        reservationId: UUID,
        passengerName: String,
        docType: String,
        docNumber: String,
        paymentType: String,
    ): OrderResult {
        val shift = shiftRepository.findOpenByIdForUpdate(shiftId)
            ?: throw DomainRuleViolation("Open shift $shiftId was not found")

        val reservation = reservationRepository.findByIdForUpdate(reservationId)
            ?: throw NoSuchElementException("Reservation $reservationId was not found")

        if (reservation.status != ReservationStatus.ACTIVE) {
            throw DomainRuleViolation("Reservation $reservationId is not active")
        }

        val now = Clock.systemUTC().instant()
        if (reservation.expiresAt.isBefore(now)) {
            throw DomainRuleViolation("Reservation $reservationId is expired")
        }

        val trip = tripRepository.findById(reservation.tripId)
            ?: throw NoSuchElementException("Trip ${reservation.tripId} was not found")

        val fromStop = reservation.fromStopOrder ?: 1
        val toStop = reservation.toStopOrder ?: 2
        val quote = tariffCalculator.calculate(reservation.tripId, fromStop, toStop)

        val saleItem = SaleItem(
            reservation = reservation,
            passengerName = passengerName.trim(),
            docType = DocType.valueOf(docType.trim().uppercase()),
            docNumber = docNumber.trim(),
            fromStopOrder = fromStop,
            toStopOrder = toStop,
            quote = quote,
        )

        return completeSale(
            shift = shift,
            trip = trip,
            items = listOf(saleItem),
            paymentType = PaymentType.valueOf(paymentType.trim().uppercase()),
        )
    }

    @Transactional
    fun completeSale(
        shift: CashierShift,
        trip: Trip,
        items: List<SaleItem>,
        paymentType: PaymentType,
    ): OrderResult {
        if (items.isEmpty() || items.size > 10) {
            throw DomainRuleViolation("Order must contain 1 to 10 tickets (BR-SAL-004)")
        }

        val now = Clock.systemUTC().instant()
        val orderId = UUID.randomUUID()
        val totalCents = items.sumOf { it.quote.priceCents }

        orderRepository.insert(
            Order(
                id = orderId,
                shiftId = shift.id,
                status = OrderStatus.PENDING,
                totalCents = totalCents,
                createdAt = now,
                expiresAt = items.minOf { it.reservation.expiresAt },
            ),
        )

        val paymentId = UUID.randomUUID()
        paymentRepository.insert(
            Payment(
                id = paymentId,
                orderId = orderId,
                paymentType = paymentType,
                amountCents = totalCents,
                status = PaymentStatus.PENDING,
            ),
        )

        val transactionId = "MOCK-${UUID.randomUUID()}"
        paymentRepository.updateStatus(paymentId, PaymentStatus.COMPLETED, transactionId, now)

        val fiscalResult = try {
            hardwareAgentClient.printFiscalReceipt(
                FiscalReceiptRequest(
                    operationId = orderId,
                    amountCents = totalCents,
                    cashierName = shift.cashierName,
                    description = "Order $orderId (${items.size} tickets)",
                ),
            )
        } catch (ex: Exception) {
            paymentRepository.updateStatus(paymentId, PaymentStatus.FAILED, null, now)
            throw DomainRuleViolation("Fiscal receipt failed: ${ex.message}")
        }

        fiscalReceiptService.recordSale(
            shiftId = shift.id,
            orderId = orderId,
            amountCents = totalCents,
            result = fiscalResult,
        )

        val tickets = mutableListOf<Ticket>()
        items.forEach { item ->
            reservationService.confirm(item.reservation.id)

            orderItemRepository.insert(
                OrderItem(
                    id = UUID.randomUUID(),
                    orderId = orderId,
                    reservationId = item.reservation.id,
                    tripId = trip.id,
                    seatNumber = item.reservation.seatNumber,
                    passengerName = item.passengerName,
                    docType = item.docType,
                    docNumber = item.docNumber,
                    fromStopOrder = item.fromStopOrder,
                    toStopOrder = item.toStopOrder,
                    tariffId = item.quote.tariffId,
                    priceCents = item.quote.priceCents,
                ),
            )

            val ticket = Ticket(
                id = UUID.randomUUID(),
                ticketNumber = ticketNumberGenerator.generate(trip.id),
                reservationId = item.reservation.id,
                shiftId = shift.id,
                tripId = trip.id,
                seatNumber = item.reservation.seatNumber,
                passengerName = item.passengerName,
                priceCents = item.quote.priceCents,
                status = TicketStatus.ISSUED,
                issuedAt = now,
                orderId = orderId,
                docType = item.docType,
                docNumber = item.docNumber,
            )
            ticketRepository.insert(ticket)
            if (segmentOccupancyService.isSegmentMode(trip.id)) {
                reservationService.recordSeatSale(item.reservation, ticket.id)
            }
            tickets += ticket

            runCatching {
                hardwareAgentClient.printTicket(
                    PrintTicketRequest(
                        ticketId = ticket.id,
                        ticketNumber = ticket.ticketNumber,
                        passengerName = ticket.passengerName,
                        seatNumber = ticket.seatNumber,
                        routeNumber = trip.routeNumber,
                        departureTime = trip.expectedDepartureTime,
                    ),
                )
            }

            outboxEventRepository.append(
                aggregateType = "ticket",
                aggregateId = ticket.id.toString(),
                eventType = "sales.ticket.issued",
                payload = mapOf(
                    "ticketId" to ticket.id,
                    "ticketNumber" to ticket.ticketNumber,
                    "orderId" to orderId,
                    "reservationId" to ticket.reservationId,
                    "shiftId" to ticket.shiftId,
                    "tripId" to ticket.tripId,
                    "seatNumber" to ticket.seatNumber,
                    "passengerName" to ticket.passengerName,
                    "priceCents" to ticket.priceCents,
                    "issuedAt" to ticket.issuedAt,
                ),
            )
        }

        orderRepository.updateStatus(orderId, OrderStatus.PAID, now)
        outboxEventRepository.append(
            aggregateType = "order",
            aggregateId = orderId.toString(),
            eventType = "sales.order.completed",
            payload = mapOf(
                "orderId" to orderId,
                "shiftId" to shift.id,
                "totalCents" to totalCents,
                "ticketCount" to tickets.size,
            ),
        )

        return OrderResult(
            order = Order(
                id = orderId,
                shiftId = shift.id,
                status = OrderStatus.PAID,
                totalCents = totalCents,
                createdAt = now,
                expiresAt = items.minOf { it.reservation.expiresAt },
                paidAt = now,
            ),
            tickets = tickets,
            paymentTransactionId = transactionId,
        )
    }
}
