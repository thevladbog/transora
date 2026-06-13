package ru.transora.hardwareagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MockHardwareAgentApplication

fun main(args: Array<String>) {
    runApplication<MockHardwareAgentApplication>(*args)
}
