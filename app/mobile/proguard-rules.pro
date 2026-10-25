# Dependencies provide their own consumer rules. Android manifest components
# and XML callbacks are covered by the Android Gradle plugin's default rules.
# JSON messages are parsed explicitly with JSONObject, without reflective DTOs.
-keepattributes SourceFile,LineNumberTable
# Shizuku creates this class reflectively in its privileged UserService process.
-keep class com.chuckiehelper.mobile.phonecontrol.PhoneShellService { public <init>(); public *; }
