plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":backend:shared"))
    implementation(project(":backend:scheduling"))
    testImplementation(kotlin("test"))
}

