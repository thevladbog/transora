package ru.transora.app

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import ru.transora.app.test.TestIamConfiguration
import ru.transora.app.test.TestIamRedisConfig

@SpringBootTest
@ActiveProfiles("test")
@Import(TestIamConfiguration::class, TestIamRedisConfig::class)
abstract class IntegrationTestBase {
    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("transora")
            .withUsername("transora")
            .withPassword("transora")
            .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Test
    fun contextLoads() {
    }
}
