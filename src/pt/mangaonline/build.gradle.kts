import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Online RED"
    versionCode = 60
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "pt-BR"
        baseUrl = "https://mangaonline.red"
    }
}
