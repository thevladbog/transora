package ru.transora.app.hardware

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(HardwareAgentProperties::class)
class HardwareAgentConfiguration
