# R8 / ProGuard rules for Take Master (Compose + Media3 ExoPlayer / Transformer)

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Media3 / ExoPlayer & Transformer native/reflection hooks
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Compose view interop (PlayerView inflated from XML)
-keep class com.takemaster.studio.MainActivity { *; }
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}
