# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.cursorforandroid.**$$serializer { *; }
-keepclassmembers class com.cursorforandroid.** { *** Companion; }
-keepclasseswithmembers class com.cursorforandroid.** { kotlinx.serialization.KSerializer serializer(...); }

# Tink (via androidx.security:security-crypto) references Error Prone's compile-time annotations, which are not on the
# runtime classpath; R8 treats the missing classes as an error without this.
-dontwarn com.google.errorprone.annotations.**

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
