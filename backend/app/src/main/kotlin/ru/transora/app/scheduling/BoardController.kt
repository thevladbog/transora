package ru.transora.app.scheduling

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.notifications.BoardArrivalsPayload
import ru.transora.app.notifications.BoardDeparturesPayload
import ru.transora.app.notifications.BoardPlatformPayload
import ru.transora.app.notifications.BoardStateService

@RestController
@RequestMapping("/api/board")
@Tag(name = "Board", description = "Departure and arrival board read models")
class BoardController(
    private val boardStateService: BoardStateService,
) {
    @GetMapping("/departures")
    @Operation(summary = "List departures for display board (stop-aware)")
    fun departures(
        @RequestParam(defaultValue = "T1") stationCode: String,
        @RequestParam(required = false) windowBeforeMin: Int?,
        @RequestParam(required = false) windowAfterMin: Int?,
    ): BoardDeparturesPayload =
        boardStateService.departures(stationCode, windowBeforeMin, windowAfterMin)

    @GetMapping("/arrivals")
    @Operation(summary = "List arrivals for display board (stop-aware)")
    fun arrivals(
        @RequestParam(defaultValue = "T1") stationCode: String,
        @RequestParam(required = false) windowBeforeMin: Int?,
        @RequestParam(required = false) windowAfterMin: Int?,
    ): BoardArrivalsPayload =
        boardStateService.arrivals(stationCode, windowBeforeMin, windowAfterMin)

    @GetMapping("/platform/{platformNumber}")
    @Operation(summary = "List trips assigned to a platform")
    fun platform(
        @PathVariable platformNumber: String,
        @RequestParam(defaultValue = "T1") stationCode: String,
        @RequestParam(required = false) windowBeforeMin: Int?,
        @RequestParam(required = false) windowAfterMin: Int?,
    ): BoardPlatformPayload =
        boardStateService.platform(stationCode, platformNumber, windowBeforeMin, windowAfterMin)
}
