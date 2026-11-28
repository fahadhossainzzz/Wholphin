import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NoBars core-data: the data shared by the TV app and (later) the phone app: Watchlist (WantedStore), History, the
// Netflix / Prime / Theatre catalogues (StreamingStore), where-to-watch and the server connection (WatchProviders),
// box office, poster facts, sizes and IMDb votes. No screens here; each app makes its own cards from these.
plugins {
    alias(libs.plugins.android.library)
}

private fun Provider<String>.getInt() = get().toInt()

android {
    namespace = "com.github.damontecres.wholphin.core.data"
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
    implementation(libs.jellyfin.api)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
