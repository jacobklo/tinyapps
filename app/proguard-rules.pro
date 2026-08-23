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

# --- WebView Javascript Interface Protection ---
# JavaScript calls TableBridge's four methods by name from table.html, so R8 must not
# rename or remove them.
#
# Belt and braces, not a fix: proguard-android-optimize.txt, which this build already
# includes, carries an app-wide "-keepclassmembers class * { @JavascriptInterface
# <methods>; }" that covers TableBridge on its own. Verified against the release dex -
# with this rule and without it, sort/resize/reorder/renderComplete keep their names and
# their VISIBILITY_RUNTIME annotation while the class becomes a.qq0 and its fields a-f.
# This rule names the one class that depends on that guarantee, so removing the default
# file from proguardFiles cannot silently break the table.
#
# The class NAME is deliberately not kept: the page reaches the bridge through the
# "Android" binding, never by class name, so only the method names are load bearing.
-keepclassmembers class net.jacoblo.simpleanki.table.TableBridge {
    @android.webkit.JavascriptInterface <methods>;
}

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