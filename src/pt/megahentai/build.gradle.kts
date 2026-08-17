import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    source {
        name = "MegaHentai"
        lang = "pt-BR"
        baseUrl = "https://megahentai.biz"
    }
    name = "MegaHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
}
