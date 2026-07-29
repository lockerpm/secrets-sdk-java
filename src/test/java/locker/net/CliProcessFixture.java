package locker.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class CliProcessFixture {
    private CliProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("locker.fixture.mode", "echo");
        switch (mode) {
            case "echo":
                echo(args);
                return;
            case "sleep":
                Thread.sleep(30_000);
                return;
            case "overflow":
                overflow();
                return;
            case "overflow-forever":
                overflowForever();
                return;
            case "spawn":
                spawnChild(false);
                return;
            case "spawn-and-exit":
                spawnChild(true);
                return;
            default:
                throw new IllegalArgumentException("Unknown fixture mode");
        }
    }

    private static void echo(String[] args) throws IOException {
        byte[] request = System.in.readAllBytes();
        String argument = args.length == 1 ? args[0] : "unexpected";
        String response = String.format(
                "{\"argument\":\"%s\",\"request_bytes\":%d}",
                argument,
                request.length
        );
        System.out.write(response.getBytes(StandardCharsets.UTF_8));
        System.err.write("safe diagnostic".getBytes(StandardCharsets.UTF_8));
    }

    private static void overflow() throws IOException {
        byte[] bytes = new byte[4096];
        System.out.write(bytes);
    }

    private static void overflowForever() {
        byte[] bytes = new byte[4096];
        while (true) {
            System.out.write(bytes, 0, bytes.length);
            System.out.flush();
        }
    }

    private static void spawnChild(boolean exitAfterTrackerSample)
            throws Exception {
        String pidFile = System.getProperty(
                "locker.fixture.childPidFile"
        );
        if (pidFile == null || pidFile.isBlank()) {
            throw new IllegalArgumentException(
                    "Child PID file is required"
            );
        }
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        ProcessBuilder childBuilder = new ProcessBuilder(
                executable,
                "-Dlocker.fixture.mode=sleep",
                "-cp",
                System.getProperty("java.class.path"),
                CliProcessFixture.class.getName()
        );
        if (exitAfterTrackerSample) {
            childBuilder
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
        } else {
            childBuilder.inheritIO();
        }
        Process child = childBuilder.start();
        Files.writeString(
                Path.of(pidFile),
                Long.toString(child.pid()),
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        Thread.sleep(exitAfterTrackerSample ? 250 : 30_000);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
