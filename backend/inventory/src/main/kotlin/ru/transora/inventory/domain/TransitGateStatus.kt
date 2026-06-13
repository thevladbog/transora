package ru.transora.inventory.domain

enum class TransitGateStatus {
    AWAITING_ARRIVAL,
    OPEN,
    CLOSED,
    ;

    companion object {
        fun parse(value: String): TransitGateStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown transit gate status: $value")
    }
}
