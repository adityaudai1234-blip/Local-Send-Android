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

# Components referenced by AndroidManifest.xml & Widgets
-keep class com.example.LocalDropApp { *; }
-keep class com.example.MainActivity { *; }
-keep class com.example.data.transfer.FileTransferService { *; }
-keep class com.example.data.transfer.TransferNotificationReceiver { *; }
-keep class com.example.widget.** { *; }
-keep class com.example.data.transfer.FileMetadata { *; }
-keep class com.example.data.transfer.ResumeMetadata { *; }
-keep class com.example.data.transfer.IncomingTransferRequest { *; }
-keep class com.example.data.transfer.ConflictAction { *; }
-keep class com.example.data.transfer.ConflictPrompt { *; }
-keep class com.example.data.transfer.ConflictDecision { *; }
-keep class com.example.data.network.ConnectionErrorEvent { *; }
-keep class com.example.domain.model.AppError { *; }
-keep class com.example.domain.model.ErrorCategory { *; }
-keep class com.example.domain.model.ReleaseNoteEntry { *; }
-keep class com.example.util.LocalCrashReporter { *; }
-keep class com.example.util.PermissionHelper { *; }
-keep class com.example.util.PerformanceMonitor { *; }
-keep class com.example.util.PerformanceSnapshot { *; }

# AndroidX Core SplashScreen
-keep class androidx.core.splashscreen.** { *; }

# ZXing QR Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**
