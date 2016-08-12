package de.setsoftware.reviewtool.telemetry;

/**
 * Singleton holding the configured telemetry.
 * Singleton value can change upon reconfiguration.
 */
public class Telemetry {

    private static AbstractTelemetry instance;

    public static AbstractTelemetry get() {
        return instance;
    }

    public static TelemetryEventBuilder event(String type) {
        return new TelemetryEventBuilder(instance, type);
    }

    public static void set(AbstractTelemetry t) {
        instance = t;
    }

}
