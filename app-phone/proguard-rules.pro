# NoBars phone: keep the Jellyfin model (kotlinx serialization) and media3 extension renderers found by reflection
-keep class org.jellyfin.sdk.model.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.jellyfin.sdk.**
