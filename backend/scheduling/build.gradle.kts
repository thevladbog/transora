plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":backend:shared"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

