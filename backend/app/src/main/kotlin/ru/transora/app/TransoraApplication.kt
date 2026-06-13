package ru.transora.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["ru.transora"])
@EnableScheduling
class TransoraApplication

fun main(args: Array<String>) {
    runApplication<TransoraApplication>(*args)
}

