// Build for the QiuUTMTv5 cross-app API SDK: one Android library module (:umapi) that ships the
// AIDL contract plus a small Java client, published as an AAR (and a raw classes jar) to GitHub
// Packages / GitHub Pages by .github/workflows/publish.yml.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QiuUTMTv5-API"

include(":umapi")
