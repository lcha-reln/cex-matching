import com.diffplug.gradle.spotless.SpotlessExtension
import io.github.lchareln.cex.build.M00StartCheck
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
    alias(libs.plugins.spotless)
}

group = "io.github.lchareln.cex"
version = "0.0.0-m00-dev"

spotless {
    format("rootMisc") {
        target(
            "*.md",
            "*.properties",
            "*.kts",
            "*.toml",
            ".editorconfig",
            ".gitattributes",
            ".gitignore",
            ".github/**/*.yml",
            "docs/**/*.md",
            "schemas/**/*.json",
            "matching-testkit/src/test/resources/**/*.json",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release = 25
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxParallelForks = 1
            systemProperty("file.encoding", "UTF-8")
        }
    }

    pluginManager.withPlugin("com.diffplug.spotless") {
        extensions.configure<SpotlessExtension> {
            java {
                // This formatter version is part of the immutable M00 start contract.
                googleJavaFormat("1.36.1")
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
            }
        }

        tasks.matching { it.name == "check" }.configureEach {
            dependsOn("spotlessCheck")
        }
    }
}

tasks.named("assemble") {
    dependsOn(":matching-core:assemble", ":matching-testkit:assemble")
}

tasks.named("check") {
    dependsOn("spotlessCheck", ":matching-core:check", ":matching-testkit:check")
}

val m00Report = layout.buildDirectory.file("reports/m00/check.json")

tasks.register<M00StartCheck>("m00Check") {
    group = "verification"
    description = "Runs the M00 executable contract. The start tag must report GOAL_NOT_IMPLEMENTED."
    reportFile.set(m00Report)
}
