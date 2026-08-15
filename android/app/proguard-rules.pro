# AgentDeck

# Vosk offline STT + JNA native bridge (beta/release minify otherwise breaks LibVosk).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

-keep class org.vosk.** { *; }
-keep class org.vosk.android.** { *; }
-dontwarn org.vosk.**

# Lab host executors are loaded by reflection from src/main.
-keep class com.agentdeck.app.data.host.lab.LabUiAutomationHolder { *; }
-keep class com.agentdeck.app.data.host.lab.LabIntentExecutorImpl { *; }
-keep class com.agentdeck.app.data.host.lab.LabPrivilegedExecutorImpl { *; }
-keep class com.agentdeck.app.data.host.lab.LabAccessibilityService { *; }
