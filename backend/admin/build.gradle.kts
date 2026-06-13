plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":backend:shared"))
    testImplementation(kotlin("test"))
}
