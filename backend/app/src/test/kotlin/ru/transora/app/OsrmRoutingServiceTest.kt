package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.transora.app.admin.OsrmRoutingService
import ru.transora.app.admin.RouteWaypoint
import ru.transora.app.admin.RoutingProperties

class OsrmRoutingServiceTest {
    private val properties = RoutingProperties().apply { enabled = false }
    private val service = OsrmRoutingService(properties)

    @Test
    fun `calculates straight line distance when osrm disabled`() {
        val result = service.calculate(
            listOf(
                RouteWaypoint(1, 55.75, 37.61),
                RouteWaypoint(2, 55.76, 37.62),
            ),
        )
        assertThat(result.distanceKm).isNotNull()
        assertThat(result.distanceSource?.name).isEqualTo("STRAIGHT_LINE")
        assertThat(result.legs).hasSize(1)
    }

    @Test
    fun `returns empty for single waypoint`() {
        val result = service.calculate(listOf(RouteWaypoint(1, 55.75, 37.61)))
        assertThat(result.distanceKm).isNull()
        assertThat(result.legs).isEmpty()
    }
}
