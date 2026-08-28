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
    systemProperty("matching.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    // These two end-to-end tests freeze M00's deliberate no-order-book boundary. They remain
    // executable at course/m00-complete but are historical by definition after M01 adds the book.
    exclude("**/M00ArchitectureBoundaryTest.class", "**/M00MutantJudgeTest.class")
}

val m00ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m00")
val m00EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M00")
val m00UnitTag = providers.gradleProperty("m00.unitTag").orElse("course/m00-complete")
val m01ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m01")
val m01EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M01")
val m01UnitTag = providers.gradleProperty("m01.unitTag").orElse("course/m01-complete")
val m02ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m02")
val m02EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M02")
val m02UnitTag = providers.gradleProperty("m02.unitTag").orElse("course/m02-complete")

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

tasks.register<JavaExec>("m01Check") {
    group = "verification"
    description = "Runs the completed deterministic M01 price-time judge."
    dependsOn("test", ":matching-core:test", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M01CheckMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m01ReportDirectory.get().asFile.absolutePath,
    )
    doNotTrackState("M01 must never reuse a stale completion report")
}

tasks.register<JavaExec>("m01Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M01 evidence manifest."
    dependsOn("m01Check")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M01EvidenceMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m01ReportDirectory.get().asFile.absolutePath,
        m01EvidenceDirectory.get().asFile.absolutePath,
        m01UnitTag.get(),
    )
    doNotTrackState("Evidence must re-check HEAD and working-tree cleanliness on every invocation")
}

tasks.register<JavaExec>("m02Check") {
    group = "verification"
    description = "Runs the completed deterministic M02 addressable lifecycle judge."
    dependsOn("test", ":matching-core:test", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M02CheckMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m02ReportDirectory.get().asFile.absolutePath,
    )
    doNotTrackState("M02 must never reuse a stale completion report")
}

tasks.register<JavaExec>("m02Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M02 evidence manifest."
    dependsOn("m02Check")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M02EvidenceMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m02ReportDirectory.get().asFile.absolutePath,
        m02EvidenceDirectory.get().asFile.absolutePath,
        m02UnitTag.get(),
    )
    doNotTrackState("Evidence must re-check HEAD and working-tree cleanliness on every invocation")
}
