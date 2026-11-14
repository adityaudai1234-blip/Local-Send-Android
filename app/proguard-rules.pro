# LocalDrop R8 / ProGuard Production Rules

# Preserve stack trace line numbers safely
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room Database Entities, DAOs, and TypeConverters
-keep class com.example.data.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# Protocol & Pairing Models
-keep class com.example.data.protocol.** { *; }
-keep class com.example.data.pairing.PairingPayload { *; }
-keep class com.example.domain.model.** { *; }

# Components referenced by AndroidManifest.xml
-keep class com.example.LocalDropApp { *; }
-keep class com.example.MainActivity { *; }
-keep class com.example.data.transfer.FileTransferService { *; }
-keep class com.example.data.transfer.TransferNotificationReceiver { *; }

# ZXing QR Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**
