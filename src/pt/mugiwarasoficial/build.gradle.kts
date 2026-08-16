import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mugiwaras Oficial"
    versionCode = 56
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://mugiwarasoficial.com"
    }
}

dependencies {
    implementation(project(":lib:cookieinterceptor"))
}
