import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Tia Manhwa"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "madaralegacy"

    source {
        lang = "pt-BR"
        baseUrl = "https://tiamanhwa.com"
    }
}
