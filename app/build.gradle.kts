// Dot — app module build file.
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Reads a value from the environment first, then from a Gradle property
// (command line -P, gradle.properties, or local.properties).
private fun envOrProperty(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }

android {
    namespace = "app.dot"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dot"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release signing config exists only when all four values are
    // present. CI restores the keystore from the KEYSTORE_BASE64 secret
    // and sets KEYSTORE_PATH; the keystore itself is never in the repo.
    val keystorePath = envOrProperty("KEYSTORE_PATH")
    val keystorePassword = envOrProperty("KEYSTORE_PASSWORD")
    val keyAlias = envOrProperty("KEY_ALIAS")
    val keyPassword = envOrProperty("KEY_PASSWORD")

    signingConfigs {
        if (keystorePath != null && keystorePassword != null &&
            keyAlias != null && keyPassword != null
        ) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
