# Compose, Coil and the AndroidX libraries ship their own consumer rules. The app uses no reflection,
# so nothing extra needs keeping. Add -keep rules here only if a release build misbehaves.

# NewPipe Extractor (YouTube on the phone) and the JavaScript engine it uses; rules from NewPipe's own app.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn com.google.re2j.**
-keep class com.grack.nanojson.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
}
