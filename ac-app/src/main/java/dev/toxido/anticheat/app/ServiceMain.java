package dev.toxido.anticheat.app;

/**
 * Gorunmez arka plan servisi. Terminal splash'i kapansa da Minecraft
 * kapanana kadar calismaya devam eder.
 */
public final class ServiceMain {
    private ServiceMain() {
    }

    public static void main(String[] args) {
        LogReporter reporter = new LogReporter(LogReporter.defaultLogFile());
        reporter.log("Toxido AC servisi baslatiliyor.");

        try (ServiceLock lock = ServiceLock.acquire()) {
            if (lock == null) {
                reporter.log("Baska bir servis zaten calisiyor; cikiliyor.");
                return;
            }

            Config config = Config.load();
            reporter.log("Servis hazir. API: " + config.apiBaseUrl
                + " | cihaz: " + config.deviceId());

            AppController controller = new AppController(config, reporter);
            controller.run();
        } catch (Throwable throwable) {
            reporter.log("HATA: " + throwable.getMessage());
            throwable.printStackTrace();
        } finally {
            reporter.log("Toxido AC servisi kapandi.");
            System.exit(0);
        }
    }
}
