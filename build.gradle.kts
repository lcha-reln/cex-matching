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
version = "0.5.0"

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
    dependsOn(":matching-core:assemble", ":matching-local-runtime:assemble", ":matching-benchmarks:assemble", ":matching-reference:assemble", ":matching-testkit:assemble")
}

tasks.named("check") {
    dependsOn("spotlessCheck", ":matching-core:check", ":matching-local-runtime:check", ":matching-benchmarks:check", ":matching-reference:check", ":matching-testkit:check")
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
    description = "Runs the completed M04 execution-policy proof."
    dependsOn(":matching-testkit:m04Check")
}

tasks.register("m04Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M04 evidence manifest."
    dependsOn(":matching-testkit:m04Evidence")
}

tasks.register("m05Check") {
    group = "verification"
    description = "Runs the declared M05 versioned price-band boundary."
    dependsOn(":matching-testkit:m05Check")
}

tasks.register("m05Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M05 evidence manifest."
    dependsOn(":matching-testkit:m05Evidence")
}

tasks.register("m06Check") {
    group = "verification"
    description = "Runs the completed M06 market-mode and deterministic Mass Cancel judge."
    dependsOn(":matching-testkit:m06Check")
}

tasks.register("m06Evidence") {
    group = "verification"
    description = "Generates and validates clean-tree annotated-tag M06 evidence."
    dependsOn(":matching-testkit:m06Evidence")
}

tasks.register("m07Check") {
    group = "verification"
    description = "Runs the completed M07 self-trade-prevention semantic judge."
    dependsOn(":matching-testkit:m07Check")
}

tasks.register("m07Evidence") {
    group = "verification"
    description = "Generates and validates clean-tree annotated-tag M07 evidence."
    dependsOn(":matching-testkit:m07Evidence")
}

tasks.register("m08Check") {
    group = "verification"
    description = "Runs the completed M08 local durability judge."
    dependsOn(":matching-testkit:m08Check")
}

tasks.register("m08Evidence") {
    group = "verification"
    description = "Generates and validates clean-tree annotated-tag M08 evidence."
    dependsOn(":matching-testkit:m08Evidence")
}

tasks.register("m09Check") {
    group = "verification"
    description = "Runs the completed M09 snapshot and bounded-recovery judge."
    dependsOn(":matching-testkit:m09Check")
}

tasks.register("m09Evidence") {
    group = "verification"
    description = "Generates and validates clean-tree annotated-tag M09 evidence."
    dependsOn(":matching-testkit:m09Evidence")
}

tasks.register("m10Check") {
    group = "verification"
    description = "Runs the completed M10 bounded-admission and qualification-method judge."
    dependsOn(":matching-testkit:m10Check")
}

tasks.register("m10CiSmokeLoad") {
    group = "benchmark"
    description = "Runs the method-isomorphic M10 CI smoke load; never release evidence."
    dependsOn(":matching-benchmarks:m10CiSmokeLoad")
}

tasks.register("m10ReleaseQualification") {
    group = "benchmark"
    description = "Runs the complete environment-specific M10 release qualification."
    dependsOn(":matching-benchmarks:m10ReleaseQualification")
}

tasks.register("m10Evidence") {
    group = "verification"
    description = "Validates and publishes clean-tree M10 correctness and full release evidence."
    dependsOn(":matching-testkit:m10Evidence")
}

tasks.register("m11Check") {
    group = "verification"
    description = "Writes the schema-valid intentional M11 start-contract RED report."
    dependsOn(":matching-testkit:m11Check")
}
