-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.hubg50we.dc.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
