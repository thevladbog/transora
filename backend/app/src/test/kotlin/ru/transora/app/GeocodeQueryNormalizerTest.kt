package ru.transora.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.transora.app.admin.buildGeocodeSearchQueries
import ru.transora.app.admin.normalizeRussianAddress

class GeocodeQueryNormalizerTest {
    @Test
    fun `normalizes stanitsa street address for nominatim`() {
        val normalized = normalizeRussianAddress("станица Ленинградская ул. Кооперации, 88")
        assertEquals("Ленинградская, ул Кооперации, 88", normalized)
    }

    @Test
    fun `builds prioritized query variants`() {
        val variants = buildGeocodeSearchQueries("станица Ленинградская ул. Кооперации, 88")
        assertTrue(variants.first().contains("Ленинградская"))
        assertTrue(variants.first().contains("Кооперации"))
        assertTrue(variants.contains("станица Ленинградская ул. Кооперации, 88"))
    }

    @Test
    fun `includes city hint in variants`() {
        val variants = buildGeocodeSearchQueries("ул. Кооперации, 88", city = "Ленинградская")
        assertTrue(variants.any { it.startsWith("Ленинградская,") })
    }
}
