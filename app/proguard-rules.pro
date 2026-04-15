# Media3 + ExoPlayer still rely on reflection in a few places (extractors,
# renderers loaded by class name). Keep the whole androidx.media3 tree —
# the library isn't large enough to bother shrinking selectively.
-keep class androidx.media3.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin metadata needs to survive R8 for reflective serialization
# libraries; we don't use any right now but cheap insurance.
-keep class kotlin.Metadata { *; }
