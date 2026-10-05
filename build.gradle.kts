// The SDK is a single Android library module; everything that has to be configured per module
// lives in umapi/build.gradle.kts.
plugins {
    // Kept in step with the Android SDK the app itself is built with
    // (see UndertaleModToolAvalonia.Android's Microsoft.Android.Sdk version).
    id("com.android.library") version "8.9.1" apply false
}

// group/version come from gradle.properties so that the release workflow can override the version
// with -Pversion=<tag> without touching any file.
