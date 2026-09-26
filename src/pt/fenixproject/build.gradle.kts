import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Fenix Project"
    versionCode = 59
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "pt-BR"
        baseUrl = "https://fenixproject.website"
    }
}

dependencies {
    implementation(project(":lib-multisrc:madaralegacy"))
}
