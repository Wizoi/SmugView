# SmugView R8 / ProGuard rules
#
# Minification and resource shrinking are ENABLED for release. They were previously disabled
# wholesale because R8 stripped/renamed the reflection-based Gson models, which silently broke all
# JSON parsing. The correct fix is to keep only the reflective surfaces (the API models and the
# attributes Gson/Retrofit need), rather than turning shrinking off for the entire app.
#
# If you add a new package whose classes are populated by Gson reflection, add a -keep for it here.

########################################
# Readable release stack traces (R-51)
########################################
# Keep line numbers so crashes in the diagnostics log can be retraced with the archived mapping
# (release-mappings/<versionCode>/mapping.txt). The source file name is hidden behind "SourceFile".
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

########################################
# Attributes required by Gson/Retrofit reflection
########################################
# Signature is required for Gson to resolve generic types (e.g. List<AlbumImageData>).
-keepattributes Signature
# Annotations drive @SerializedName and Retrofit's HTTP annotations.
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes EnclosingMethod,InnerClasses
-keepattributes Exceptions

########################################
# SmugView data models (deserialized reflectively by Gson)
########################################
# Field names must survive obfuscation because Gson maps JSON keys to them by name.
-keep class com.smugview.app.data.api.** { *; }
-keepclassmembers class com.smugview.app.data.api.** {
    <init>(...);
    <fields>;
}

# Room entities/DAO types are also reflected over by the Room runtime.
-keep class com.smugview.app.data.db.** { *; }

########################################
# Gson
########################################
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson uses generic type information stored in a class file when working with fields.
-keepclassmembers enum * { *; }

########################################
# Retrofit
########################################
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Retain service method annotations on Retrofit interfaces.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keepclasseswithmembers,includedescriptorclasses class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn javax.annotation.**

########################################
# OkHttp
########################################
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

########################################
# Kotlin
########################################
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
