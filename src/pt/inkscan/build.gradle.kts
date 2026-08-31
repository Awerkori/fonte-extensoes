import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    source {
        name = "Ink Scan"
        lang = "pt-BR"
        baseUrl = "https://inkscann.live"
    }
    name = "Ink Scan"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
}
