# ML Kit translation loads native and model components by name.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }

# Keep model metadata and native JNI entry points used by translation.
-keep class com.google.android.libraries.** { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Libraries used by the reader may discover implementations reflectively.
-keep class org.jsoup.** { *; }
-keep class com.bumptech.glide.** { *; }
-dontwarn org.conscrypt.**
