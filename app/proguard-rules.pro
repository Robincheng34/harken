# Harken 倾耳 — R8/ProGuard rules for the release build.

# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.rc.harken.** {
    *** Companion;
}
-keepclasseswithmembers class com.rc.harken.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.rc.harken.**$$serializer { *; }
-keepclassmembers class com.rc.harken.** {
    *** INSTANCE;
}

# OkHttp ships with these consumer rules already; keep the defensives.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room: entities/DAOs are reflected over by the generated implementation.
-keep class com.rc.harken.storage.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# Media3: MediaSessionService / PlayerService are resolved by manifest name.
-keep class com.rc.harken.player.PlayerService { *; }
-keep class com.rc.harken.player.** extends androidx.media3.session.MediaSessionService { *; }

# DataStore preferences: keep the Preferences.Key generated accessors.
-keepclassmembers class com.rc.harken.storage.** {
    *** Companion;
}

# zxing: QR encoder reflectively touches encoders/hints.
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }
