package ru.transora.app.admin

import com.fasterxml.jackson.databind.JsonNode

private val STREET_FIELDS = listOf("road", "street", "pedestrian", "footway", "path", "residential")

fun formatRussianAddress(address: JsonNode?): String? {
    if (address == null || address.isMissingNode) {
        return null
    }

    val parts = linkedSetOf<String>()

    address.textOrNull("postcode")?.let { parts += it }
    address.textOrNull("country")?.let { parts += it }
    address.textOrNull("state")?.let { parts += it }

    sequenceOf("county", "state_district", "district")
        .mapNotNull { field -> address.textOrNull(field) }
        .firstOrNull { district -> district !in parts }
        ?.let { parts += it }

    extractSettlement(address)?.takeIf { it !in parts }?.let { parts += it }

    extractStreet(address)?.takeIf { it !in parts }?.let { parts += it }
    address.textOrNull("house_number")?.let { parts += it }

    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

fun extractSettlement(address: JsonNode): String? =
    sequenceOf("city", "town", "village", "hamlet", "municipality")
        .mapNotNull { field -> address.textOrNull(field) }
        .firstOrNull()

fun extractLocalityName(address: JsonNode?): String =
    address?.let(::extractSettlement).orEmpty()

private fun extractStreet(address: JsonNode): String? =
    STREET_FIELDS.asSequence()
        .mapNotNull { field -> address.textOrNull(field) }
        .firstOrNull()

fun JsonNode.textOrNull(field: String): String? =
    path(field).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }

fun formatPhotonAddress(
    postcode: String?,
    country: String?,
    state: String?,
    county: String?,
    city: String?,
    street: String?,
    housenumber: String?,
): String? {
    val parts = linkedSetOf<String>()
    postcode?.takeIf { it.isNotBlank() }?.let { parts += it }
    country?.takeIf { it.isNotBlank() }?.let { parts += it }
    state?.takeIf { it.isNotBlank() }?.let { parts += it }
    county?.takeIf { it.isNotBlank() && it !in parts }?.let { parts += it }
    city?.takeIf { it.isNotBlank() && it !in parts }?.let { parts += it }
    street?.takeIf { it.isNotBlank() && it !in parts }?.let { parts += it }
    housenumber?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
