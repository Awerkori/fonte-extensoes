import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Tomato"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://prod-api.tomatoanimes.com"
        lang = "pt-BR"
    }

    deeplink {
        path("/v2/manga/..*")
    }
}
