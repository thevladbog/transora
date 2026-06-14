package ru.transora.app.admin

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "transora.routing")
class RoutingProperties {
    var osrmBaseUrl: String = "https://router.project-osrm.org"
    var enabled: Boolean = true
    var cacheTtlMinutes: Long = 1440
}
