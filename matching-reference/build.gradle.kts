plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

// Main code is intentionally JDK-only and has no project dependency. JUnit is test-only.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
