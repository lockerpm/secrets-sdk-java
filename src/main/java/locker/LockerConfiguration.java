package locker;

import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;


public class LockerConfiguration {
    private static LockerConfiguration instance;
    private static final Object lockObject = new Object();
    @Getter
    private String sdkVersion = "0.0.4";
    private String lockerDir;
    @Getter
    private String binaryFilePath;
    private String binaryVersion = "1.0.88";

    private LockerConfiguration() {
        initBinaryPath();
        downloadBinaryFile();
    }

    public static LockerConfiguration getInstance() {
        if (instance == null) {
            synchronized (lockObject) {
                if (instance == null) {
                    instance = new LockerConfiguration();
                }
            }
        }
        return instance;
    }

    private void initBinaryPath() {
        String homeDir = System.getProperty("user.home");
        lockerDir = Paths.get(homeDir, ".locker").toString();
        binaryFilePath = Paths.get(lockerDir, "locker_binary-" + binaryVersion).toString();
    }

    private void downloadBinaryFile() {
        String binaryUrl;
        String osName = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch");

        if (osName.contains("mac")) {
            binaryUrl = "https://s.locker.io/download/locker-cli-mac-" + (arch.equals("aarch64") ? "arm64" : "x64") + "-" + binaryVersion;
        } else if (osName.contains("win")) {
            binaryUrl = "https://s.locker.io/download/locker-cli-win-x64-" + binaryVersion + ".exe";
            binaryFilePath = Paths.get(lockerDir, "locker_binary-" + binaryVersion + ".exe").toString();
        } else {
            binaryUrl = "https://s.locker.io/download/locker-cli-linux-x64-" + binaryVersion;
        }

        // Check if the .locker directory exists, and create it if not
        File lockerDirFile = new File(lockerDir);
        if (!lockerDirFile.exists()) {
            lockerDirFile.mkdirs();
        }

        // Download binary file
        File binaryFile = new File(binaryFilePath);
        if (!binaryFile.exists()) {
            try {
                System.out.println("Saving to " + binaryFile.getAbsolutePath());
                ProcessBuilder processBuilder = new ProcessBuilder("curl", "-o", binaryFile.getAbsolutePath(), binaryUrl);
                Process process = processBuilder.start();
                process.waitFor();
                process.destroy();

                binaryFile.setExecutable(true);
                binaryFile.setReadable(true);
                binaryFile.setWritable(false);

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }

        }
    }

}