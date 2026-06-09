# ProGuard / R8 rules for FRP 穿透
-keep class top.zw.frpc.** { *; }
-dontwarn androidx.**
-keep class androidx.** { *; }
