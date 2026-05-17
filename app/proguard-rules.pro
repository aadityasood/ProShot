# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renameSourceFileAttribute SourceFile

# --- ProShot-specific rules ---

# TODO(T-future): Add TFLite keep rules when minification is enabled
# -keep class org.tensorflow.lite.** { *; }

# TODO(T-future): Add MediaPipe keep rules when minification is enabled
# -keep class com.google.mediapipe.** { *; }

# TODO(T-future): Add OpenCV keep rules when integrated
# -keep class org.opencv.** { *; }

# Hilt (already handled by Hilt Gradle plugin, but defensive)
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
