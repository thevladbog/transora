package ru.transora.app

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/system")
class SystemController {
    @GetMapping("/version")
    fun version(): SystemVersionResponse =
        SystemVersionResponse(
            name = "transora-core",
            version = "0.1.0-SNAPSHOT",
            timestamp = Instant.now(),
        )
}

data class SystemVersionResponse(
    val name: String,
    val version: String,
    val timestamp: Instant,
)

