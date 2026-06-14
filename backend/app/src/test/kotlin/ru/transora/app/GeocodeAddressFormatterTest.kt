package ru.transora.app

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.transora.app.admin.extractLocalityName
import ru.transora.app.admin.formatRussianAddress

class GeocodeAddressFormatterTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `formats reverse geocode address for stanitsa`() {
        val address = mapper.readTree(
            """
            {
              "house_number": "88",
              "road": "улица Кооперации",
              "town": "Ленинградская",
              "county": "Ленинградский муниципальный округ",
              "state": "Краснодарский край",
              "postcode": "353740",
              "country": "Россия"
            }
            """.trimIndent(),
        )

        assertEquals(
            "353740, Россия, Краснодарский край, Ленинградский муниципальный округ, Ленинградская, улица Кооперации, 88",
            formatRussianAddress(address),
        )
        assertEquals("Ленинградская", extractLocalityName(address))
    }
}
