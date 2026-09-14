# Proguard rules for FocusLock (R8 release shrinking — audit item 12)
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class * {
    @org.jetbrains.annotations.* <fields>;
    @org.jetbrains.annotations.* <methods>;
}

# --- kotlinx.serialization (ConvexSyncClient + TickTickApiClient + data models) ---
# @Serializable classes resolve serializers through generated Companion/serializer()
# members; keep them so Json.encodeToString/decodeFromString survive shrinking.
-keep,includedescriptorclasses class com.focuslock.app.**$$serializer { *; }
-keepclassmembers class com.focuslock.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.focuslock.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Clerk Android SDK ---
# The SDK resolves parts of its API reflectively; no official consumer rules are
# published for the Android SDK, so keep the whole package conservatively (audit item 12).
-keep class com.clerk.** { *; }
-dontwarn com.clerk.**

# --- WorkManager ---
# Workers are instantiated reflectively from class names persisted in WorkDatabase.
# WorkManager ships consumer rules, but keep our workers' constructors explicitly
# so a future WorkManager upgrade can never drop them.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- OkHttp / Okio ---
# Both bundle consumer rules with R8 out of the box; only silence known optional
# platform warnings (Conscrypt/OpenJSSE/BouncyCastle TLS providers).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
