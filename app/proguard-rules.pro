# ---------------------------------------------------------------------------
# Release build: minify + resource shrinking are ON, so everything here exists to
# keep R8 from removing something that is only reached indirectly.
#
# Rules are deliberately narrow: the libraries in use (Room, OkHttp, Compose,
# media3, coroutines) each ship their own consumer rules, and none of this app's
# own classes are accessed reflectively — NoteItem parses JSON by direct calls,
# not by reflection. Anything added below must be justified by an actual failure.
# ---------------------------------------------------------------------------

# Kotlin metadata is read reflectively by several libraries (coroutines, Compose).
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Enums are resolved by name through values()/entries; ThemeMode is restored from
# a persisted string, so the constants must survive.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Objective-C style property annotation used by some optional OkHttp paths.
-dontwarn javax.annotation.**

# Compose tooling / preview hooks referenced only by the debug tooling artifact.
-dontwarn androidx.compose.ui.tooling.**

# Room's generated database implementation is instantiated by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ---------------------------------------------------------------------------
# ViewModels — found by RUNNING the minified build, not by reading the rules.
#
# Without this the release APK died at launch with:
#   java.lang.IllegalArgumentException: No compatible ctor for <obfuscated>
#     at androidx.lifecycle.ViewModelProvider
# ViewModelProvider resolves the ViewModel class and its constructor
# reflectively, which R8 cannot see; shrinking renamed the class and dropped the
# constructor, so the lookup failed. Every screen's ViewModel goes through it.
# ---------------------------------------------------------------------------
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * implements androidx.lifecycle.ViewModelProvider$Factory {
    <init>(...);
}

# Optional OkHttp platform integrations absent from the APK.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**