# Kumone 雲音 — R8/ProGuard rules for the release build.

# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class im.missuo.kumone.** {
    *** Companion;
}
-keepclasseswithmembers class im.missuo.kumone.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class im.missuo.kumone.**$$serializer { *; }
-keepclassmembers class im.missuo.kumone.** {
    *** INSTANCE;
}

# OkHttp ships with these consumer rules already; keep the defensives.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room: entities/DAOs are reflected over by the generated implementation.
-keep class im.missuo.kumone.storage.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# Media3: MediaSessionService / PlayerService are resolved by manifest name.
-keep class im.missuo.kumone.player.PlayerService { *; }
-keep class im.missuo.kumone.player.** extends androidx.media3.session.MediaSessionService { *; }

# DataStore preferences: keep the Preferences.Key generated accessors.
-keepclassmembers class im.missuo.kumone.storage.** {
    *** Companion;
}

# zxing: QR encoder reflectively touches encoders/hints.
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }
