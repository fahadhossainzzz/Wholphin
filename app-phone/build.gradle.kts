import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

// NoBars phone app (stage 1): find the server, sign in, Home (Continue Watching, Next Up, Recently added), Library,
// Search, title pages with seasons and episodes, and the player built on core-player. Its own app ID, so it never
// replaces or touches the TV app. Signed with the same key as the TV app on CI.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

private fun Provider<String>.getInt() = get().toInt()

val shouldSign = providers.environmentVariable("CI").orElse("false").map { it.toBoolean() }
    .zip(providers.environmentVariable("KEY_ALIAS").orElse("").map { it.isNotBlank() }) { ci, key -> ci && key }
val phoneVersion = rootProject.file("NOBARS_PHONE_VERSION").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "p0.1" }

android {
    namespace = "com.nobars.phone"
    compileSdk {
        version = release(libs.versions.compileSdk.getInt())
    }

    defaultConfig {
        applicationId = "com.github.damontecres.wholphin.nobars.phone"
        minSdk = libs.versions.minSdk.getInt()
        targetSdk = libs.versions.compileSdk.getInt()
        versionCode = 4
        versionName = phoneVersion
    }

    signingConfigs {
        if (shouldSign.get()) {
            create("ci") {
                file("ci.keystore").writeBytes(Base64.getDecoder().decode(System.getenv("SIGNING_KEY")))
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                storeFile = file("ci.keystore")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (shouldSign.get()) signingConfig = signingConfigs.getByName("ci")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":core-player"))
    implementation(project(":core-data"))
    implementation(project(":core-jellyfin"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    // ffmpeg audio (DTS, TrueHD) when the official extensions can be downloaded, as for the TV app
    if (project.hasProperty("WholphinExtensionsUsername")) {
        implementation(libs.wholphin.extensions.ffmpeg)
    }

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
