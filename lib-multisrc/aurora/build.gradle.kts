plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 4
    libVersion = "1.6"
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.named("test") {
        kotlin.srcDir("test")
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}
