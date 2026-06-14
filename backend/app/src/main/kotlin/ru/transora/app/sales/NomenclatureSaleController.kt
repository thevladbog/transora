package ru.transora.app.sales

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import ru.transora.sales.domain.RefundPreview
import ru.transora.sales.domain.RefundType
import java.util.UUID

@RestController
@RequestMapping("/api/nomenclature")
@Tag(name = "Nomenclature Sales", description = "Nomenclature catalog, quotes and refunds")
class NomenclatureSaleController(
    private val nomenclatureSaleService: NomenclatureSaleService,
    private val nomenclatureRefundService: NomenclatureRefundService,
) {
    @GetMapping("/catalog")
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "List sellable nomenclature for a trip segment")
    fun catalog(
        @RequestParam tripId: UUID,
        @RequestParam @Min(1) fromStop: Int,
        @RequestParam @Min(1) toStop: Int,
    ): NomenclatureCatalogResponse =
        nomenclatureSaleService.catalog(tripId, fromStop, toStop)

    @PostMapping("/quote")
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "Quote nomenclature prices for a trip segment")
    fun quote(@Valid @RequestBody request: NomenclatureQuoteRequest): NomenclatureQuoteResponse =
        nomenclatureSaleService.quote(request)

    @GetMapping("/lines/{lineId}/refund-preview")
    @RequirePermission(Permissions.TICKETS_REFUND)
    @Operation(summary = "Preview refund for a sold nomenclature line")
    fun refundPreview(
        @PathVariable lineId: UUID,
        @RequestParam(required = false) @Min(1) quantity: Int?,
    ): RefundPreview = nomenclatureRefundService.preview(lineId, quantity)

    @PostMapping("/lines/{lineId}/refund")
    @RequirePermission(Permissions.TICKETS_REFUND)
    @Operation(summary = "Refund a sold nomenclature line")
    fun refund(
        @PathVariable lineId: UUID,
        @Valid @RequestBody request: NomenclatureRefundRequestBody,
    ): NomenclatureRefundResult =
        nomenclatureRefundService.refund(
            lineId = lineId,
            quantity = request.quantity,
            refundType = RefundType.valueOf(request.refundType.trim().uppercase()),
        )
}

data class NomenclatureRefundRequestBody(
    @field:Min(1) val quantity: Int? = null,
    val refundType: String = "CASH",
)
