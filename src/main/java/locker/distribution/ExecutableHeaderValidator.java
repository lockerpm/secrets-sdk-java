package locker.distribution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Binds a verified artifact to its declared operating system and CPU.
 *
 * <p>The parser performs only fixed-size positional reads. It never maps or
 * loads the executable body and it does not trust filename extensions.
 */
final class ExecutableHeaderValidator {
    private static final int ELF_HEADER_BYTES = 20;
    private static final int MACH_O_HEADER_BYTES = 8;
    private static final int DOS_HEADER_BYTES = 64;
    private static final int PE_PREFIX_BYTES = 6;

    private static final int ELF_CLASS_64 = 2;
    private static final int ELF_DATA_LITTLE_ENDIAN = 1;
    private static final int ELF_MACHINE_AMD64 = 0x3e;
    private static final int ELF_MACHINE_ARM64 = 0xb7;

    private static final int MACH_O_CPU_AMD64 = 0x01000007;
    private static final int MACH_O_CPU_ARM64 = 0x0100000c;

    private static final int PE_MACHINE_AMD64 = 0x8664;

    private ExecutableHeaderValidator() {
    }

    static void verify(
            FileChannel channel,
            long fileSize,
            PlatformIdentity platform
    ) throws IOException, CliDistributionException {
        if (channel == null
                || platform == null
                || fileSize < 1) {
            throw invalid("Locker CLI executable header is invalid");
        }
        switch (platform.getOs()) {
            case "linux":
                verifyElf(channel, fileSize, platform.getArch());
                return;
            case "darwin":
                verifyMachO(channel, fileSize, platform.getArch());
                return;
            case "windows":
                verifyPe(channel, fileSize, platform.getArch());
                return;
            default:
                throw invalid(
                        "Locker CLI executable operating system is invalid"
                );
        }
    }

    private static void verifyElf(
            FileChannel channel,
            long fileSize,
            String architecture
    ) throws IOException, CliDistributionException {
        byte[] header = readExact(
                channel,
                fileSize,
                0,
                ELF_HEADER_BYTES,
                "ELF"
        );
        int expectedMachine;
        if ("amd64".equals(architecture)) {
            expectedMachine = ELF_MACHINE_AMD64;
        } else if ("arm64".equals(architecture)) {
            expectedMachine = ELF_MACHINE_ARM64;
        } else {
            throw invalid("Locker CLI ELF architecture is unsupported");
        }
        if ((header[0] & 0xff) != 0x7f
                || header[1] != 'E'
                || header[2] != 'L'
                || header[3] != 'F'
                || (header[4] & 0xff) != ELF_CLASS_64
                || (header[5] & 0xff)
                != ELF_DATA_LITTLE_ENDIAN) {
            throw invalid(
                    "Locker CLI is not a canonical 64-bit "
                            + "little-endian ELF executable"
            );
        }
        int machine = unsignedLittleEndian16(header, 18);
        if (machine != expectedMachine) {
            throw invalid(
                    "Locker CLI ELF architecture does not match "
                            + "the signed target"
            );
        }
    }

    private static void verifyMachO(
            FileChannel channel,
            long fileSize,
            String architecture
    ) throws IOException, CliDistributionException {
        byte[] header = readExact(
                channel,
                fileSize,
                0,
                MACH_O_HEADER_BYTES,
                "Mach-O"
        );
        int expectedCpu;
        if ("amd64".equals(architecture)) {
            expectedCpu = MACH_O_CPU_AMD64;
        } else if ("arm64".equals(architecture)) {
            expectedCpu = MACH_O_CPU_ARM64;
        } else {
            throw invalid("Locker CLI Mach-O architecture is unsupported");
        }
        if ((header[0] & 0xff) != 0xcf
                || (header[1] & 0xff) != 0xfa
                || (header[2] & 0xff) != 0xed
                || (header[3] & 0xff) != 0xfe) {
            throw invalid(
                    "Locker CLI is not a canonical 64-bit "
                            + "little-endian Mach-O executable"
            );
        }
        int cpu = littleEndian32(header, 4);
        if (cpu != expectedCpu) {
            throw invalid(
                    "Locker CLI Mach-O architecture does not match "
                            + "the signed target"
            );
        }
    }

    private static void verifyPe(
            FileChannel channel,
            long fileSize,
            String architecture
    ) throws IOException, CliDistributionException {
        if (!"amd64".equals(architecture)) {
            throw invalid("Locker CLI PE architecture is unsupported");
        }
        byte[] dosHeader = readExact(
                channel,
                fileSize,
                0,
                DOS_HEADER_BYTES,
                "DOS"
        );
        if (dosHeader[0] != 'M' || dosHeader[1] != 'Z') {
            throw invalid("Locker CLI is not a PE executable");
        }
        long peOffset = unsignedLittleEndian32(dosHeader, 60);
        if (peOffset < DOS_HEADER_BYTES
                || peOffset > fileSize - PE_PREFIX_BYTES) {
            throw invalid(
                    "Locker CLI PE header offset is outside "
                            + "the executable"
            );
        }
        byte[] peHeader = readExact(
                channel,
                fileSize,
                peOffset,
                PE_PREFIX_BYTES,
                "PE"
        );
        if (peHeader[0] != 'P'
                || peHeader[1] != 'E'
                || peHeader[2] != 0
                || peHeader[3] != 0) {
            throw invalid("Locker CLI PE signature is invalid");
        }
        if (unsignedLittleEndian16(peHeader, 4)
                != PE_MACHINE_AMD64) {
            throw invalid(
                    "Locker CLI PE architecture does not match "
                            + "the signed target"
            );
        }
    }

    private static byte[] readExact(
            FileChannel channel,
            long fileSize,
            long offset,
            int length,
            String label
    ) throws IOException, CliDistributionException {
        if (offset < 0
                || length < 1
                || offset > fileSize - length) {
            throw invalid(
                    "Locker CLI " + label + " header is truncated"
            );
        }
        byte[] bytes = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read <= 0) {
                throw invalid(
                        "Locker CLI " + label + " header is truncated"
                );
            }
            position += read;
        }
        return bytes;
    }

    private static int unsignedLittleEndian16(
            byte[] bytes,
            int offset
    ) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int littleEndian32(
            byte[] bytes,
            int offset
    ) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | (bytes[offset + 3] << 24);
    }

    private static long unsignedLittleEndian32(
            byte[] bytes,
            int offset
    ) {
        return Integer.toUnsignedLong(littleEndian32(bytes, offset));
    }

    private static CliDistributionException invalid(String message) {
        return new CliDistributionException(message);
    }
}
