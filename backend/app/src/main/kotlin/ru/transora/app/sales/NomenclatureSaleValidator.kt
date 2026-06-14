package ru.transora.app.sales

import org.springframework.stereotype.Component
import ru.transora.app.admin.NomenclatureRow
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.sales.domain.NomenclatureSaleMode
import java.util.UUID

data class NomenclatureAddonRequest(
    val nomenclatureItemId: UUID,
    val quantity: Int,
)

@Component
class NomenclatureSaleValidator {
    fun validateStandalone(item: NomenclatureRow, quantity: Int) {
        requireActive(item)
        if (NomenclatureSaleMode.valueOf(item.saleMode) != NomenclatureSaleMode.STANDALONE) {
            throw DomainRuleViolation("Item ${item.code} cannot be sold standalone")
        }
        requirePositiveQty(quantity)
    }

    fun validateTicketAddon(
        item: NomenclatureRow,
        quantity: Int,
        existingQtyOnTicket: Int,
    ) {
        requireActive(item)
        if (NomenclatureSaleMode.valueOf(item.saleMode) != NomenclatureSaleMode.TICKET_ATTACHED) {
            throw DomainRuleViolation("Item ${item.code} cannot be attached to a ticket")
        }
        requirePositiveQty(quantity)
        val total = existingQtyOnTicket + quantity
        if (total > item.maxQtyPerTicket) {
            throw DomainRuleViolation(
                "Item ${item.code} allows at most ${item.maxQtyPerTicket} per ticket, requested $total",
            )
        }
    }

    private fun requireActive(item: NomenclatureRow) {
        if (!item.isActive) {
            throw DomainRuleViolation("Nomenclature item ${item.code} is not active")
        }
    }

    private fun requirePositiveQty(quantity: Int) {
        if (quantity < 1) {
            throw DomainRuleViolation("Quantity must be at least 1")
        }
    }
}
