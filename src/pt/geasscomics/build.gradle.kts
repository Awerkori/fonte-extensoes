import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets.named("test") {
        java.directories.add("test")
        kotlin.directories.add("test")
        resources.directories.add("test/resources")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.okhttp.core)
    testImplementation(libs.kotlin.json)
}

// Source metadata is generated for the main source set, not the unit-test sources.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

keiyoushi {
    name = "Geass Comics"
    versionCode = 6
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://geasscomics.xyz"
        versionId = 2
    }
}
