# Keep the @JavascriptInterface bridge intact under R8: the WebView resolves these
# by name at runtime, so renaming them silently breaks session capture in release.
-keepclassmembers class org.emilumiq.alloha.parser.AllohaSessionBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class org.emilumiq.alloha.parser.AllohaSessionBridge { *; }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
