import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Sagrado Imp\u00e9rio da Britannia"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "mangotheme"

    source {
        lang = "pt-BR"
        baseUrl = "https://imperiodabritannia.net"
        id = 7355004027880350247L
    }
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    sourceSets.named("test") {
        kotlin.srcDir("test")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.core)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.jsoup)
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
