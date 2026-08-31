pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "cex-matching"

include("matching-core", "matching-local-runtime", "matching-testkit")
include("matching-reference")
