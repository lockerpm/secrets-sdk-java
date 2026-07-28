package locker.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutableHeaderValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAllFiveCanonicalReleaseTargets() throws Exception {
        verify(
                UpdateChannelFixture.executable("linux", "amd64"),
                "Linux",
                "amd64"
        );
        verify(
                UpdateChannelFixture.executable("linux", "arm64"),
                "Linux",
                "arm64"
        );
        verify(
                UpdateChannelFixture.executable("darwin", "amd64"),
                "Mac OS X",
                "amd64"
        );
        verify(
                UpdateChannelFixture.executable("darwin", "arm64"),
                "Mac OS X",
                "arm64"
        );
        verify(
                UpdateChannelFixture.executable("windows", "amd64"),
                "Windows 11",
                "amd64"
        );
    }

    @Test
    void rejectsTruncatedAndScriptArtifacts() {
        assertRejected(new byte[19], "Linux", "amd64");
        assertRejected(new byte[7], "Mac OS X", "amd64");
        assertRejected(new byte[63], "Windows 11", "amd64");
        byte[] script = "#!/bin/sh\nexit 0\n".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII
        );
        assertRejected(script, "Linux", "amd64");
        assertRejected(script, "Mac OS X", "amd64");
        assertRejected(script, "Windows 11", "amd64");
    }

    @Test
    void rejectsWrongArchitecture() {
        assertRejected(
                UpdateChannelFixture.executable("linux", "arm64"),
                "Linux",
                "amd64"
        );
        assertRejected(
                UpdateChannelFixture.executable("linux", "amd64"),
                "Linux",
                "arm64"
        );
        assertRejected(
                UpdateChannelFixture.executable("darwin", "arm64"),
                "Mac OS X",
                "amd64"
        );
        assertRejected(
                UpdateChannelFixture.executable("darwin", "amd64"),
                "Mac OS X",
                "arm64"
        );
        byte[] pe = UpdateChannelFixture.executable(
                "windows",
                "amd64"
        );
        pe[132] = 0x4c;
        pe[133] = 0x01;
        assertRejected(pe, "Windows 11", "amd64");
    }

    @Test
    void rejectsWrongOperatingSystem() {
        assertRejected(
                UpdateChannelFixture.executable("darwin", "amd64"),
                "Linux",
                "amd64"
        );
        assertRejected(
                UpdateChannelFixture.executable("windows", "amd64"),
                "Mac OS X",
                "amd64"
        );
        assertRejected(
                UpdateChannelFixture.executable("linux", "amd64"),
                "Windows 11",
                "amd64"
        );
    }

    @Test
    void rejectsNonCanonicalElfAndMachOHeaders() {
        byte[] elf32 = UpdateChannelFixture.executable(
                "linux",
                "amd64"
        );
        elf32[4] = 1;
        assertRejected(elf32, "Linux", "amd64");

        byte[] bigEndianElf = UpdateChannelFixture.executable(
                "linux",
                "amd64"
        );
        bigEndianElf[5] = 2;
        assertRejected(bigEndianElf, "Linux", "amd64");

        byte[] swappedMachMagic = UpdateChannelFixture.executable(
                "darwin",
                "amd64"
        );
        swappedMachMagic[0] = (byte) 0xfe;
        swappedMachMagic[1] = (byte) 0xed;
        swappedMachMagic[2] = (byte) 0xfa;
        swappedMachMagic[3] = (byte) 0xcf;
        assertRejected(swappedMachMagic, "Mac OS X", "amd64");
    }

    @Test
    void rejectsUnsafePeOffsetsAndSignatures() {
        byte[] belowDosHeader = UpdateChannelFixture.executable(
                "windows",
                "amd64"
        );
        putLittleEndian32(belowDosHeader, 60, 63);
        assertRejected(
                belowDosHeader,
                "Windows 11",
                "amd64"
        );

        byte[] beyondFile = UpdateChannelFixture.executable(
                "windows",
                "amd64"
        );
        putLittleEndian32(beyondFile, 60, 251);
        assertRejected(beyondFile, "Windows 11", "amd64");

        byte[] unsignedOverflow = UpdateChannelFixture.executable(
                "windows",
                "amd64"
        );
        Arrays.fill(
                unsignedOverflow,
                60,
                64,
                (byte) 0xff
        );
        assertRejected(unsignedOverflow, "Windows 11", "amd64");

        byte[] badSignature = UpdateChannelFixture.executable(
                "windows",
                "amd64"
        );
        badSignature[128] = 'N';
        assertRejected(badSignature, "Windows 11", "amd64");
    }

    private void verify(
            byte[] bytes,
            String os,
            String arch
    ) throws Exception {
        Path path = temporaryDirectory.resolve(
                os.replace(' ', '-') + "-" + arch + "-"
                        + System.nanoTime()
        );
        Files.write(path, bytes);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ
        )) {
            ExecutableHeaderValidator.verify(
                    channel,
                    bytes.length,
                    PlatformIdentity.from(os, arch)
            );
        }
    }

    private void assertRejected(
            byte[] bytes,
            String os,
            String arch
    ) {
        assertThrows(
                CliDistributionException.class,
                () -> verify(bytes, os, arch)
        );
    }

    private static void putLittleEndian32(
            byte[] bytes,
            int offset,
            int value
    ) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }
}
