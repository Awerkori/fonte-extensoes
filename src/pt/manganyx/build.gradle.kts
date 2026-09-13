import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga NXY"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    theme = "aurora"

    source {
        lang = "pt-BR"
        baseUrl = "https://manganyx.org"
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
}

// Unit tests exercise the interceptor, not generated source metadata.
tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
