import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Bladetoons"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "mangotheme"

    source {
        lang = "pt-BR"
        baseUrl = "https://bladetoons.com"
    }
}
