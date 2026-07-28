package locker.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class LockerCliInstallerV2Test {
    private static final long START_TIME = 1_800_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    public void firstUseInstallsEntireVerifiedV2Chain()
            throws Exception {
        TestContext context = context("2.0.7");

        Path installed = context.installer.resolve();

        Path generations = managedRoot(temporaryDirectory)
                .resolve("generations");
        assertEquals(
                generations,
                installed.getParent().getParent()
        );
        assertTrue(
                installed.getParent().getFileName().toString().matches(
                        "g-[0-9a-f]{64}-[0-9a-f]{32}"
                )
        );
        assertEquals("locker", installed.getFileName().toString());
        assertArrayEquals(
                context.fixture.binary,
                Files.readAllBytes(installed)
        );
        assertEquals(4, context.transport.requestCount());
        assertEquals(
                List.of(
                        "latest.json",
                        "2.0.7/manifest.json",
                        "2.0.7/locker-linux-amd64.sig",
                        "2.0.7/locker-linux-amd64"
                ),
                relativeRequests(context.transport.requests())
        );
    }

    @Test
    public void persistedSixHourStateSuppressesNetworkChecks()
            throws Exception {
        TestContext context = context("2.0.7");
        Path first = context.installer.resolve();

        context.clock.addAndGet((6 * 60 * 60) - 1);
        Path second = context.installer.resolve();

        assertEquals(first, second);
        assertEquals(4, context.transport.requestCount());
    }

    @Test
    public void dueCheckReverifiesLatestAndManifestWithoutRedownload()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();

        context.clock.addAndGet(6 * 60 * 60);
        context.installer.resolve();

        assertEquals(6, context.transport.requestCount());
        assertEquals(
                List.of(
                        "latest.json",
                        "2.0.7/manifest.json"
                ),
                relativeRequests(
                        context.transport.requests().subList(4, 6)
                )
        );
    }

    @Test
    public void transientNetworkFailureUsesOnlyVerifiedCache()
            throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        context.transport.setUnavailable(true);

        Path fallback = context.installer.resolve();

        assertEquals(installed, fallback);
        assertArrayEquals(
                context.fixture.binary,
                Files.readAllBytes(fallback)
        );
    }

    @Test
    public void transientFallbackUsesBoundedRetryBackoff()
            throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        context.transport.setUnavailable(true);

        assertEquals(installed, context.installer.resolve());
        assertEquals(5, context.transport.requestCount());
        assertEquals(installed, context.installer.resolve());
        assertEquals(5, context.transport.requestCount());

        context.clock.addAndGet(60);
        assertEquals(installed, context.installer.resolve());
        assertEquals(6, context.transport.requestCount());
    }

    @Test
    public void tlsFailureNeverUsesVerifiedCache()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        LockerCliInstaller installer = new LockerCliInstaller(
                (uri, maximumBytes) -> {
                    throw new SSLHandshakeException(
                            "fixture certificate rejected"
                    );
                },
                context.clock::get,
                temporaryDirectory,
                PlatformIdentity.from("linux", "amd64"),
                context.fixture.publicKey
        );

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                installer::resolve
        );

        assertTrue(failure.getMessage().contains("TLS"));
    }

    @Test
    public void dnsFailureRemainsTransient()
            throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        LockerCliInstaller installer = new LockerCliInstaller(
                (uri, maximumBytes) -> {
                    throw new UnknownHostException(
                            "fixture host unavailable"
                    );
                },
                context.clock::get,
                temporaryDirectory,
                PlatformIdentity.from("linux", "amd64"),
                context.fixture.publicKey
        );

        assertEquals(installed, installer.resolve());
    }

    @Test
    public void tlsFailureDuringBodyReadNeverFallsBack()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        LockerCliInstaller installer = new LockerCliInstaller(
                (uri, maximumBytes) ->
                        new LockerCliInstaller.DownloadedResponse(
                                200,
                                Optional.empty(),
                                new java.io.InputStream() {
                                    @Override
                                    public int read()
                                            throws IOException {
                                        throw new SSLHandshakeException(
                                                "fixture TLS body failure"
                                        );
                                    }
                                }
                        ),
                context.clock::get,
                temporaryDirectory,
                PlatformIdentity.from("linux", "amd64"),
                context.fixture.publicKey
        );

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                installer::resolve
        );

        assertTrue(failure.getMessage().contains("TLS"));
    }

    @Test
    public void transientManifestFailureUsesVerifiedCache()
            throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        URI manifest = uri("2.0.7/manifest.json");
        context.transport.put(manifest, 503, new byte[]{'x'});

        assertEquals(installed, context.installer.resolve());
    }

    @Test
    public void signedMetadataIntegrityFailureNeverFallsBack()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        byte[] corrupt = context.fixture.latestEnvelope.clone();
        corrupt[corrupt.length / 2] ^= 1;
        context.transport.put(
                uri("latest.json"),
                200,
                corrupt
        );

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
    }

    @Test
    public void unexpectedHttpStatusNeverFallsBack()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        context.transport.put(
                uri("latest.json"),
                404,
                new byte[]{'x'}
        );

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
    }

    @Test
    public void nonHttpSixHundredStatusNeverFallsBack()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        context.transport.put(
                uri("latest.json"),
                600,
                new byte[]{'x'}
        );

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
    }

    @Test
    public void responseLifecycleFailureNeverFallsBack()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        LockerCliInstaller.DownloadTransport transport =
                (uri, maximumBytes) -> {
                    byte[] bytes = context.fixture.objects.get(uri);
                    return new LockerCliInstaller.DownloadedResponse(
                            200,
                            Optional.of((long) bytes.length),
                            new ByteArrayInputStream(bytes) {
                                @Override
                                public void close() throws IOException {
                                    super.close();
                                    throw new IOException(
                                            "fixture close failure"
                                    );
                                }
                            }
                    );
                };
        LockerCliInstaller installer = new LockerCliInstaller(
                transport,
                context.clock::get,
                temporaryDirectory,
                PlatformIdentity.from("linux", "amd64"),
                context.fixture.publicKey
        );

        assertThrows(
                CliDistributionException.class,
                installer::resolve
        );
    }

    @Test
    public void invalidArtifactSignatureAndHashFailClosed()
            throws Exception {
        TestContext badSignature = contextIn(
                temporaryDirectory.resolve("bad-signature"),
                "2.0.7"
        );
        byte[] signature = badSignature.fixture
                .binarySignature
                .clone();
        signature[0] ^= 1;
        badSignature.transport.put(
                uri("2.0.7/locker-linux-amd64.sig"),
                200,
                signature
        );
        assertThrows(
                CliDistributionException.class,
                badSignature.installer::resolve
        );

        TestContext badBinary = contextIn(
                temporaryDirectory.resolve("bad-binary"),
                "2.0.7"
        );
        badBinary.transport.put(
                uri("2.0.7/locker-linux-amd64"),
                200,
                "wrong-binary".getBytes(StandardCharsets.US_ASCII)
        );
        assertThrows(
                CliDistributionException.class,
                badBinary.installer::resolve
        );
    }

    @Test
    public void executableHeaderIsCheckedBeforePublication()
            throws Exception {
        Path home = temporaryDirectory.resolve("invalid-header-download");
        Files.createDirectories(home);
        byte[] script = "#!/bin/sh\nexit 0\n".getBytes(
                StandardCharsets.US_ASCII
        );
        UpdateChannelFixture fixture =
                UpdateChannelFixture.create("2.0.7", script);
        UpdateChannelFixture.FakeTransport transport =
                new UpdateChannelFixture.FakeTransport(fixture);
        LockerCliInstaller installer = new LockerCliInstaller(
                transport,
                () -> START_TIME,
                home,
                PlatformIdentity.from("linux", "amd64"),
                fixture.publicKey
        );

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                installer::resolve
        );

        assertTrue(
                failure.getMessage().contains("ELF"),
                failure::getMessage
        );
        assertTrue(
                Files.notExists(
                        managedRoot(home).resolve(
                                "locker.current.json"
                        )
                )
        );
    }

    @Test
    public void executableHeaderIsCheckedOnEveryCachedReverify()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        byte[] script = "#!/bin/sh\nexit 0\n".getBytes(
                StandardCharsets.US_ASCII
        );
        UpdateChannelFixture invalid =
                UpdateChannelFixture.create("2.0.7", script);
        publishTestGeneration(
                temporaryDirectory,
                invalid
        );
        context.transport.setUnavailable(true);

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );

        assertTrue(
                failure.getMessage().contains("ELF"),
                failure::getMessage
        );
    }

    @Test
    public void cachedBinaryTamperCannotUseOfflineFallback()
            throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        Files.writeString(
                installed,
                "tampered",
                StandardCharsets.US_ASCII
        );
        context.clock.addAndGet(6 * 60 * 60);
        context.transport.setUnavailable(true);

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
    }

    @Test
    public void rejectsSignedDowngradeFromAcceptedVersion()
            throws Exception {
        TestContext context = context("2.0.8");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        UpdateChannelFixture older =
                UpdateChannelFixture.create("2.0.7");
        context.transport.replace(older);

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );

        assertTrue(failure.getMessage().contains("downgrade"));
    }

    @Test
    public void persistedStatePreventsDowngradeAfterCacheLoss()
            throws Exception {
        TestContext context = context("2.0.8");
        Path installed = context.installer.resolve();
        Files.delete(installed);
        Files.delete(installed.resolveSibling("locker.sig"));
        Files.delete(installed.resolveSibling("locker.latest.json"));
        Files.delete(installed.resolveSibling("locker.manifest.json"));
        context.clock.addAndGet(6 * 60 * 60);
        context.transport.replace(
                UpdateChannelFixture.create("2.0.7")
        );

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );

        assertTrue(failure.getMessage().contains("downgrade"));
        assertTrue(Files.notExists(installed));
    }

    @Test
    public void recoversWhenVerifiedCacheWasPublishedBeforeNewState()
            throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        UpdateChannelFixture newer =
                UpdateChannelFixture.create("2.0.8");
        context.transport.replace(newer);
        Path newerInstalled = context.installer.resolve();

        Files.writeString(
                managedRoot(temporaryDirectory).resolve(
                        "locker.update-state.json"
                ),
                stateJson(
                        context.fixture,
                        START_TIME,
                        0
                ),
                StandardCharsets.US_ASCII
        );

        assertNotEquals(installed, newerInstalled);
        assertEquals(newerInstalled, context.installer.resolve());
        String repairedState = Files.readString(
                managedRoot(temporaryDirectory).resolve(
                        "locker.update-state.json"
                ),
                StandardCharsets.US_ASCII
        );
        assertTrue(repairedState.contains("\"version\":\"2.0.8\""));
    }

    @Test
    public void sameVersionLatestEquivocationFailsClosed()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        UpdateChannelFixture differentBytes =
                UpdateChannelFixture.create(
                        "2.0.7",
                        "different-valid-signed-binary".getBytes(
                                StandardCharsets.US_ASCII
                        )
                );
        context.transport.replace(differentBytes);

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
        assertTrue(
                failure.getMessage().contains("accepted")
        );
        assertEquals(5, context.transport.requestCount());
    }

    @Test
    public void acceptedLatestPersistsBeforeManifestDownload()
            throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        context.clock.addAndGet(6 * 60 * 60);
        UpdateChannelFixture accepted =
                UpdateChannelFixture.create("2.0.8");
        context.transport.replace(accepted);
        context.transport.put(
                uri("2.0.8/manifest.json"),
                503,
                new byte[]{'x'}
        );

        assertEquals(installed, context.installer.resolve());
        String state = Files.readString(
                managedRoot(temporaryDirectory).resolve(
                        "locker.update-state.json"
                ),
                StandardCharsets.US_ASCII
        );
        assertTrue(state.contains("\"version\":\"2.0.8\""));
        SignedUpdateContract.Latest acceptedLatest =
                SignedUpdateContract.verifyLatest(
                        accepted.latestEnvelope,
                        accepted.publicKey
                );
        assertTrue(state.contains(
                "\"source_commit\":\""
                        + acceptedLatest.getSourceCommit()
                        + "\""
        ));
        assertTrue(state.contains(
                "\"manifest_sha256\":\""
                        + acceptedLatest.getManifestSha256()
                        + "\""
        ));
        assertTrue(state.contains(
                "\"manifest_size\":"
                        + acceptedLatest.getManifestSize()
        ));
        assertTrue(!state.contains("\"latest_sha256\""));

        context.clock.addAndGet(60);
        context.transport.replace(
                UpdateChannelFixture.create("2.0.7")
        );
        assertTrue(assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        ).getMessage().contains("downgrade"));

        byte[] changedBinary = UpdateChannelFixture.executable(
                "linux",
                "amd64"
        );
        changedBinary[200] = 42;
        context.transport.replace(
                UpdateChannelFixture.create(
                        "2.0.8",
                        changedBinary
                )
        );
        assertTrue(assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        ).getMessage().contains("accepted"));
    }

    @Test
    public void manifestSizeParticipatesInAcceptedHighWaterTuple()
            throws Exception {
        TestContext context = context("2.0.7");
        context.installer.resolve();
        Path state = managedRoot(temporaryDirectory).resolve(
                "locker.update-state.json"
        );
        Files.writeString(
                state,
                stateJson(
                        context.fixture,
                        context.fixture.manifestEnvelope.length + 1L,
                        0,
                        0
                ),
                StandardCharsets.US_ASCII
        );
        Files.delete(
                managedRoot(temporaryDirectory).resolve(
                        "locker.current.json"
                )
        );

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );

        assertTrue(failure.getMessage().contains("accepted"));
        assertEquals(5, context.transport.requestCount());
    }

    @Test
    public void everyPublicationBoundaryRecoversOffline()
            throws Exception {
        List<LockerCliInstaller.PublicationBoundary> boundaries =
                List.of(
                        LockerCliInstaller.PublicationBoundary
                                .GENERATION_BINARY_PUBLISHED,
                        LockerCliInstaller.PublicationBoundary
                                .GENERATION_SIGNATURE_PUBLISHED,
                        LockerCliInstaller.PublicationBoundary
                                .GENERATION_MANIFEST_PUBLISHED,
                        LockerCliInstaller.PublicationBoundary
                                .GENERATION_LATEST_PUBLISHED,
                        LockerCliInstaller.PublicationBoundary
                                .GENERATION_DIRECTORY_PUBLISHED,
                        LockerCliInstaller.PublicationBoundary
                                .CURRENT_POINTER_PUBLISHED,
                        LockerCliInstaller.PublicationBoundary
                                .UPDATE_STATE_PUBLISHED
                );
        for (LockerCliInstaller.PublicationBoundary boundary
                : boundaries) {
            Path home = temporaryDirectory.resolve(
                    boundary.name().toLowerCase()
            );
            byte[] oldBinary = UpdateChannelFixture.executable(
                    "linux",
                    "amd64"
            );
            oldBinary[200] = 7;
            byte[] newBinary = UpdateChannelFixture.executable(
                    "linux",
                    "amd64"
            );
            newBinary[200] = 8;
            UpdateChannelFixture oldFixture =
                    UpdateChannelFixture.create(
                            "2.0.7",
                            oldBinary
                    );
            UpdateChannelFixture newFixture =
                    UpdateChannelFixture.create(
                            "2.0.8",
                            newBinary
                    );
            TestContext context = contextIn(home, oldFixture);
            Path oldPath = context.installer.resolve();
            context.clock.addAndGet(6 * 60 * 60);
            context.transport.replace(newFixture);
            AtomicInteger injections = new AtomicInteger();
            LockerCliInstaller interrupted =
                    new LockerCliInstaller(
                            context.transport,
                            context.clock::get,
                            home,
                            PlatformIdentity.from(
                                    "linux",
                                    "amd64"
                            ),
                            oldFixture.publicKey,
                            observed -> {
                                if (observed == boundary
                                        && injections
                                        .getAndIncrement() == 0) {
                                    throw new CliDistributionException(
                                            "fixture publication crash"
                                    );
                                }
                            }
                    );

            assertThrows(
                    CliDistributionException.class,
                    interrupted::resolve,
                    boundary.name()
            );
            context.transport.setUnavailable(true);
            LockerCliInstaller recovery =
                    new LockerCliInstaller(
                            context.transport,
                            context.clock::get,
                            home,
                            PlatformIdentity.from(
                                    "linux",
                                    "amd64"
                            ),
                            oldFixture.publicKey
                    );
            Path recovered = recovery.resolve();
            boolean pointerWasPublished =
                    boundary == LockerCliInstaller
                            .PublicationBoundary
                            .CURRENT_POINTER_PUBLISHED
                            || boundary == LockerCliInstaller
                            .PublicationBoundary
                            .UPDATE_STATE_PUBLISHED;
            if (pointerWasPublished) {
                assertNotEquals(
                        oldPath,
                        recovered,
                        boundary.name()
                );
                assertArrayEquals(
                        newFixture.binary,
                        Files.readAllBytes(recovered),
                        boundary.name()
                );
            } else {
                assertEquals(
                        oldPath,
                        recovered,
                        boundary.name()
                );
                assertArrayEquals(
                        oldFixture.binary,
                        Files.readAllBytes(recovered),
                        boundary.name()
                );
            }
        }
    }

    @Test
    public void explicitAndEnvironmentPathsBypassManagedUpdater()
            throws Exception {
        TestContext context = context("2.0.7");
        Path callerBinary = temporaryDirectory.resolve(
                isWindows() ? "caller.exe" : "caller"
        );
        Files.writeString(
                callerBinary,
                "caller-owned",
                StandardCharsets.US_ASCII
        );
        callerBinary.toFile().setExecutable(true, true);

        String explicit = LockerCliResolver.resolve(
                callerBinary.toString(),
                null,
                context.installer
        );
        String environment = LockerCliResolver.resolve(
                null,
                callerBinary.toString(),
                context.installer
        );

        assertEquals(
                callerBinary.toRealPath().toString(),
                explicit
        );
        assertEquals(callerBinary.toRealPath().toString(), environment);
        assertEquals(0, context.transport.requestCount());
        assertTrue(
                Files.notExists(
                        temporaryDirectory.resolve(".locker")
                )
        );
    }

    @Test
    public void bareCommandOverrideDoesNotUseAmbientPath()
            throws Exception {
        TestContext context = context("2.0.7");

        assertThrows(
                CliDistributionException.class,
                () -> LockerCliResolver.resolve(
                        "locker-fixture-that-does-not-exist",
                        null,
                        context.installer
                )
        );
        assertEquals(0, context.transport.requestCount());
    }

    @Test
    public void relativeExplicitPathIsRejectedBeforeFilesystemFallback()
            throws Exception {
        TestContext context = context("2.0.7");

        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                () -> LockerCliResolver.resolve(
                        "pom.xml",
                        null,
                        context.installer
                )
        );

        assertTrue(failure.getMessage().contains("absolute"));
        assertEquals(0, context.transport.requestCount());
    }

    @Test
    public void sharedCacheAncestorSymlinkFailsBeforeNetwork()
            throws Exception {
        Path redirected = temporaryDirectory.resolve("redirected");
        Files.createDirectory(redirected);
        try {
            Files.createSymbolicLink(
                    temporaryDirectory.resolve(".locker"),
                    redirected
            );
        } catch (IOException
                 | UnsupportedOperationException
                 | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception);
        }
        TestContext context = context("2.0.7");

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
        assertEquals(0, context.transport.requestCount());
    }

    @Test
    public void writableSharedCacheAncestorFailsBeforeNetwork()
            throws Exception {
        Path locker = temporaryDirectory.resolve(".locker");
        Files.createDirectory(locker);
        PosixFileAttributeView posix = Files.getFileAttributeView(
                locker,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        assumeTrue(posix != null, "POSIX permissions are unavailable");
        Files.setPosixFilePermissions(
                locker,
                PosixFilePermissions.fromString("rwxrwxrwx")
        );
        TestContext context = context("2.0.7");

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
        assertEquals(0, context.transport.requestCount());
    }

    @Test
    public void sharedCacheAncestorWithForeignAclFailsBeforeNetwork()
            throws Exception {
        Path locker = temporaryDirectory.resolve(".locker");
        Files.createDirectory(locker);
        AclFileAttributeView acl = Files.getFileAttributeView(
                locker,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        assumeTrue(acl != null, "ACL permissions are unavailable");
        java.nio.file.attribute.UserPrincipal aclOwner =
                acl.getOwner();
        AclEntry foreign = acl.getAcl().stream()
                .filter(entry -> entry.type() == AclEntryType.ALLOW)
                .filter(entry -> !aclOwner.equals(entry.principal()))
                .findFirst()
                .orElse(null);
        assumeTrue(foreign != null, "No foreign ACL principal is available");
        AclEntry owner = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(aclOwner)
                .setPermissions(
                        EnumSet.allOf(AclEntryPermission.class)
                )
                .build();
        try {
            acl.setAcl(List.of(owner, foreign));
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "ACL mutation is unavailable: " + exception);
        }
        TestContext context = context("2.0.7");

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
        assertEquals(0, context.transport.requestCount());
    }

    @Test
    public void blankInjectedTrustFailsBeforeNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        LockerCliInstaller installer = new LockerCliInstaller(
                (uri, maximumBytes) -> {
                    requests.incrementAndGet();
                    throw new IOException("network must not be reached");
                },
                () -> START_TIME,
                temporaryDirectory,
                PlatformIdentity.from("linux", "amd64"),
                new byte[0]
        );
        CliDistributionException failure = assertThrows(
                CliDistributionException.class,
                installer::resolve
        );

        assertTrue(
                failure.getMessage().contains("public key")
        );
        assertEquals(0, requests.get());
        assertTrue(Files.notExists(managedRoot(temporaryDirectory)));
    }

    @Test
    public void malformedFutureStateFailsClosed() throws Exception {
        TestContext context = context("2.0.7");
        Path installed = context.installer.resolve();
        Path state = managedRoot(temporaryDirectory).resolve(
                "locker.update-state.json"
        );
        Files.writeString(
                state,
                stateJson(
                        context.fixture,
                        START_TIME + 1,
                        0
                ),
                StandardCharsets.US_ASCII
        );

        assertThrows(
                CliDistributionException.class,
                context.installer::resolve
        );
    }

    @Test
    public void nativeLockSerializesConcurrentFirstUse()
            throws Exception {
        UpdateChannelFixture fixture =
                UpdateChannelFixture.create("2.0.7");
        UpdateChannelFixture.FakeTransport transport =
                new UpdateChannelFixture.FakeTransport(fixture);
        AtomicLong clock = new AtomicLong(START_TIME);
        PlatformIdentity platform = PlatformIdentity.from(
                "linux",
                "amd64"
        );
        LockerCliInstaller first = new LockerCliInstaller(
                transport,
                clock::get,
                temporaryDirectory,
                platform,
                fixture.publicKey
        );
        LockerCliInstaller second = new LockerCliInstaller(
                transport,
                clock::get,
                temporaryDirectory,
                platform,
                fixture.publicKey
        );
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Path> one = workers.submit(first::resolve);
            Future<Path> two = workers.submit(second::resolve);

            assertEquals(
                    one.get(10, TimeUnit.SECONDS),
                    two.get(10, TimeUnit.SECONDS)
            );
            assertEquals(4, transport.requestCount());
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private TestContext context(String version) throws Exception {
        return contextIn(temporaryDirectory, version);
    }

    private static TestContext contextIn(
            Path home,
            String version
    ) throws Exception {
        Files.createDirectories(home);
        UpdateChannelFixture fixture =
                UpdateChannelFixture.create(version);
        return contextIn(home, fixture);
    }

    private static TestContext contextIn(
            Path home,
            UpdateChannelFixture fixture
    ) throws Exception {
        Files.createDirectories(home);
        UpdateChannelFixture.FakeTransport transport =
                new UpdateChannelFixture.FakeTransport(fixture);
        AtomicLong clock = new AtomicLong(START_TIME);
        LockerCliInstaller installer = new LockerCliInstaller(
                transport,
                clock::get,
                home,
                PlatformIdentity.from("linux", "amd64"),
                fixture.publicKey
        );
        return new TestContext(
                fixture,
                transport,
                clock,
                installer
        );
    }

    private static Path managedRoot(Path home) {
        return home.toAbsolutePath()
                .normalize()
                .resolve(".locker")
                .resolve("sdk-cli")
                .resolve("java");
    }

    private static String stateJson(
            UpdateChannelFixture fixture,
            long lastSuccessfulCheck,
            long retryAfter
    ) throws Exception {
        return stateJson(
                fixture,
                fixture.manifestEnvelope.length,
                lastSuccessfulCheck,
                retryAfter
        );
    }

    private static String stateJson(
            UpdateChannelFixture fixture,
            long manifestSize,
            long lastSuccessfulCheck,
            long retryAfter
    ) throws Exception {
        SignedUpdateContract.Latest latest =
                SignedUpdateContract.verifyLatest(
                        fixture.latestEnvelope,
                        fixture.publicKey
                );
        return "{"
                + "\"last_successful_check_epoch_seconds\":"
                + lastSuccessfulCheck
                + ",\"manifest_sha256\":\""
                + latest.getManifestSha256()
                + "\",\"manifest_size\":"
                + manifestSize
                + ",\"product\":\"locker-cli-java-updater\","
                + "\"retry_after_epoch_seconds\":"
                + retryAfter
                + ",\"schema_version\":2,"
                + "\"source_commit\":\""
                + latest.getSourceCommit()
                + "\",\"version\":\""
                + latest.getVersion()
                + "\""
                + "}\n";
    }

    private static Path publishTestGeneration(
            Path home,
            UpdateChannelFixture fixture
    ) throws Exception {
        Path root = managedRoot(home);
        String digest = SignedUpdateContract.sha256(
                fixture.latestEnvelope
        );
        String generation = "g-" + digest + "-" + "0".repeat(32);
        Path directory = root.resolve("generations")
                .resolve(generation);
        Files.createDirectories(directory);
        Path binary = directory.resolve("locker");
        Files.write(binary, fixture.binary);
        binary.toFile().setExecutable(true, true);
        Files.write(
                directory.resolve("locker.sig"),
                fixture.binarySignature
        );
        Files.write(
                directory.resolve("locker.latest.json"),
                fixture.latestEnvelope
        );
        Files.write(
                directory.resolve("locker.manifest.json"),
                fixture.manifestEnvelope
        );
        Files.writeString(
                root.resolve("locker.current.json"),
                "{"
                        + "\"generation\":\"" + generation + "\","
                        + "\"latest_sha256\":\"" + digest + "\","
                        + "\"product\":\"locker-cli-java-updater\","
                        + "\"schema_version\":2,"
                        + "\"version\":\""
                        + fixture.version
                        + "\""
                        + "}\n",
                StandardCharsets.US_ASCII
        );
        secureTestPath(directory, true, false);
        secureTestPath(binary, false, true);
        secureTestPath(
                directory.resolve("locker.sig"),
                false,
                false
        );
        secureTestPath(
                directory.resolve("locker.latest.json"),
                false,
                false
        );
        secureTestPath(
                directory.resolve("locker.manifest.json"),
                false,
                false
        );
        secureTestPath(
                root.resolve("locker.current.json"),
                false,
                false
        );
        return binary;
    }

    private static void secureTestPath(
            Path path,
            boolean directory,
            boolean executable
    ) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (posix != null) {
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(
                            directory || executable
                                    ? "rwx------"
                                    : "rw-------"
                    )
            );
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        AclEntry owner = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(
                        EnumSet.allOf(AclEntryPermission.class)
                )
                .build();
        acl.setAcl(Collections.singletonList(owner));
    }

    private static List<String> relativeRequests(List<URI> requests) {
        return requests.stream()
                .map(uri -> uri.toString().substring(
                        SignedUpdateContract.BASE_URL.length()
                ))
                .collect(java.util.stream.Collectors.toList());
    }

    private static URI uri(String relative) {
        return URI.create(SignedUpdateContract.BASE_URL + relative);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase()
                .contains("windows");
    }

    private static final class TestContext {
        private final UpdateChannelFixture fixture;
        private final UpdateChannelFixture.FakeTransport transport;
        private final AtomicLong clock;
        private final LockerCliInstaller installer;

        private TestContext(
                UpdateChannelFixture fixture,
                UpdateChannelFixture.FakeTransport transport,
                AtomicLong clock,
                LockerCliInstaller installer
        ) {
            this.fixture = fixture;
            this.transport = transport;
            this.clock = clock;
            this.installer = installer;
        }
    }
}
