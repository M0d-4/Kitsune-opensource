@file:Suppress("UnstableApiUsage")

// The app-list hiding payload is a separate Gradle build on purpose: the ZygoteLoader plugin
// needs the AGP 8.x "applicationVariants" API, while the Magisk app itself builds with AGP 9.
// Build it with:  ./gradlew -p app/applist :zygote:assembleRelease
// (tools/build.py does this and stages the result as the app's applist_payload.zip asset)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "applist"

include(":common", ":stub", ":zygote")
