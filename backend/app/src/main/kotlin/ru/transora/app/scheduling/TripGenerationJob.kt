package ru.transora.app.scheduling

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile("!test")
class TripGenerationJob(
    private val tripGenerationService: TripGenerationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${transora.scheduling.generation-cron:0 0 2 * * *}")
    fun generateTrips() {
        val result = tripGenerationService.generate()
        log.info(
            "Trip generation completed: created={}, skipped={}, window={}..{}",
            result.createdCount,
            result.skippedCount,
            result.fromDate,
            result.toDate,
        )
    }
}
