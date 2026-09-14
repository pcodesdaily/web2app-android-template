# The shell has no reflection-based entry points beyond the Android framework's
# own, which the default AGP rules already cover. Kept as an explicit anchor so
# minify-enabled builds have a file to extend.
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
