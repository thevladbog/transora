plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    implementation(project(":backend:shared"))
    implementation(project(":backend:iam"))
    implementation(project(":backend:scheduling"))
    implementation(project(":backend:inventory"))
    implementation(project(":backend:sales"))
    implementation(project(":backend:documents"))
    implementation(project(":backend:notifications"))
    implementation(project(":backend:boarding"))
    implementation(project(":backend:admin"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.aspectj:aspectjweaver:1.9.24")
    implementation("org.springframework:spring-aop")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.8")

    runtimeOnly("org.postgresql:postgresql")

    implementation("com.github.librepdf:openpdf:2.0.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
}

tasks.named<Test>("test") {
    maxParallelForks = 1
    jvmArgs("-Xmx1g")
}

tasks.test {
    maxParallelForks = 1
}
