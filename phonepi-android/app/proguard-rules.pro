# Minification is off in release for v1. Keep rules here for when it turns on.

# kotlinx.serialization: keep generated serializers.
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# OkHttp pulls optional Conscrypt/BouncyCastle; ignore missing ones.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
