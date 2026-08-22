import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    source {
        name = "Fliptru"
        lang = "pt-BR"
        baseUrl = "https://fliptru.com.br"
    }
    name = "Fliptru"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
}
