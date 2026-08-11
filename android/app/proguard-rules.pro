# AgentDeck

# Vosk offline STT + JNA native bridge (beta/release minify otherwise breaks LibVosk).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

-keep class org.vosk.** { *; }
-keep class org.vosk.android.** { *; }
-dontwarn org.vosk.**
