# ── B116: strip VERBOSE/DEBUG logging from SHIPPED release builds ──────────────
# Release is minified by R8 with proguard-android-optimize.txt (optimization ON, which
# -assumenosideeffects requires). Covers direct android.util.Log calls AND Kermit's logcat writer
# (it calls Log.v/Log.d underneath), so Kermit verbose/debug lines vanish from release too.
# Nothing in production reads logcat: crash telemetry is ApplicationExitInfo + PostHog/Sentry
# exception capture (PostHog captureLogcat=false and log upload dropped; Sentry logcat
# instrumentation disabled). INFO/WARN/ERROR stay — and stay redacted via LogRedaction.
# Explicit method signatures, never a `*` wildcard (that would also strip isLoggable etc.).
# Ref: developer.android.com/topic/performance/app-optimization/additional-rule-types
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
