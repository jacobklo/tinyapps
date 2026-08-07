# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
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
#-renamesourcefileattribute SourceFile

# --- Aggressive Size Optimizations ---

# Assumes no side effects in class initializers (helps remove unused static blocks)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Aggressive optimization passes
-optimizationpasses 5

# Allow changing access modifiers to package-private to improve access speed and size
-allowaccessmodification

# Remove attributes that are only useful for debugging
-keepattributes *Annotation* # Keep annotations for Jetpack Compose and JS Interface
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable # Keep strictly if you need crash stack traces, remove to save more bytes

# Repackage all classes into a single package (flattens hierarchy strings)
-repackageclasses 'a'