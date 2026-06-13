package ru.transora.app.scheduling

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "transora.scheduling")
class SchedulingProperties {
    var generationHorizonDays: Int = 60
    var lifecycleAutoAdvance: Boolean = false
}
