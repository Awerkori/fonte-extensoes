import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "OneReader"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://onereader.net"
    }

    deeplink {
        path("/obra")
        path("/leitor")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("test")
    }
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
