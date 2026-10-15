import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Loobyt"
    versionCode = 0
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://loobyt.org"
        id = 7462657023971681136
    }

    deeplink {
        host("loobyt.org")
        host("noxmangas.org")
        path("/manga/..*")
        path("/read/..*")
    }
}
