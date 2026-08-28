plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

// M03 intentionally keeps this module free of project and external dependencies. The independent
// semantic implementation is added only after course/m03-start has frozen this boundary.
