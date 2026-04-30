# Shrink only — no obfuscation, no optimization.
-dontoptimize
-dontobfuscate

# Preserve Kotlin metadata so reflection and serialization continue to work.
-keepkotlinmetadata
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Suppress warnings about references into the IntelliJ library JARs.
-dontwarn **

# ── Entry point ──────────────────────────────────────────────────────────────
-keep class io.github.amichne.kast.standalone.StandaloneMainKt {
    public static void main(java.lang.String[]);
}

# ── kast public API ──────────────────────────────────────────────────────────
# Accessed reflectively by the IntelliJ plugin loader and via JSON-RPC dispatch.
-keep class io.github.amichne.kast.** { *; }

# ── Compat-JAR bridge classes ─────────────────────────────────────────────────
# These com.intellij.ide.plugins classes are bundled in ide-plugin-compat.jar
# and must survive to win the classpath race against IntelliJ's own versions.
-keep class com.intellij.ide.plugins.** { *; }
-keep class com.intellij.util.messages.ListenerDescriptor { *; }

# ── ServiceLoader / SPI registrations ────────────────────────────────────────
-keepclassmembers class * {
    @java.lang.Override *;
}

# ── Standard serializable contract ───────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object readResolve();
}

# ── Gradle Tooling API ────────────────────────────────────────────────────────
# Used at runtime via GradleConnector/ProjectConnection; classes are loaded by
# Gradle's own class loader after the daemon starts.
-keep class org.gradle.tooling.** { *; }
-keep class org.gradle.tooling.internal.** { *; }

# ── OpenTelemetry ─────────────────────────────────────────────────────────────
# SDK components are wired at startup via SPI; keep all SDK classes.
-keep class io.opentelemetry.api.** { *; }
-keep class io.opentelemetry.sdk.** { *; }
-keep class io.opentelemetry.context.** { *; }

# ── Logback ───────────────────────────────────────────────────────────────────
# Configured via logback.xml; keep all appenders and encoders.
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }

# ── SQLite JDBC ───────────────────────────────────────────────────────────────
# Native-bridge classes loaded via System.loadLibrary at runtime.
-keep class org.sqlite.** { *; }
