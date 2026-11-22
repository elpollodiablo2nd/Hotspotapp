-keep class com.elpollodiablo.hotspotapp.PixelBridge { *; }

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
