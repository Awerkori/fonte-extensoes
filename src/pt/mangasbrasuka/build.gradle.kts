import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mangas Brasuka"
    versionCode = 57
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    theme = "aurora"

    source {
        lang = "pt-BR"
        baseUrl = "https://mangasbrasuka.org"
        versionId = 2
    }
}
