# Add project specific ProGuard rules here.

# --- Library Integrity ---
-keep class aman.playbackengine.enginecore.** { *; }
-keep class aman.playbackengine.enginempv.** { *; }
-keep class aman.playbackengine.engineexoplayer.** { *; }

# --- Native MPV Rules ---
-keep class is.xyz.mpv.** { *; }
-keepnames class is.xyz.mpv.** { *; }

# --- Coil (Image Loading) ---
-keep class coil.** { *; }

# --- Jetpack Compose ---
-keep class androidx.compose.** { *; }

# --- Media3 / ExoPlayer ---
-keep class androidx.media3.** { *; }

# Keep line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
