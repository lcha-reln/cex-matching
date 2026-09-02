import org.gradle.api.tasks.Delete

plugins {
    `java-library`
    application
    alias(libs.plugins.spotless)
}

dependencies {
    implementation(project(":matching-core"))
    implementation(project(":matching-local-runtime"))
    implementation(libs.jackson.databind)

    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.github.lchareln.cex.matching.benchmark.M10LoadMain")
}

val jmhReportFile = layout.buildDirectory.file("jmh-core-sample-time.json").get().asFile
val defaultM10SmokeWalRoot = rootProject.layout.buildDirectory.dir("tmp/m10-ci-smoke-wal")
val defaultM10SmokeOutput = rootProject.layout.buildDirectory.dir("reports/m10-ci-smoke")

val cleanDefaultM10SmokeState = tasks.register<Delete>("cleanDefaultM10SmokeState") {
    group = "benchmark"
    description = "Deletes only the default M10 CI-smoke WAL and evidence directories."
    if (!providers.gradleProperty("m10.walRoot").isPresent) {
        delete(defaultM10SmokeWalRoot)
    }
    if (!providers.gradleProperty("m10.output").isPresent) {
        delete(defaultM10SmokeOutput)
    }
}

tasks.register<JavaExec>("jmhCore") {
    group = "benchmark"
    description = "Runs the diagnostic-only M10 core and canonical-codec SampleTime benchmarks."
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args(
        "io.github.lchareln.cex.matching.benchmark.CoreMatchingBenchmark",
        "-bm", "sample",
        "-rf", "json",
        "-rff", jmhReportFile.absolutePath,
    )
    doNotTrackState("JMH output is an environment-specific diagnostic and must never be reused as a release gate")
}

fun JavaExec.configureM10Load(profile: String) {
    val smoke = profile == "CI_SMOKE"
    val sourceCommit =
        if (smoke) {
            providers.gradleProperty("m10.sourceCommit").orElse(
                providers.exec {
                    workingDir(rootProject.layout.projectDirectory)
                    commandLine("git", "rev-parse", "HEAD")
                }.standardOutput.asText.map { it.trim() },
            ).get()
        } else {
            providers.gradleProperty("m10.sourceCommit").get()
        }
    val walRoot =
        if (smoke) {
            providers.gradleProperty("m10.walRoot")
                .orElse(defaultM10SmokeWalRoot.map { it.asFile.absolutePath })
                .get()
        } else {
            providers.gradleProperty("m10.walRoot").get()
        }
    val output =
        if (smoke) {
            providers.gradleProperty("m10.output")
                .orElse(defaultM10SmokeOutput.map { it.asFile.absolutePath })
                .get()
        } else {
            providers.gradleProperty("m10.output").get()
        }
    fun requiredEnvironmentProperty(name: String): String =
        if (smoke) {
            providers.gradleProperty(name).orElse("METHOD_SMOKE_UNSPECIFIED").get()
        } else {
            providers.gradleProperty(name).get()
        }
    group = "benchmark"
    description = "Runs the $profile scheduled-arrival M10 load method."
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.benchmark.M10LoadMain")
    args(
        "--profile", profile,
        "--repository-root", rootProject.layout.projectDirectory.asFile.absolutePath,
        "--source-commit", sourceCommit,
        "--wal-root", walRoot,
        "--output", output,
        "--cpu-model", requiredEnvironmentProperty("m10.cpuModel"),
        "--storage-device", requiredEnvironmentProperty("m10.storageDevice"),
        "--filesystem", requiredEnvironmentProperty("m10.filesystem"),
        "--power-policy", requiredEnvironmentProperty("m10.powerPolicy"),
        "--run-id", providers.gradleProperty("m10.runId").orElse("m10-$profile").get(),
    )
    if (smoke) {
        dependsOn(cleanDefaultM10SmokeState)
    }
    if (profile == "RELEASE_QUALIFICATION") {
        dependsOn("jmhCore")
        args("--diagnostic-jmh", jmhReportFile.absolutePath)
    }
    doNotTrackState("M10 load evidence is environment-specific and must never reuse stale output")
}

tasks.register<JavaExec>("m10CiSmokeLoad") {
    configureM10Load("CI_SMOKE")
}

tasks.register<JavaExec>("m10ReleaseQualification") {
    configureM10Load("RELEASE_QUALIFICATION")
}
