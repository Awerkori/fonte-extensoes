import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mugiwaras Oficial"
    versionCode = 58
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://mugiwarasoficial.org"
    }
}

dependencies {
    implementation(project(":lib:cookieinterceptor"))
}
