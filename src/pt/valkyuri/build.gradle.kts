import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    source {
        name = "ValkYuri"
        lang = "pt-BR"
        baseUrl = "https://valkyuri.com"
    }
    name = "ValkYuri"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
}
