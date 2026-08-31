plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

dependencies {
    api(project(":matching-core"))
    implementation(project(":matching-reference"))
    implementation(libs.jackson.databind)
    implementation(libs.json.schema.validator)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    systemProperty("m00.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("matching.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    // Historical source-identity checks remain executable from their immutable completion tags.
    // M05 re-runs their semantic contracts but must not rebind old source/import gates to this tree.
    exclude(
        "**/M00ArchitectureBoundaryTest.class",
        "**/M00MutantJudgeTest.class",
        "**/M01ArchitectureBoundaryTest.class",
        "**/M01CheckRunnerTest.class",
        "**/M01EvidenceWriterTest.class",
        "**/M02ArchitectureBoundaryTest.class",
        "**/M02CheckRunnerTest.class",
        "**/M03ArchitectureBoundaryTest.class",
        "**/M03CheckRunnerTest.class",
        "**/M04ArchitectureGateTest.class",
        "**/M04StartCheckRunnerTest.class",
        "**/M06StartCheckRunnerTest.class",
    )
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
val m03ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m03")
val m03EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M03")
val m03UnitTag = providers.gradleProperty("m03.unitTag").orElse("course/m03-complete")
val m03ProductRelease = providers.gradleProperty("m03.productRelease").orElse("matching-0.1.0")
val m04ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m04")
val m04EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M04")
val m04UnitTag = providers.gradleProperty("m04.unitTag").orElse("course/m04-complete")
val m05ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m05")
val m05EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M05")
val m05UnitTag = providers.gradleProperty("m05.unitTag").orElse("course/m05-complete")
val m06ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m06")
val m06EvidenceDirectory = rootProject.layout.buildDirectory.dir("lab-evidence/M06")
val m06UnitTag = providers.gradleProperty("m06.unitTag").orElse("course/m06-complete")
val m07ReportDirectory = rootProject.layout.buildDirectory.dir("reports/m07")

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

tasks.register<JavaExec>("m03Check") {
    group = "verification"
    description = "Runs the completed deterministic M03 generated-property judge."
    dependsOn("test", ":matching-core:test", ":matching-reference:check", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M03CheckMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m03ReportDirectory.get().asFile.absolutePath,
    )
    doNotTrackState("M03 must never reuse a stale completion report")
}

tasks.register<JavaExec>("m03Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M03 evidence manifest."
    dependsOn("m03Check")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M03EvidenceMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m03ReportDirectory.get().asFile.absolutePath,
        m03EvidenceDirectory.get().asFile.absolutePath,
        m03UnitTag.get(),
        m03ProductRelease.get(),
    )
    doNotTrackState("Evidence must re-check both M03 tags and working-tree cleanliness on every invocation")
}

tasks.register<JavaExec>("m04Check") {
    group = "verification"
    description = "Runs the completed deterministic M04 execution-policy judge."
    dependsOn("test", ":matching-core:test", ":matching-reference:check", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M04CheckMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m04ReportDirectory.get().asFile.absolutePath,
    )
    doNotTrackState("M04 must never reuse a stale completion report")
}

tasks.register<JavaExec>("m04Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M04 evidence manifest."
    dependsOn("m04Check")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M04EvidenceMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m04ReportDirectory.get().asFile.absolutePath,
        m04EvidenceDirectory.get().asFile.absolutePath,
        m04UnitTag.get(),
    )
    doNotTrackState("Evidence must re-check the M04 tag and working-tree cleanliness on every invocation")
}

tasks.register<JavaExec>("m05Check") {
    group = "verification"
    description = "Runs the declared M05 versioned price-band boundary."
    dependsOn("test", ":matching-core:test", ":matching-reference:check", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M05CheckMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m05ReportDirectory.get().asFile.absolutePath,
    )
    doNotTrackState("M05 must never reuse a stale report")
}

tasks.register<JavaExec>("m05Evidence") {
    group = "verification"
    description = "Generates and validates the clean-tree M05 evidence manifest."
    dependsOn("m05Check")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M05EvidenceMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m05ReportDirectory.get().asFile.absolutePath,
        m05EvidenceDirectory.get().asFile.absolutePath,
        m05UnitTag.get(),
    )
    doNotTrackState("Evidence must re-check the M05 tag and working-tree cleanliness on every invocation")
}

tasks.register<JavaExec>("m06Check") {
    group = "verification"
    description = "Runs the completed M06 operating-mode and deterministic Mass Cancel judge."
    dependsOn("test", ":matching-core:test", ":matching-reference:check", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M06CheckMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m06ReportDirectory.get().asFile.absolutePath,
    )
    doNotTrackState("M06 must never reuse a stale completion report")
}

tasks.register<JavaExec>("m06Evidence") {
    group = "verification"
    description = "Generates and validates clean-tree annotated-tag M06 evidence."
    dependsOn("m06Check")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M06EvidenceMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m06ReportDirectory.get().asFile.absolutePath,
        m06EvidenceDirectory.get().asFile.absolutePath,
        m06UnitTag.get(),
    )
    doNotTrackState("Evidence must re-check the M06 tag and working-tree cleanliness on every invocation")
}

tasks.register<JavaExec>("m07Check") {
    group = "verification"
    description = "Validates the frozen M07 self-trade-prevention RED boundary."
    dependsOn("test", ":matching-core:test", ":matching-reference:check", "classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lchareln.cex.matching.testkit.M07CheckMain")
    args(
        rootProject.layout.projectDirectory.asFile.absolutePath,
        m07ReportDirectory.get().asFile.absolutePath,
    )
    doNotTrackState("M07 must never reuse a stale RED report")
}
