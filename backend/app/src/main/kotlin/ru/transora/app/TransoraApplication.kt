package ru.transora.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.transora"])
class TransoraApplication

fun main(args: Array<String>) {
    runApplication<TransoraApplication>(*args)
}

