import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Animy"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://animy.net"
        lang = "pt-BR"
    }

    deeplink {
        path("/manga/..*")
    }
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("test")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
