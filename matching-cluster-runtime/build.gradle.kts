import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

dependencies {
    api(project(":matching-local-runtime"))
    implementation(libs.aeron.cluster)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val java25AeronOpens = listOf("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")

tasks.withType<Test>().configureEach {
    jvmArgs(java25AeronOpens)
    systemProperty("matching.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(java25AeronOpens)
}
