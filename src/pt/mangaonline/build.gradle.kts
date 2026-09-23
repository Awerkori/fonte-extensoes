import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Online"
    versionCode = 61
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        id = 1057607826304767869L
        lang = "pt-BR"
        baseUrl = "https://mangaonline.love"
    }
}
