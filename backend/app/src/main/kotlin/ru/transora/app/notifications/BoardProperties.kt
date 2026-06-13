package ru.transora.app.notifications

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "transora.board")
class BoardProperties {
    var windowBeforeMin: Int = 30
    var windowAfterMin: Int = 180
}
