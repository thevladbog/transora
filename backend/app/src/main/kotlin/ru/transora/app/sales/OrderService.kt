package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.inventory.CreateReservationRequest
import ru.transora.app.inventory.ReservationService
import ru.transora.app.scheduling.TripRepository
import ru.transora.sales.domain.DocType
import ru.transora.sales.domain.Order
import ru.transora.sales.domain.PaymentType
import ru.transora.sales.domain.Ticket
import java.util.UUID

@Service
class OrderService(
    private val reservationService: ReservationService,
    private val shiftRepository: ShiftRepository,
    private val tripRepository: TripRepository,
    private val tariffCalculator: TariffCalculator,
    private val ticketSaleOrchestrator: TicketSaleOrchestrator,
    private val nomenclatureSaleService: NomenclatureSaleService,
    private val commercePolicyResolver: CommercePolicyResolver,
) {
    @Transactional
    fun createOrder(request: CreateOrderRequest): OrderResult {
        val shiftId = requireNotNull(request.shiftId)
        val shift = shiftRepository.findOpenByIdForUpdate(shiftId)
            ?: throw DomainRuleViolation("Open shift $shiftId was not found")

        val tripId = requireNotNull(request.tripId)
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")

        val items = request.resolvedItems()
        if (items.isEmpty() || items.size > 10) {
            throw DomainRuleViolation("Order must contain 1 to 10 tickets (BR-SAL-004)")
        }

        val firstFromStop = items.first().fromStopOrder ?: 1
        val firstToStop = items.first().toStopOrder ?: 2
        val segmentQuote = tariffCalculator.calculate(tripId, firstFromStop, firstToStop)
        val applicable = commercePolicyResolver.resolveApplicableSalePolicies(tripId, segmentQuote.priceCents)
        val mandatoryIds = applicable.mandatory.mapNotNull { it.nomenclatureItemId }.toSet()
        val optionalIds = applicable.optional.mapNotNull { it.nomenclatureItemId }.toSet()
        val mergedStandalone = mergeStandaloneNomenclature(
            request.standaloneNomenclature,
            applicable.mandatory,
        )
        validateOptionalPolicyNomenclature(mergedStandalone, mandatoryIds, optionalIds, tripId)

        val standalone = nomenclatureSaleService.prepareStandalone(
            tripId = tripId,
            fromStopOrder = firstFromStop,
            toStopOrder = firstToStop,
            requests = mergedStandalone,
        )

        val saleItems = items.map { item ->
            val fromStop = item.fromStopOrder ?: 1
            val toStop = item.toStopOrder ?: 2
            val quote = tariffCalculator.calculate(tripId, fromStop, toStop)
            val reservation = reservationService.reserve(
                CreateReservationRequest(
                    tripId = tripId,
                    seatNumber = item.seatNumber,
                    fromStopOrder = fromStop,
                    toStopOrder = toStop,
                ),
            )
            val addons = nomenclatureSaleService.prepareTicketAddons(
                routePriceCents = quote.priceCents,
                requests = item.nomenclatureAddons,
            )
            SaleItem(
                reservation = reservation,
                passengerName = item.passengerName.trim(),
                docType = DocType.valueOf(item.docType.trim().uppercase()),
                docNumber = item.docNumber.trim(),
                fromStopOrder = fromStop,
                toStopOrder = toStop,
                quote = quote,
                nomenclatureAddons = addons,
            )
        }

        return ticketSaleOrchestrator.completeSale(
            shift = shift,
            trip = trip,
            items = saleItems,
            paymentType = PaymentType.valueOf(request.paymentType.trim().uppercase()),
            nomenclatureContext = SaleNomenclatureContext(standalone = standalone),
        )
    }

    private fun mergeStandaloneNomenclature(
        requested: List<NomenclatureAddonRequest>,
        mandatory: List<ResolvedPolicy>,
    ): List<NomenclatureAddonRequest> {
        val byId = linkedMapOf<UUID, Int>()
        requested.forEach { req ->
            byId[req.nomenclatureItemId] = byId.getOrDefault(req.nomenclatureItemId, 0) + req.quantity
        }
        mandatory.forEach { policy ->
            val itemId = policy.nomenclatureItemId ?: return@forEach
            if (!byId.containsKey(itemId)) {
                byId[itemId] = 1
            }
        }
        return byId.map { (id, qty) -> NomenclatureAddonRequest(id, qty) }
    }

    private fun validateOptionalPolicyNomenclature(
        requests: List<NomenclatureAddonRequest>,
        mandatoryIds: Set<UUID>,
        optionalIds: Set<UUID>,
        tripId: UUID,
    ) {
        requests.forEach { req ->
            if (req.nomenclatureItemId in mandatoryIds) return@forEach
            if (req.nomenclatureItemId in optionalIds) return@forEach
            if (commercePolicyResolver.isRouteSalePolicyNomenclature(tripId, req.nomenclatureItemId)) {
                throw DomainRuleViolation(
                    "Nomenclature ${req.nomenclatureItemId} is not applicable for this trip segment",
                )
            }
        }
    }
}

data class OrderItemRequest(
    val seatNumber: Int,
    val passengerName: String,
    val docType: String,
    val docNumber: String,
    val fromStopOrder: Int? = null,
    val toStopOrder: Int? = null,
    val nomenclatureAddons: List<NomenclatureAddonRequest> = emptyList(),
)

data class CreateOrderRequest(
    val shiftId: UUID?,
    val tripId: UUID?,
    val items: List<OrderItemRequest> = emptyList(),
    val seatNumber: Int? = null,
    val passengerName: String? = null,
    val docType: String? = null,
    val docNumber: String? = null,
    val fromStopOrder: Int? = null,
    val toStopOrder: Int? = null,
    val paymentType: String = "CASH",
    val standaloneNomenclature: List<NomenclatureAddonRequest> = emptyList(),
) {
    fun resolvedItems(): List<OrderItemRequest> =
        if (items.isNotEmpty()) {
            items
        } else {
            listOf(
                OrderItemRequest(
                    seatNumber = requireNotNull(seatNumber),
                    passengerName = requireNotNull(passengerName),
                    docType = requireNotNull(docType),
                    docNumber = requireNotNull(docNumber),
                    fromStopOrder = fromStopOrder,
                    toStopOrder = toStopOrder,
                ),
            )
        }
}

data class OrderResult(
    val order: Order,
    val tickets: List<Ticket>,
    val paymentTransactionId: String,
) {
    val ticket: Ticket get() = tickets.first()
}
