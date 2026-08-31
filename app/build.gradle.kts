plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

val lightSdkPath = providers.gradleProperty("lightious.sdkPath").getOrElse("../light-sdk")
val releaseKeystorePath = providers.environmentVariable("LIGHTIOUS_RELEASE_KEYSTORE").orNull

fun requiredReleaseEnvironment(name: String): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: error("$name must be set when LIGHTIOUS_RELEASE_KEYSTORE is configured")

android {
    compileSdk = 36

    signingConfigs {
        create("lightsdkDev") {
            storeFile = rootProject.file("$lightSdkPath/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }

        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = requiredReleaseEnvironment("LIGHTIOUS_RELEASE_STORE_PASSWORD")
                keyAlias = requiredReleaseEnvironment("LIGHTIOUS_RELEASE_KEY_ALIAS")
                keyPassword = requiredReleaseEnvironment("LIGHTIOUS_RELEASE_KEY_PASSWORD")
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    defaultConfig {
        minSdk = 34
        targetSdk = 36

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (releaseKeystorePath.isNullOrBlank()) {
                signingConfigs.getByName("lightsdkDev")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.sdk.client)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
