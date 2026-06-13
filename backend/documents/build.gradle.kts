plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":backend:shared"))
    implementation(project(":backend:sales"))
    testImplementation(kotlin("test"))
}

