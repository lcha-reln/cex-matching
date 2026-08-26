plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

dependencies {
    api(project(":matching-core"))
    implementation(libs.jackson.databind)
    implementation(libs.json.schema.validator)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    systemProperty("m00.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}

val m00ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m00")
val m00EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M00")
val m00UnitTag = providers.gradleProperty("m00.unitTag").orElse("course/m00-complete")

tasks.register<JavaExec>("m00Check") {
    group = "verification"
    description = "Runs the deterministic M00 completion judge."
    dependsOn("test", ":matching-core:test", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M00CheckMain")
    args(rootProject.layout.projectDirectory.asFile.absolutePath, m00ReportDirectory.get().asFile.absolutePath)
    doNotTrackState("M00 must never reuse a stale PASS report")
}

tasks.register<JavaExec>("m00Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M00 evidence manifest."
    dependsOn("m00Check")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M00EvidenceMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m00ReportDirectory.get().asFile.absolutePath,
        m00EvidenceDirectory.get().asFile.absolutePath,
        m00UnitTag.get(),
    )
    doNotTrackState("Evidence must re-check HEAD and working-tree cleanliness on every invocation")
}
