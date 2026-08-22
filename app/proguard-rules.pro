# The system instantiates these by name from the manifest, so R8 must not rename or remove them.
-keep class com.avdesign.mfd24.MfdWatchFaceService { *; }
-keep class com.avdesign.mfd24.editor.WatchConfigActivity { *; }
-keep class com.avdesign.mfd24.data.TelemetryWorker { *; }
-keep class com.avdesign.mfd24.WatchShiftReceiver { *; }
-keep class com.avdesign.mfd24.data.VigilanceService { *; }
-keep class com.avdesign.mfd24.export.LogExportActivity { *; }
-keep class com.avdesign.mfd24.export.RepoLinkActivity { *; }
-keep class com.avdesign.mfd24.update.ReleaseLinkActivity { *; }

# Health Services talks to its provider in protocol buffers, and proto-lite finds a message's
# fields *by name through reflection*. R8 renames fields, so the lookup fails inside a static
# initializer — which surfaces as ExceptionInInitializerError, not as a catchable Exception, and
# took the whole watch face down the moment the steps slot asked for the day's total. Symptom to
# recognise if this ever comes back: "java.lang.RuntimeException: Field name_ for <class> not
# found. Known fields are [...]".
#
# Debug builds are not minified, which is why this only ever appeared on a release APK. Anything
# reflective has to be exercised on a release build before it ships.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$Builder { <fields>; }
-keep class androidx.health.services.client.impl.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn androidx.health.services.client.**
