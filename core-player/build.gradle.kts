import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NoBars core-player: the playback engine shared by the TV app and (later) the phone app: read-ahead
// (ElasticLoadControl), stall rescue and diagnostics (StallGuard, StreamHealth), the MP4/MKV readers, subtitle delay,
// DTS handling. No screens here; the apps pass in what is playing through PlaybackInfo.
plugins {
    alias(libs.plugins.android.library)
}

private fun Provider<String>.getInt() = get().toInt()

android {
    namespace = "com.github.damontecres.wholphin.core.player"
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
    api(libs.androidx.media3.exoplayer)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.coil.core)
    implementation(libs.jellyfin.core)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
