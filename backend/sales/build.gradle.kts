plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":backend:shared"))
    implementation(project(":backend:inventory"))
    testImplementation(kotlin("test"))
}

