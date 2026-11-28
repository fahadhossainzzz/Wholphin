import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NoBars core-jellyfin: our own small layer on Jellyfin's official toolkit, for the phone app (and later the TV): find
// the server, sign in (password or Quick Connect), remember the login (token encrypted with the Android keystore),
// library queries, start playback (direct play), report progress, send playback reports. Written for NoBars, not
// copied from Wholphin.
plugins {
    alias(libs.plugins.android.library)
}

private fun Provider<String>.getInt() = get().toInt()

android {
    namespace = "com.nobars.jellyfin"
    compileSdk {
        version = release(libs.versions.compileSdk.getInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.getInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    api(libs.jellyfin.core)
    api(libs.jellyfin.api)
    implementation(libs.jellyfin.api.okhttp)
    implementation(project(":core-data"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
