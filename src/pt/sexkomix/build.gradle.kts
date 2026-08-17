import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SexKomix 2"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "SexKomix 2"
        baseUrl = "https://sexkomix2.com"
        lang = "pt-BR"
    }

    deeplink {
        path("/..*")
    }
}
