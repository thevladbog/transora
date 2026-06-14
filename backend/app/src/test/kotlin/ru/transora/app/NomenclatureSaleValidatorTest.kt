package ru.transora.app.sales

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.transora.app.admin.NomenclatureRow
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.sales.domain.NomenclatureSaleMode
import java.time.Instant
import java.util.UUID

class NomenclatureSaleValidatorTest {
    private val validator = NomenclatureSaleValidator()

    @Test
    fun `standalone item cannot attach to ticket`() {
        val item = item(saleMode = NomenclatureSaleMode.STANDALONE.name)
        assertThrows<DomainRuleViolation> {
            validator.validateTicketAddon(item, quantity = 1, existingQtyOnTicket = 0)
        }
    }

    @Test
    fun `ticket attached item enforces max qty`() {
        val item = item(saleMode = NomenclatureSaleMode.TICKET_ATTACHED.name, maxQtyPerTicket = 1)
        assertThrows<DomainRuleViolation> {
            validator.validateTicketAddon(item, quantity = 1, existingQtyOnTicket = 1)
        }
    }

    private fun item(saleMode: String, maxQtyPerTicket: Int = 1): NomenclatureRow =
        NomenclatureRow(
            id = UUID.randomUUID(),
            code = "BAG",
            name = "Baggage",
            category = "BAGGAGE",
            priceCents = 10000,
            refundPolicyId = null,
            refundPolicyName = null,
            isActive = true,
            description = null,
            createdAt = Instant.now(),
            saleMode = saleMode,
            maxQtyPerTicket = maxQtyPerTicket,
            printName = "Baggage",
        )
}
