# ProGuard/R8 Rules for Bambu Filament Manager

# Preserve attributes required for reflection and debugging
-keepattributes Signature,InnerClasses,AnnotationDefault,EnclosingMethod,Exceptions,*Annotation*,SourceFile,LineNumberTable

# Netty / HiveMQ - Full protection from obfuscation
-keep class io.netty.** { *; }
-keepclassmembers class io.netty.** { *; }
-keep class com.hivemq.client.** { *; }
-keepclassmembers class com.hivemq.client.** { *; }

# JCTools - Fix for NoSuchFieldException: consumerIndex / producerIndex
# RxJava and HiveMQ depend on this for high-performance queues
-keep class org.jctools.** { *; }
-keepclassmembers class org.jctools.** { *; }

# Specific fix for the 'toLeakAwareBuffer' crash
-keepclassmembers class * {
    *** toLeakAwareBuffer(...);
}

# Preserve internal methods in AbstractByteBufAllocator used by Netty's leak detector
-keepclassmembernames class io.netty.buffer.AbstractByteBufAllocator {
    private <methods>;
}

# RxJava and Dagger
-keep class io.reactivex.** { *; }
-keep class dagger.internal.DoubleCheck { *; }

# Data Models (Gson/Room) - Fix for Deserialization failed
# We keep all fields in your database package to ensure JSON mapping works
-keep class com.napps.filamentmanager.database.** { *; }
-keepclassmembers class com.napps.filamentmanager.database.** { *; }

# Suppress warnings for libraries that reference missing classes on Android
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern
-dontwarn io.netty.**
-dontwarn com.hivemq.client.**
-dontwarn io.reactivex.**
-dontwarn dagger.internal.**
-dontwarn org.jctools.**
-dontwarn javax.naming.**
-dontwarn org.apache.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.slf4j.**

# Room Database rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Remove logs from release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# DO NOT obfuscate class or method names (prevents crashes with reflection/JSON)
-dontobfuscate

# DO NOT remove unused code (optional, but safer if you want no minification at all)
#-dontshrink
