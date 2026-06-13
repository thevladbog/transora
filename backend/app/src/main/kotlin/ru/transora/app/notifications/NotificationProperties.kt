package ru.transora.app.notifications

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "transora.notifications")
class NotificationProperties {
    var storagePath: String = "./data/audio"
    var playbackPollIntervalMs: Long = 5000
    var departureSchedulerCron: String = "0 * * * * *"
}
