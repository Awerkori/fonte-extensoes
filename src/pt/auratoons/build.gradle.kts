import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "AuraToons"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        baseUrl = "https://auratoons.com"
        lang = "pt-BR"
    }

    deeplink {
        path("/..*")
    }
}
