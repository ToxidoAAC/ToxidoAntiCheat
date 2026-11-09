package dev.toxido.anticheat.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Ayni anda ikinci bir servis baslatilmasini engeller. Kilit dosyasi
 * surec kapaninca isletim sistemi tarafindan otomatik birakilir.
 */
public final class ServiceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private ServiceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ServiceLock acquire() throws IOException {
        Path path = LogReporter.appDataDirectory().resolve("service.lock");
        Files.createDirectories(path.getParent());

        FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        );

        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new ServiceLock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            channel.close();
            return null;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Kilit zaten birakilmis olabilir.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Kanal zaten kapanmis olabilir.
        }
    }
}
