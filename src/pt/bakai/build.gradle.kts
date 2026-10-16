import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Bakai"
    versionCode = 21
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://bakai.org"
    }
}
