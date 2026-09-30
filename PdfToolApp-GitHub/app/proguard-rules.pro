# Keep minimal; no obfuscation in this release build.
-dontwarn org.apache.**
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

-keep class org.apache.pdfbox.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }
