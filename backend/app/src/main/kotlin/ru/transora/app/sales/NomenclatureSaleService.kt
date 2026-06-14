package ru.transora.app.sales

import org.springframework.stereotype.Service
import ru.transora.app.admin.NomenclatureAdminRepository
import ru.transora.app.admin.NomenclatureRow
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.sales.domain.NomenclatureSaleMode
import java.util.UUID

data class NomenclatureCatalogItem(
    val id: String,
    val code: String,
    val name: String,
    val category: String,
    val saleMode: String,
    val pricingMode: String,
    val unitPriceCents: Long,
    val maxQtyPerTicket: Int,
)

data class NomenclatureCatalogResponse(
    val standalone: List<NomenclatureCatalogItem>,
    val ticketAttached: List<NomenclatureCatalogItem>,
)

data class NomenclatureQuoteItemRequest(
    val nomenclatureItemId: UUID,
    val quantity: Int,
    val saleMode: String,
)

data class NomenclatureQuoteRequest(
    val tripId: UUID,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val items: List<NomenclatureQuoteItemRequest>? = null,
)

data class NomenclatureQuoteLine(
    val nomenclatureItemId: String,
    val code: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val totalPriceCents: Long,
)

data class NomenclatureQuoteResponse(
    val lines: List<NomenclatureQuoteLine>,
    val totalCents: Long,
)

data class PreparedNomenclatureLine(
    val item: NomenclatureRow,
    val quantity: Int,
    val unitPriceCents: Long,
)

@Service
class NomenclatureSaleService(
    private val nomenclatureAdminRepository: NomenclatureAdminRepository,
    private val tariffCalculator: TariffCalculator,
    private val priceCalculator: NomenclaturePriceCalculator,
    private val saleValidator: NomenclatureSaleValidator,
) {
    fun catalog(tripId: UUID, fromStopOrder: Int, toStopOrder: Int): NomenclatureCatalogResponse {
        val routePrice = tariffCalculator.calculate(tripId, fromStopOrder, toStopOrder).priceCents
        val standalone = mutableListOf<NomenclatureCatalogItem>()
        val attached = mutableListOf<NomenclatureCatalogItem>()
        nomenclatureAdminRepository.listActive().forEach { item ->
            val catalogItem = item.toCatalogItem(routePrice)
            when (NomenclatureSaleMode.valueOf(item.saleMode)) {
                NomenclatureSaleMode.STANDALONE -> standalone += catalogItem
                NomenclatureSaleMode.TICKET_ATTACHED -> attached += catalogItem
            }
        }
        return NomenclatureCatalogResponse(standalone = standalone, ticketAttached = attached)
    }

    fun quote(request: NomenclatureQuoteRequest): NomenclatureQuoteResponse {
        val routePrice = tariffCalculator.calculate(
            request.tripId,
            request.fromStopOrder,
            request.toStopOrder,
        ).priceCents
        val lines = (request.items ?: emptyList()).map { line ->
            val item = nomenclatureAdminRepository.findById(line.nomenclatureItemId)
                ?: throw NoSuchElementException("Nomenclature item ${line.nomenclatureItemId} was not found")
            when (NomenclatureSaleMode.valueOf(line.saleMode.trim().uppercase())) {
                NomenclatureSaleMode.STANDALONE -> saleValidator.validateStandalone(item, line.quantity)
                NomenclatureSaleMode.TICKET_ATTACHED -> saleValidator.validateTicketAddon(item, line.quantity, 0)
            }
            if (item.saleMode != line.saleMode.trim().uppercase()) {
                throw DomainRuleViolation("Sale mode mismatch for item ${item.code}")
            }
            val unitPrice = priceCalculator.unitPriceCents(item, routePrice)
            NomenclatureQuoteLine(
                nomenclatureItemId = item.id.toString(),
                code = item.code,
                name = item.name,
                quantity = line.quantity,
                unitPriceCents = unitPrice,
                totalPriceCents = unitPrice * line.quantity,
            )
        }
        return NomenclatureQuoteResponse(lines = lines, totalCents = lines.sumOf { it.totalPriceCents })
    }

    fun prepareStandalone(
        tripId: UUID,
        fromStopOrder: Int,
        toStopOrder: Int,
        requests: List<NomenclatureAddonRequest>,
    ): List<PreparedNomenclatureLine> {
        if (requests.isEmpty()) return emptyList()
        val routePrice = tariffCalculator.calculate(tripId, fromStopOrder, toStopOrder).priceCents
        return requests.map { req ->
            val item = nomenclatureAdminRepository.findById(req.nomenclatureItemId)
                ?: throw NoSuchElementException("Nomenclature item ${req.nomenclatureItemId} was not found")
            saleValidator.validateStandalone(item, req.quantity)
            PreparedNomenclatureLine(
                item = item,
                quantity = req.quantity,
                unitPriceCents = priceCalculator.unitPriceCents(item, routePrice),
            )
        }
    }

    fun prepareTicketAddons(
        routePriceCents: Long,
        requests: List<NomenclatureAddonRequest>,
    ): List<PreparedNomenclatureLine> {
        if (requests.isEmpty()) return emptyList()
        val qtyByItem = mutableMapOf<UUID, Int>()
        return requests.map { req ->
            val item = nomenclatureAdminRepository.findById(req.nomenclatureItemId)
                ?: throw NoSuchElementException("Nomenclature item ${req.nomenclatureItemId} was not found")
            val existing = qtyByItem.getOrDefault(req.nomenclatureItemId, 0)
            saleValidator.validateTicketAddon(item, req.quantity, existing)
            qtyByItem[req.nomenclatureItemId] = existing + req.quantity
            PreparedNomenclatureLine(
                item = item,
                quantity = req.quantity,
                unitPriceCents = priceCalculator.unitPriceCents(item, routePriceCents),
            )
        }
    }

    private fun NomenclatureRow.toCatalogItem(routePriceCents: Long): NomenclatureCatalogItem =
        NomenclatureCatalogItem(
            id = id.toString(),
            code = code,
            name = name,
            category = category,
            saleMode = saleMode,
            pricingMode = pricingMode,
            unitPriceCents = priceCalculator.unitPriceCents(this, routePriceCents),
            maxQtyPerTicket = maxQtyPerTicket,
        )
}
