import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Stop"
    versionCode = 47
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    theme = "mangathemesia"

    source {
        lang = "pt-BR"
        baseUrl = "https://mangastop.net"
    }
}

dependencies {
    api(project(":lib:cookieinterceptor"))
    implementation(project(":lib:randomua"))
}
