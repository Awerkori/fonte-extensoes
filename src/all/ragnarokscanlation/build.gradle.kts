import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Ragnarok Scanlation"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    source {
        lang = "all"
        baseUrl = "https://ragnarokscanlation.org"
    }
}

dependencies {
    implementation(project(":lib-multisrc:madara"))
}
