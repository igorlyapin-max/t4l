-keepattributes *Annotation*
-keepclassmembers class ** {
    @androidx.room.* <methods>;
}

# Tink references these source-retention annotations only in metadata. They are
# intentionally not shipped by security-crypto and have no runtime behavior.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
