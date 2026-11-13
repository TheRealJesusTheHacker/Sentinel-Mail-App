// Sentinel Mail Desktop — STANDALONE build (desktop port, 2026-11-13).
// Deliberately NOT part of the Android root build: the Android build's
// gradle/verification-metadata.xml must stay byte-identical, so this
// build carries its own settings and its own verification metadata
// (desktop/gradle/verification-metadata.xml, generated from this
// build's resolved graph and committed).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The desktop graph lives on Maven Central: JetBrains Compose
        // Multiplatform, Skiko, Eclipse Angus Mail, kotlinx, JNA.
        // (dl.google.com is unreachable from the build box.)
        mavenCentral()
        // Two CMP transitives (androidx.annotation, androidx.collection)
        // publish only to Google Maven; on the build box, where
        // dl.google.com is unreachable, the same artifacts are
        // supplied from a file:// seed via an init script instead.
        google()
    }
}
rootProject.name = "SentinelMailDesktop"
