package ru.transora.app.admin

private val SETTLEMENT_TYPE_PREFIX =
    Regex("""^(станица|село|деревня|пос[её]лок|пгт|город|хутор|аул)\s+""", RegexOption.IGNORE_CASE)

private val STREET_WITHOUT_COMMA =
    Regex("""^(.+?)\s+(ул\.?|улица|пр\.?|проспект|пер\.?|переулок|ш\.?|шоссе)\s+(.+)$""", RegexOption.IGNORE_CASE)

fun buildGeocodeSearchQueries(query: String, city: String? = null): List<String> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) {
        return emptyList()
    }

    val stripped = stripSettlementType(trimmed)
    val normalized = normalizeRussianAddress(stripped)
    val normalizedOriginal = normalizeRussianAddress(trimmed)
    val cityHint = city?.trim()?.takeIf { it.isNotBlank() }

    return listOfNotNull(
        normalized.takeIf { it.isNotBlank() },
        normalizedOriginal.takeIf { it.isNotBlank() && it != normalized },
        cityHint?.let { "$it, $normalized" },
        stripped.takeIf { it.isNotBlank() && it != trimmed },
        trimmed,
        cityHint?.let { "$it, $trimmed" },
    ).distinct()
}

fun stripSettlementType(query: String): String =
    query.trim().replace(SETTLEMENT_TYPE_PREFIX, "").trim()

fun normalizeRussianAddress(query: String): String {
    var normalized = stripSettlementType(query)
    normalized = normalized.replace(Regex("""ул\.\s*""", RegexOption.IGNORE_CASE), "ул ")
    normalized = normalized.replace(Regex("""(?<=\s)ул\s+""", RegexOption.IGNORE_CASE), "ул ")
    normalized = normalized.replace(Regex("""пр\.\s*""", RegexOption.IGNORE_CASE), "проспект ")
    normalized = normalized.replace(Regex("""пер\.\s*""", RegexOption.IGNORE_CASE), "переулок ")
    normalized = normalized.replace(Regex("""ш\.\s*""", RegexOption.IGNORE_CASE), "шоссе ")
    normalized = normalized.replace(Regex("""\s+,"""), ",")
    normalized = normalized.replace(Regex(""",\s*"""), ", ")
    normalized = normalized.replace(Regex("""\s+"""), " ").trim()

    val match = STREET_WITHOUT_COMMA.find(normalized) ?: return normalized
    val settlement = match.groupValues[1].trim().trim(',')
    val streetType = match.groupValues[2].trim()
    val rest = match.groupValues[3].trim()
    return listOf(settlement, "$streetType $rest").joinToString(", ")
}
