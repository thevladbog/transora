package ru.transora.app.sales

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Ticket orders and payments")
class OrderController(
    private val orderService: OrderService,
) {
    @PostMapping
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "Create order, process mock payment, confirm reservation and issue ticket")
    fun create(@Valid @RequestBody request: CreateOrderRequestBody): OrderResponse {
        val result = orderService.createOrder(
            CreateOrderRequest(
                shiftId = request.shiftId,
                tripId = request.tripId,
                items = (request.items ?: emptyList()).map {
                    OrderItemRequest(
                        seatNumber = it.seatNumber,
                        passengerName = it.passengerName,
                        docType = it.docType,
                        docNumber = it.docNumber,
                        fromStopOrder = it.fromStopOrder,
                        toStopOrder = it.toStopOrder,
                        nomenclatureAddons = (it.nomenclatureAddons ?: emptyList()).map { addon ->
                            NomenclatureAddonRequest(addon.nomenclatureItemId, addon.quantity)
                        },
                    )
                }.ifEmpty {
                    listOf(
                        OrderItemRequest(
                            seatNumber = request.seatNumber,
                            passengerName = request.passengerName,
                            docType = request.docType,
                            docNumber = request.docNumber,
                            fromStopOrder = request.fromStopOrder,
                            toStopOrder = request.toStopOrder,
                            nomenclatureAddons = (request.nomenclatureAddons ?: emptyList()).map { addon ->
                                NomenclatureAddonRequest(addon.nomenclatureItemId, addon.quantity)
                            },
                        ),
                    )
                },
                seatNumber = request.seatNumber,
                passengerName = request.passengerName,
                docType = request.docType,
                docNumber = request.docNumber,
                fromStopOrder = request.fromStopOrder,
                toStopOrder = request.toStopOrder,
                paymentType = request.paymentType,
                standaloneNomenclature = (request.standaloneNomenclature ?: emptyList()).map {
                    NomenclatureAddonRequest(it.nomenclatureItemId, it.quantity)
                },
            ),
        )
        return OrderResponse(
            orderId = result.order.id.toString(),
            status = result.order.status.name,
            totalCents = result.order.totalCents,
            paidAt = result.order.paidAt,
            ticketId = result.ticket.id.toString(),
            ticketNumber = result.ticket.ticketNumber,
            ticketIds = result.tickets.map { it.id.toString() },
            paymentTransactionId = result.paymentTransactionId,
        )
    }
}

data class CreateOrderRequestBody(
    @field:NotNull val shiftId: UUID?,
    @field:NotNull val tripId: UUID?,
    @field:Min(1) val seatNumber: Int,
    @field:NotBlank val passengerName: String,
    @field:NotBlank val docType: String,
    @field:NotBlank val docNumber: String,
    @field:Min(1) val fromStopOrder: Int? = null,
    @field:Min(1) val toStopOrder: Int? = null,
    val paymentType: String = "CASH",
    val items: List<OrderItemRequestBody>? = null,
    val nomenclatureAddons: List<NomenclatureAddonRequestBody>? = null,
    val standaloneNomenclature: List<NomenclatureAddonRequestBody>? = null,
)

data class OrderItemRequestBody(
    @field:Min(1) val seatNumber: Int,
    @field:NotBlank val passengerName: String,
    @field:NotBlank val docType: String,
    @field:NotBlank val docNumber: String,
    @field:Min(1) val fromStopOrder: Int? = null,
    @field:Min(1) val toStopOrder: Int? = null,
    val nomenclatureAddons: List<NomenclatureAddonRequestBody>? = null,
)

data class NomenclatureAddonRequestBody(
    @field:NotNull val nomenclatureItemId: UUID,
    @field:Min(1) val quantity: Int,
)

data class OrderResponse(
    val orderId: String,
    val status: String,
    val totalCents: Long,
    val paidAt: Instant?,
    val ticketId: String,
    val ticketNumber: String,
    val ticketIds: List<String> = emptyList(),
    val paymentTransactionId: String,
)
