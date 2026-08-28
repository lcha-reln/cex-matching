import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
    alias(libs.plugins.spotless)
}

group = "io.github.lchareln.cex"
version = "0.1.0"

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
    dependsOn(":matching-core:assemble", ":matching-reference:assemble", ":matching-testkit:assemble")
}

tasks.named("check") {
    dependsOn("spotlessCheck", ":matching-core:check", ":matching-reference:check", ":matching-testkit:check", "m03Check")
}

tasks.register("m00Check") {
    group = "verification"
    description = "Runs the deterministic M00 contract, goldens, replays, architecture gate, and mutants."
    dependsOn(":matching-testkit:m00Check")
}

tasks.register("m00Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M00 evidence manifest."
    dependsOn(":matching-testkit:m00Evidence")
}

tasks.register("m01Check") {
    group = "verification"
    description = "Runs the M01 single-instrument GTC price-time contract."
    dependsOn(":matching-testkit:m01Check")
}

tasks.register("m01Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M01 evidence manifest."
    dependsOn(":matching-testkit:m01Evidence")
}

tasks.register("m02Check") {
    group = "verification"
    description = "Runs the completed M02 addressable order lifecycle contract."
    dependsOn(":matching-testkit:m02Check")
}

tasks.register("m02Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M02 evidence manifest."
    dependsOn(":matching-testkit:m02Evidence")
}

tasks.register("m03Check") {
    group = "verification"
    description = "Runs the completed M03 independent generated-model boundary."
    dependsOn(":matching-testkit:m03Check")
}

tasks.register("m03Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M03 evidence manifest."
    dependsOn(":matching-testkit:m03Evidence")
}

tasks.register("m04Check") {
    group = "verification"
    description = "Runs the declared M04 execution-policy RED boundary."
    dependsOn(":matching-testkit:m04Check")
}
