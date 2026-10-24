# Keep serializable API models intact.
-keep class io.cloudy.app.data.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.**
