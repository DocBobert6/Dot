// Dot — standalone Original Game Boy (DMG) remaster app for Android.
// Copyright (c) 2026 Dot contributors. All rights reserved.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Dot"
include(":app")
