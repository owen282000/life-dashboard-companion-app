# R8 rules for the release build. Everything not listed here is shrunk and obfuscated.
# Consumer rules shipped inside the AndroidX, Compose, WorkManager and OkHttp artifacts
# cover those libraries; this file covers what the app itself and the MQTT stack need.

# Crash reports stay readable: keep source file names and line numbers.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: @Serializable classes are (de)serialised through generated
# companions and $$serializer classes that R8 cannot see references to.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.owen282000.lifedashboard.**$$serializer { *; }
-keepclassmembers class com.owen282000.lifedashboard.** { *** Companion; }
-keepclasseswithmembers class com.owen282000.lifedashboard.** { kotlinx.serialization.KSerializer serializer(...); }

# WorkManager's Room database: Room instantiates the generated *_Impl class by name, and
# without this the release build dies on launch with "Failed to create an instance of
# class androidx.work.impl.WorkDatabase".
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.impl.** { *; }

# Enum names are persisted in preferences and logs (HealthDataType, LogType, MqttSection).
-keepclassmembers enum com.owen282000.lifedashboard.** { *; }

# HiveMQ MQTT client and the Netty it bundles resolve channels, handlers and unsafe
# accessors reflectively; keeping them whole costs size but not correctness.
-keep class com.hivemq.client.** { *; }
-keep class io.netty.** { *; }
-dontwarn com.hivemq.client.**
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-dontwarn org.jctools.**
-dontwarn org.jetbrains.annotations.**
-dontwarn dagger.**
-dontwarn javax.inject.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.log4j.**
-dontwarn com.google.protobuf.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.eclipse.jetty.**
-dontwarn reactor.blockhound.**
-dontwarn sun.misc.**
-dontwarn sun.security.**
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**
