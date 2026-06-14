package ru.transora.sales.domain

import java.time.Instant
import java.util.UUID

enum class DocType {
    PASSPORT_RF,
    PASSPORT_FOREIGN,
    BIRTH_CERTIFICATE,
    MILITARY_ID,
    OTHER,
}

enum class OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    REFUNDED,
}

enum class PaymentType {
    CASH,
    CARD,
}

enum class PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED,
}

enum class RefundType {
    CASH,
    CARD,
}

enum class FiscalReceiptType {
    SALE,
    REFUND,
    Z_REPORT,
}

enum class OfdStatus {
    PENDING,
    SENT,
    CONFIRMED,
    ERROR,
}

data class FiscalReceipt(
    val id: UUID,
    val shiftId: UUID?,
    val orderId: UUID?,
    val refundId: UUID?,
    val receiptType: FiscalReceiptType,
    val amountCents: Long,
    val fiscalSign: String,
    val fiscalDocNo: Long,
    val fiscalDriveNo: String,
    val ofdStatus: OfdStatus,
    val createdAt: Instant,
)

data class Order(
    val id: UUID,
    val shiftId: UUID,
    val status: OrderStatus,
    val totalCents: Long,
    val createdAt: Instant,
    val expiresAt: Instant,
    val paidAt: Instant? = null,
)

data class OrderItem(
    val id: UUID,
    val orderId: UUID,
    val reservationId: UUID,
    val tripId: UUID,
    val seatNumber: Int,
    val passengerName: String,
    val docType: DocType,
    val docNumber: String,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val tariffId: UUID?,
    val priceCents: Long,
)

data class Payment(
    val id: UUID,
    val orderId: UUID,
    val paymentType: PaymentType,
    val amountCents: Long,
    val status: PaymentStatus,
    val transactionId: String? = null,
    val processedAt: Instant? = null,
)

data class Tariff(
    val id: UUID,
    val routeNumber: String,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val priceCents: Long,
    val isActive: Boolean,
)

data class RefundPolicyTier(
    val id: UUID,
    val policyId: UUID,
    val hoursBeforeMin: Int?,
    val hoursBeforeMax: Int?,
    val penaltyPercent: java.math.BigDecimal,
    val refundAllowed: Boolean,
    val sortOrder: Int,
)

data class Refund(
    val id: UUID,
    val ticketId: UUID,
    val policyId: UUID,
    val penaltyPercent: java.math.BigDecimal,
    val penaltyCents: Long,
    val serviceFeeCents: Long,
    val refundCents: Long,
    val refundType: RefundType,
    val createdAt: Instant,
)

data class RefundPreview(
    val penaltyPercent: java.math.BigDecimal,
    val penaltyCents: Long,
    val serviceFeeCents: Long,
    val refundCents: Long,
    val refundAllowed: Boolean,
    val policyId: java.util.UUID? = null,
)
