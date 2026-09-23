import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Vegitoons"
    versionCode = 13
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "pt-BR"
        baseUrl = "https://vegitoons.black"
    }
}

dependencies {
    implementation(project(":lib-multisrc:greenshit"))
}
