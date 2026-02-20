# Screencast ProGuard Rules

# Keep SSDP/DLNA classes
-keep class com.screencast.discovery.** { *; }
-keep class com.screencast.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Moshi
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
}

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
