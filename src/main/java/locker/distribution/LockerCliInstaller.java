package locker.distribution;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Resolves and updates the canonical managed Locker CLI from the signed
 * update-channel v2.
 *
 * <p>No network or filesystem work occurs during construction. The first
 * {@link #resolve()} call checks the signed stable channel; subsequent calls
 * check at most once per persisted six-hour interval. Only transient network
 * failures may use an already verified cache.
 */
public final class LockerCliInstaller {
    private static final String COMPILED_RELEASE_PUBLIC_KEY =
            "G2lcXttVEXeXdaCNb0mBMyXE6Llgw1vu9SDjFmk8d2s";
    private static final URI LATEST_URI = URI.create(
            SignedUpdateContract.BASE_URL + "latest.json"
    );
    private static final long CHECK_INTERVAL_SECONDS = 6L * 60L * 60L;
    private static final long RETRY_DELAY_SECONDS = 60L;
    private static final int MAX_STATE_BYTES = 4096;
    private static final int MAX_POINTER_BYTES = 4096;
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final long LOCK_RETRY_MILLIS = 50;
    private static final Set<String> STATE_FIELDS = Set.of(
            "last_successful_check_epoch_seconds",
            "manifest_sha256",
            "manifest_size",
            "product",
            "retry_after_epoch_seconds",
            "schema_version",
            "source_commit",
            "version"
    );
    private static final Set<String> POINTER_FIELDS = Set.of(
            "generation",
            "latest_sha256",
            "product",
            "schema_version",
            "version"
    );
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SOURCE_COMMIT_PATTERN =
            Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Pattern GENERATION_PATTERN =
            Pattern.compile("^g-[0-9a-f]{64}-[0-9a-f]{32}$");

    private final DownloadTransport transport;
    private final LongSupplier epochSeconds;
    private final Path userHome;
    private final PlatformIdentity platform;
    private final byte[] trustedPublicKey;
    private final PublicationObserver publicationObserver;
    private volatile String detachedSignatureBinding;

    /**
     * Creates a lazy production updater. This constructor performs no I/O.
     */
    public LockerCliInstaller() {
        this(
                new HttpDownloadTransport(),
                () -> System.currentTimeMillis() / 1000L,
                configuredUserHome(),
                currentPlatform(),
                null,
                boundary -> {
                }
        );
    }

    LockerCliInstaller(
            DownloadTransport transport,
            LongSupplier epochSeconds,
            Path userHome,
            PlatformIdentity platform,
            byte[] trustedPublicKey
    ) {
        this(
                transport,
                epochSeconds,
                userHome,
                platform,
                trustedPublicKey,
                boundary -> {
                }
        );
    }

    LockerCliInstaller(
            DownloadTransport transport,
            LongSupplier epochSeconds,
            Path userHome,
            PlatformIdentity platform,
            byte[] trustedPublicKey,
            PublicationObserver publicationObserver
    ) {
        this.transport = java.util.Objects.requireNonNull(
                transport,
                "transport"
        );
        this.epochSeconds = java.util.Objects.requireNonNull(
                epochSeconds,
                "epochSeconds"
        );
        this.userHome = java.util.Objects.requireNonNull(
                userHome,
                "userHome"
        );
        this.platform = java.util.Objects.requireNonNull(
                platform,
                "platform"
        );
        this.trustedPublicKey = trustedPublicKey == null
                ? null
                : trustedPublicKey.clone();
        this.publicationObserver = java.util.Objects.requireNonNull(
                publicationObserver,
                "publicationObserver"
        );
    }

    /**
     * Returns a fully verified managed binary, updating it when due.
     */
    public Path resolve() throws CliDistributionException {
        byte[] publicKey = loadPublicKey();
        try {
            Layout layout = prepareLayout(userHome, platform);
            LockHandle lockHandle = acquireLock(layout.lock);
            try {
                return resolveLocked(layout, publicKey);
            } finally {
                lockHandle.close();
            }
        } finally {
            Arrays.fill(publicKey, (byte) 0);
        }
    }

    /**
     * Compatibility-friendly explicit lifecycle alias for {@link #resolve()}.
     */
    public Path install() throws CliDistributionException {
        return resolve();
    }

    private Path resolveLocked(
            Layout layout,
            byte[] publicKey
    ) throws CliDistributionException {
        long now = epochSeconds.getAsLong();
        if (now < 0) {
            throw invalid("The system clock is invalid");
        }
        Optional<CheckState> state = readState(layout.state);
        if (state.isPresent()) {
            state.get().requireNotFuture(now);
        }

        GenerationPointer pointer = null;
        GenerationLayout currentGeneration = null;
        TrustedDocuments trusted = null;
        VerifiedCache cache = null;
        CliDistributionException cacheFailure = null;
        try {
            pointer = readPointer(layout).orElse(null);
            if (pointer != null) {
                currentGeneration = generationLayout(
                        layout,
                        pointer.generation
                );
                trusted = readTrustedDocuments(
                        currentGeneration,
                        publicKey
                );
                requirePointerBinding(pointer, trusted);
            }
        } catch (CliDistributionException exception) {
            cacheFailure = exception;
        }
        if (pointer != null
                && currentGeneration != null
                && trusted != null) {
            try {
                cache = verifyCachedBinary(
                        currentGeneration,
                        publicKey,
                        trusted,
                        pointer,
                        requiresDetachedSignature(pointer)
                );
            } catch (CliDistributionException exception) {
                // A published generation with authentic metadata but an
                // invalid executable/signature is an integrity incident, not
                // an update opportunity. Do not silently replace it in the
                // same execution path.
                throw new CliDistributionException(
                        "The active managed Locker CLI failed signed "
                                + "integrity verification",
                        exception
                );
            }
        }

        if (cache != null) {
            if (state.isPresent()) {
                state.get().requireCompatibleActiveGeneration(
                        cache.documents.latest,
                        "Managed Locker CLI update state conflicts "
                                + "with the current generation"
                );
                if (state.get().isFresh(
                        cache.documents.latest,
                        now
                )) {
                    return cache.binary;
                }
                if (state.get().shouldDelayRetry(now)) {
                    return cache.binary;
                }
            }
        }

        try {
            return refresh(
                    layout,
                    publicKey,
                    now,
                    trusted,
                    cache,
                    state.orElse(null)
            );
        } catch (TransientNetworkException exception) {
            if (cache != null) {
                Optional<CheckState> persisted =
                        readState(layout.state);
                CheckState retryState = persisted.isPresent()
                        ? persisted.get()
                        : CheckState.fromCache(cache);
                retryState.requireNotFuture(now);
                writeState(
                        layout.state,
                        retryState.withRetryAfter(now)
                );
                publicationObserver.reached(
                        PublicationBoundary.RETRY_STATE_PUBLISHED
                );
                return cache.binary;
            }
            if (cacheFailure != null) {
                cacheFailure.addSuppressed(exception);
                throw cacheFailure;
            }
            throw new CliDistributionException(
                    "The signed Locker CLI channel is temporarily "
                            + "unavailable and no verified cache exists",
                    exception
            );
        }
    }

    private Path refresh(
            Layout layout,
            byte[] publicKey,
            long now,
            TrustedDocuments trusted,
            VerifiedCache cache,
            CheckState state
    ) throws CliDistributionException, TransientNetworkException {
        byte[] latestBytes = downloadBytes(
                LATEST_URI,
                SignedUpdateContract.MAX_LATEST_BYTES,
                -1
        );
        byte[] manifestBytes = null;
        byte[] artifactSignature = null;
        Path binaryTemporary = null;
        try {
            SignedUpdateContract.Latest latest =
                    SignedUpdateContract.verifyLatest(
                            latestBytes,
                            publicKey
                    );
            String latestSha256 =
                    SignedUpdateContract.sha256(latestBytes);
            if (state != null) {
                state.requireCandidate(
                        latest,
                        "The signed latest pointer conflicts with "
                                + "the accepted update state"
                );
            }
            if (trusted != null) {
                SignedUpdateContract.requireNotOlder(
                        latest.getVersion(),
                        trusted.latest.getVersion()
                );
                requireSameVersionTuple(
                        latest,
                        trusted.latest,
                        "The signed latest pointer changed for an "
                                + "already accepted version"
                );
            }
            CheckState accepted = state == null
                    ? CheckState.accepted(latest)
                    : state.accept(latest);
            writeState(layout.state, accepted);
            publicationObserver.reached(
                    PublicationBoundary.ACCEPTED_STATE_PUBLISHED
            );

            manifestBytes = downloadBytes(
                    releaseUri(latest.getManifestPath()),
                    SignedUpdateContract.MAX_MANIFEST_BYTES,
                    latest.getManifestSize()
            );
            if (!constantTimeEquals(
                    latest.getManifestSha256(),
                    SignedUpdateContract.sha256(manifestBytes)
            )) {
                throw invalid(
                        "Downloaded manifest does not match latest SHA-256"
                );
            }
            SignedUpdateContract.Manifest manifest =
                    SignedUpdateContract.verifyManifest(
                            manifestBytes,
                            publicKey
                    );
            SignedUpdateContract.bind(
                    latest,
                    manifest,
                    manifestBytes
            );
            SignedUpdateContract.Artifact artifact =
                    manifest.artifactFor(platform);

            if (cache != null
                    && latest.getVersion().equals(
                    cache.documents.latest.getVersion()
            )
                    && constantTimeEquals(
                    latestSha256,
                    cache.latestSha256
            )) {
                writeState(
                        layout.state,
                        accepted.successful(now)
                );
                publicationObserver.reached(
                        PublicationBoundary.UPDATE_STATE_PUBLISHED
                );
                return cache.binary;
            }

            artifactSignature = downloadBytes(
                    releaseUri(artifact.getSignaturePath()),
                    SignedUpdateContract.SIGNATURE_BYTES,
                    SignedUpdateContract.SIGNATURE_BYTES
            );
            binaryTemporary = downloadBinary(
                    layout.generations,
                    publicKey,
                    artifact,
                    artifactSignature
            );
            VerifiedCache installed = publishGeneration(
                    layout,
                    binaryTemporary,
                    artifactSignature,
                    latestBytes,
                    manifestBytes,
                    now,
                    latest,
                    latestSha256,
                    publicKey
            );
            binaryTemporary = null;
            return installed.binary;
        } finally {
            Arrays.fill(latestBytes, (byte) 0);
            if (manifestBytes != null) {
                Arrays.fill(manifestBytes, (byte) 0);
            }
            if (artifactSignature != null) {
                Arrays.fill(artifactSignature, (byte) 0);
            }
            if (binaryTemporary != null) {
                deleteTemporary(binaryTemporary);
            }
        }
    }

    private TrustedDocuments readTrustedDocuments(
            GenerationLayout generation,
            byte[] publicKey
    ) throws CliDistributionException {
        boolean latestExists = existsNoFollow(generation.latest);
        boolean manifestExists = existsNoFollow(generation.manifest);
        boolean binaryExists = existsNoFollow(generation.binary);
        boolean signatureExists = existsNoFollow(generation.signature);
        if (!latestExists
                || !manifestExists
                || !binaryExists
                || !signatureExists) {
            throw invalid(
                    "The managed Locker CLI generation is incomplete"
            );
        }

        byte[] latestBytes = readRegularFile(
                generation.latest,
                SignedUpdateContract.MAX_LATEST_BYTES,
                "Cached signed latest pointer"
        );
        byte[] manifestBytes = readRegularFile(
                generation.manifest,
                SignedUpdateContract.MAX_MANIFEST_BYTES,
                "Cached signed manifest"
        );
        try {
            SignedUpdateContract.Latest latest =
                    SignedUpdateContract.verifyLatest(
                            latestBytes,
                            publicKey
                    );
            if (manifestBytes.length != latest.getManifestSize()
                    || !constantTimeEquals(
                    latest.getManifestSha256(),
                    SignedUpdateContract.sha256(manifestBytes)
            )) {
                throw invalid(
                        "Cached manifest does not match signed latest"
                );
            }
            SignedUpdateContract.Manifest manifest =
                    SignedUpdateContract.verifyManifest(
                            manifestBytes,
                            publicKey
                    );
            SignedUpdateContract.bind(
                    latest,
                    manifest,
                    manifestBytes
            );
            return new TrustedDocuments(
                    latest,
                    manifest.artifactFor(platform),
                    latestBytes
            );
        } finally {
            Arrays.fill(latestBytes, (byte) 0);
            Arrays.fill(manifestBytes, (byte) 0);
        }
    }

    private VerifiedCache verifyCachedBinary(
            GenerationLayout generation,
            byte[] publicKey,
            TrustedDocuments documents,
            GenerationPointer pointer,
            boolean verifyDetachedSignature
    ) throws CliDistributionException {
        byte[] signature = null;
        try {
            if (verifyDetachedSignature) {
                signature = readRegularFile(
                        generation.signature,
                        SignedUpdateContract.SIGNATURE_BYTES,
                        "Cached Locker CLI signature"
                );
                if (signature.length
                        != SignedUpdateContract.SIGNATURE_BYTES) {
                    throw invalid(
                            "Cached Locker CLI signature has an invalid size"
                    );
                }
            }
            verifyBinaryFile(
                    generation.binary,
                    publicKey,
                    documents.artifact,
                    signature
            );
            requirePrivate(generation.binary, false, true);
            requirePrivate(generation.signature, false, false);
            requirePrivate(generation.latest, false, false);
            requirePrivate(generation.manifest, false, false);
            if (verifyDetachedSignature) {
                detachedSignatureBinding = signatureBinding(pointer);
            }
            return new VerifiedCache(
                    generation.binary,
                    documents,
                    pointer.latestSha256
            );
        } finally {
            if (signature != null) {
                Arrays.fill(signature, (byte) 0);
            }
        }
    }

    private boolean requiresDetachedSignature(
            GenerationPointer pointer
    ) {
        return !signatureBinding(pointer).equals(
                detachedSignatureBinding
        );
    }

    private static String signatureBinding(
            GenerationPointer pointer
    ) {
        return pointer.generation + ":" + pointer.latestSha256;
    }

    private Path downloadBinary(
            Path temporaryDirectory,
            byte[] publicKey,
            SignedUpdateContract.Artifact artifact,
            byte[] signature
    ) throws CliDistributionException, TransientNetworkException {
        DownloadedResponse response = open(
                releaseUri(artifact.getPath()),
                artifact.getSize()
        );
        Path temporary = createPrivateTemporary(
                temporaryDirectory,
                ".locker-binary-",
                ".tmp"
        );
        boolean verified = false;
        try {
            requireSuccessfulResponse(
                    response,
                    artifact.getSize(),
                    "Locker CLI binary"
            );
            MessageDigest digest = sha256Digest();
            Ed25519Signer verifier =
                    SignedUpdateContract.newStreamingVerifier(
                            publicKey
                    );
            long total = 0;
            byte[] buffer = new byte[8192];
            try (FileChannel output = FileChannel.open(
                    temporary,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                while (true) {
                    int read;
                    try {
                        read = response.body.read(buffer);
                    } catch (IOException exception) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new CliDistributionException(
                                    "Locker CLI binary download "
                                            + "was interrupted",
                                    exception
                            );
                        }
                        if (isTlsFailure(exception)) {
                            throw new CliDistributionException(
                                    "Locker CLI binary TLS "
                                            + "verification failed",
                                    exception
                            );
                        }
                        throw new TransientNetworkException(
                                "Locker CLI binary download was interrupted",
                                exception
                        );
                    }
                    if (read < 0) {
                        break;
                    }
                    total = Math.addExact(total, read);
                    if (total > artifact.getSize()) {
                        throw invalid(
                                "Downloaded Locker CLI exceeds its "
                                        + "declared size"
                        );
                    }
                    digest.update(buffer, 0, read);
                    verifier.update(buffer, 0, read);
                    try {
                        ByteBuffer bytes = ByteBuffer.wrap(
                                buffer,
                                0,
                                read
                        );
                        while (bytes.hasRemaining()) {
                            output.write(bytes);
                        }
                    } catch (IOException exception) {
                        throw new CliDistributionException(
                                "The managed Locker CLI cannot be written",
                                exception
                        );
                    }
                }
                try {
                    output.force(true);
                } catch (IOException exception) {
                    throw new CliDistributionException(
                            "The managed Locker CLI cannot be flushed",
                            exception
                    );
                }
                if (total != artifact.getSize()) {
                    throw invalid(
                            "Downloaded Locker CLI size does not match "
                                    + "the signed manifest"
                    );
                }
                ExecutableHeaderValidator.verify(
                        output,
                        total,
                        platform
                );
            } catch (IOException exception) {
                throw new CliDistributionException(
                        "The managed Locker CLI temporary file "
                                + "cannot be opened",
                        exception
                );
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
            if (!constantTimeEquals(
                    artifact.getSha256(),
                    hexadecimal(digest.digest())
            )
                    || !verifier.verifySignature(signature)) {
                throw invalid(
                        "Downloaded Locker CLI failed size, hash, "
                                + "or Ed25519 verification"
                );
            }
            try {
                securePrivate(temporary, false, true);
            } catch (IOException exception) {
                throw new CliDistributionException(
                        "The managed Locker CLI temporary permissions "
                                + "cannot be secured",
                        exception
                );
            }
            requirePrivate(temporary, false, true);
            verified = true;
            return temporary;
        } finally {
            try {
                response.close();
            } catch (IOException exception) {
                if (verified) {
                    deleteTemporary(temporary);
                    throw new CliDistributionException(
                            "Locker CLI download did not close cleanly",
                            exception
                    );
                }
            }
            if (!verified) {
                deleteTemporary(temporary);
            }
        }
    }

    private void verifyBinaryFile(
            Path binary,
            byte[] publicKey,
            SignedUpdateContract.Artifact artifact,
            byte[] signature
    ) throws CliDistributionException {
        BasicFileAttributes before = attributes(
                binary,
                "Managed Locker CLI"
        );
        if (!before.isRegularFile()
                || before.size() != artifact.getSize()) {
            throw invalid(
                    "Managed Locker CLI size does not match "
                            + "the signed manifest"
            );
        }
        MessageDigest digest = sha256Digest();
        Ed25519Signer verifier = signature == null
                ? null
                : SignedUpdateContract.newStreamingVerifier(publicKey);
        byte[] buffer = new byte[8192];
        long total = 0;
        try (FileChannel input = FileChannel.open(
                binary,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            ExecutableHeaderValidator.verify(
                    input,
                    before.size(),
                    platform
            );
            input.position(0);
            ByteBuffer bytes = ByteBuffer.wrap(buffer);
            int read;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CliDistributionException(
                            "Managed Locker CLI verification was interrupted"
                    );
                }
                bytes.clear();
                read = input.read(bytes);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw invalid(
                            "Managed Locker CLI could not be read "
                                    + "to completion"
                    );
                }
                total = Math.addExact(total, read);
                if (total > artifact.getSize()) {
                    throw invalid(
                            "Managed Locker CLI exceeds its signed size"
                    );
                }
                digest.update(buffer, 0, read);
                if (verifier != null) {
                    verifier.update(buffer, 0, read);
                }
            }
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "Managed Locker CLI cannot be read",
                    exception
            );
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
        BasicFileAttributes after = attributes(
                binary,
                "Managed Locker CLI"
        );
        if (!sameIdentity(before, after)
                || total != artifact.getSize()
                || !constantTimeEquals(
                artifact.getSha256(),
                hexadecimal(digest.digest())
        )
                || verifier != null
                && !verifier.verifySignature(signature)) {
            throw invalid(
                    "Managed Locker CLI failed signed cache verification"
            );
        }
    }

    private VerifiedCache publishGeneration(
            Layout layout,
            Path binaryTemporary,
            byte[] signature,
            byte[] latest,
            byte[] manifest,
            long now,
            SignedUpdateContract.Latest verifiedLatest,
            String latestSha256,
            byte[] publicKey
    ) throws CliDistributionException {
        String generationId = "g-"
                + latestSha256
                + "-"
                + UUID.randomUUID().toString().replace("-", "");
        if (!GENERATION_PATTERN.matcher(generationId).matches()) {
            throw invalid(
                    "Managed Locker CLI generation identifier is invalid"
            );
        }
        Path temporaryDirectory = createPrivateTemporaryDirectory(
                layout.generations
        );
        GenerationLayout temporary = generationLayoutAt(
                temporaryDirectory,
                layout.executableName
        );
        GenerationLayout published = null;
        boolean directoryPublished = false;
        try {
            publishTemporary(
                    binaryTemporary,
                    temporary.binary,
                    true
            );
            publicationObserver.reached(
                    PublicationBoundary.GENERATION_BINARY_PUBLISHED
            );
            writeAtomic(temporary.signature, signature, false);
            publicationObserver.reached(
                    PublicationBoundary.GENERATION_SIGNATURE_PUBLISHED
            );
            writeAtomic(temporary.manifest, manifest, false);
            publicationObserver.reached(
                    PublicationBoundary.GENERATION_MANIFEST_PUBLISHED
            );
            writeAtomic(temporary.latest, latest, false);
            publicationObserver.reached(
                    PublicationBoundary.GENERATION_LATEST_PUBLISHED
            );
            try {
                forceDirectory(temporary.directory);
            } catch (IOException exception) {
                throw new CliDistributionException(
                        "Managed Locker CLI generation cannot be flushed",
                        exception
                );
            }

            Path publishedDirectory =
                    layout.generations.resolve(generationId);
            publishGenerationDirectory(
                    temporary.directory,
                    publishedDirectory
            );
            directoryPublished = true;
            published = generationLayout(layout, generationId);
            publicationObserver.reached(
                    PublicationBoundary.GENERATION_DIRECTORY_PUBLISHED
            );

            GenerationPointer pointer = new GenerationPointer(
                    generationId,
                    verifiedLatest.getVersion(),
                    latestSha256
            );
            TrustedDocuments publishedDocuments =
                    readTrustedDocuments(published, publicKey);
            requirePointerBinding(pointer, publishedDocuments);
            VerifiedCache verified = verifyCachedBinary(
                    published,
                    publicKey,
                    publishedDocuments,
                    pointer,
                    true
            );

            writePointer(layout.pointer, pointer);
            publicationObserver.reached(
                    PublicationBoundary.CURRENT_POINTER_PUBLISHED
            );
            writeState(
                    layout.state,
                    CheckState.accepted(verifiedLatest).successful(now)
            );
            publicationObserver.reached(
                    PublicationBoundary.UPDATE_STATE_PUBLISHED
            );
            return verified;
        } finally {
            if (!directoryPublished) {
                deleteTemporaryGeneration(temporary);
            }
        }
    }

    private Optional<CheckState> readState(Path path)
            throws CliDistributionException {
        if (!existsNoFollow(path)) {
            return Optional.empty();
        }
        byte[] bytes = readRegularFile(
                path,
                MAX_STATE_BYTES,
                "Managed Locker CLI update state"
        );
        try {
            JsonElement parsed = StrictJson.parse(bytes, 8);
            if (!parsed.isJsonObject()) {
                throw invalid(
                        "Managed Locker CLI update state must be an object"
                );
            }
            JsonObject object = parsed.getAsJsonObject();
            requireExactFields(
                    object,
                    STATE_FIELDS,
                    "Managed Locker CLI update state"
            );
            requireCanonical(
                    bytes,
                    object,
                    "Managed Locker CLI update state"
            );
            if (!"locker-cli-java-updater".equals(
                    requireMetadataString(
                            object,
                            "product",
                            "Managed Locker CLI update state"
                    )
            )
                    || requireMetadataLong(
                    object,
                    "schema_version",
                    "Managed Locker CLI update state"
            ) != 2) {
                throw invalid(
                        "Managed Locker CLI update state identity is invalid"
                );
            }
            String version = requireMetadataString(
                    object,
                    "version",
                    "Managed Locker CLI update state"
            );
            SignedUpdateContract.requireNotOlder(version, version);
            String sourceCommit = requireMetadataString(
                    object,
                    "source_commit",
                    "Managed Locker CLI update state"
            );
            if (!SOURCE_COMMIT_PATTERN.matcher(sourceCommit).matches()) {
                throw invalid(
                        "Managed Locker CLI update state source commit "
                                + "is invalid"
                );
            }
            String manifestSha256 = requireSha256(
                    requireMetadataString(
                            object,
                            "manifest_sha256",
                            "Managed Locker CLI update state"
                    ),
                    "Managed Locker CLI update state"
            );
            long manifestSize = requireMetadataLong(
                    object,
                    "manifest_size",
                    "Managed Locker CLI update state"
            );
            if (manifestSize < 1
                    || manifestSize
                    > SignedUpdateContract.MAX_MANIFEST_BYTES) {
                throw invalid(
                        "Managed Locker CLI update state manifest size "
                                + "is invalid"
                );
            }
            long checkedAt = requireMetadataLong(
                    object,
                    "last_successful_check_epoch_seconds",
                    "Managed Locker CLI update state"
            );
            long retryAfter = requireMetadataLong(
                    object,
                    "retry_after_epoch_seconds",
                    "Managed Locker CLI update state"
            );
            if (checkedAt < 0 || retryAfter < 0) {
                throw invalid(
                        "Managed Locker CLI update state time is invalid"
                );
            }
            requirePrivate(path, false, false);
            return Optional.of(new CheckState(
                    version,
                    sourceCommit,
                    manifestSha256,
                    manifestSize,
                    checkedAt,
                    retryAfter
            ));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void writeState(
            Path path,
            CheckState state
    ) throws CliDistributionException {
        requireSha256(
                state.manifestSha256,
                "Managed Locker CLI update state"
        );
        if (!SOURCE_COMMIT_PATTERN.matcher(
                state.sourceCommit
        ).matches()) {
            throw invalid(
                    "Managed Locker CLI update state source commit "
                            + "is invalid"
            );
        }
        SignedUpdateContract.requireNotOlder(
                state.version,
                state.version
        );
        if (state.manifestSize < 1
                || state.manifestSize
                > SignedUpdateContract.MAX_MANIFEST_BYTES
                || state.lastSuccessfulCheck < 0
                || state.retryAfter < 0) {
            throw invalid(
                    "Managed Locker CLI update state is invalid"
            );
        }
        String value = "{"
                + "\"last_successful_check_epoch_seconds\":"
                + state.lastSuccessfulCheck
                + ","
                + "\"manifest_sha256\":\""
                + state.manifestSha256
                + "\","
                + "\"manifest_size\":" + state.manifestSize + ","
                + "\"product\":\"locker-cli-java-updater\","
                + "\"retry_after_epoch_seconds\":"
                + state.retryAfter
                + ","
                + "\"schema_version\":2,"
                + "\"source_commit\":\"" + state.sourceCommit + "\","
                + "\"version\":\"" + state.version + "\""
                + "}\n";
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        try {
            writeAtomic(path, bytes, false);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private Optional<GenerationPointer> readPointer(Layout layout)
            throws CliDistributionException {
        if (!existsNoFollow(layout.pointer)) {
            return Optional.empty();
        }
        byte[] bytes = readRegularFile(
                layout.pointer,
                MAX_POINTER_BYTES,
                "Managed Locker CLI current pointer"
        );
        try {
            JsonElement parsed = StrictJson.parse(bytes, 8);
            if (!parsed.isJsonObject()) {
                throw invalid(
                        "Managed Locker CLI current pointer "
                                + "must be an object"
                );
            }
            JsonObject object = parsed.getAsJsonObject();
            requireExactFields(
                    object,
                    POINTER_FIELDS,
                    "Managed Locker CLI current pointer"
            );
            requireCanonical(
                    bytes,
                    object,
                    "Managed Locker CLI current pointer"
            );
            if (!"locker-cli-java-updater".equals(
                    requireMetadataString(
                            object,
                            "product",
                            "Managed Locker CLI current pointer"
                    )
            )
                    || requireMetadataLong(
                    object,
                    "schema_version",
                    "Managed Locker CLI current pointer"
            ) != 2) {
                throw invalid(
                        "Managed Locker CLI current pointer "
                                + "identity is invalid"
                );
            }
            String version = requireMetadataString(
                    object,
                    "version",
                    "Managed Locker CLI current pointer"
            );
            SignedUpdateContract.requireNotOlder(version, version);
            String latestSha256 = requireSha256(
                    requireMetadataString(
                            object,
                            "latest_sha256",
                            "Managed Locker CLI current pointer"
                    ),
                    "Managed Locker CLI current pointer"
            );
            String generation = requireMetadataString(
                    object,
                    "generation",
                    "Managed Locker CLI current pointer"
            );
            if (!GENERATION_PATTERN.matcher(generation).matches()
                    || !generation.startsWith(
                    "g-" + latestSha256 + "-"
            )) {
                throw invalid(
                        "Managed Locker CLI current pointer "
                                + "generation is invalid"
                );
            }
            requirePrivate(layout.pointer, false, false);
            return Optional.of(new GenerationPointer(
                    generation,
                    version,
                    latestSha256
            ));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void writePointer(
            Path path,
            GenerationPointer pointer
    ) throws CliDistributionException {
        if (!GENERATION_PATTERN.matcher(
                pointer.generation
        ).matches()
                || !pointer.generation.startsWith(
                "g-" + pointer.latestSha256 + "-"
        )) {
            throw invalid(
                    "Managed Locker CLI current pointer "
                            + "generation is invalid"
            );
        }
        requireSha256(
                pointer.latestSha256,
                "Managed Locker CLI current pointer"
        );
        SignedUpdateContract.requireNotOlder(
                pointer.version,
                pointer.version
        );
        String value = "{"
                + "\"generation\":\"" + pointer.generation + "\","
                + "\"latest_sha256\":\""
                + pointer.latestSha256
                + "\","
                + "\"product\":\"locker-cli-java-updater\","
                + "\"schema_version\":2,"
                + "\"version\":\"" + pointer.version + "\""
                + "}\n";
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        try {
            writeAtomic(path, bytes, false);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void requirePointerBinding(
            GenerationPointer pointer,
            TrustedDocuments documents
    ) throws CliDistributionException {
        String actualLatestSha256 =
                SignedUpdateContract.sha256(documents.latestBytes);
        if (!pointer.version.equals(
                documents.latest.getVersion()
        )
                || !constantTimeEquals(
                pointer.latestSha256,
                actualLatestSha256
        )
                || !pointer.generation.startsWith(
                "g-" + actualLatestSha256 + "-"
        )) {
            throw invalid(
                    "Managed Locker CLI current pointer does not "
                            + "match its signed generation"
            );
        }
    }

    private byte[] downloadBytes(
            URI uri,
            long maximum,
            long expectedSize
    ) throws CliDistributionException, TransientNetworkException {
        DownloadedResponse response = open(uri, maximum);
        try {
            requireSuccessfulResponse(
                    response,
                    expectedSize < 0 ? maximum : expectedSize,
                    "Locker CLI update object"
            );
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            try {
                while (true) {
                    int read;
                    try {
                        read = response.body.read(buffer);
                    } catch (IOException exception) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new CliDistributionException(
                                    "Locker CLI update download "
                                            + "was interrupted",
                                    exception
                            );
                        }
                        if (isTlsFailure(exception)) {
                            throw new CliDistributionException(
                                    "Locker CLI update TLS "
                                            + "verification failed",
                                    exception
                            );
                        }
                        throw new TransientNetworkException(
                                "Locker CLI update download was interrupted",
                                exception
                        );
                    }
                    if (read < 0) {
                        break;
                    }
                    total = Math.addExact(total, read);
                    if (total > maximum) {
                        throw invalid(
                                "Locker CLI update object exceeds "
                                        + "its size bound"
                        );
                    }
                    output.write(buffer, 0, read);
                }
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
            if (total < 1
                    || (expectedSize >= 0 && total != expectedSize)) {
                throw invalid(
                        "Locker CLI update object size does not match "
                                + "its signed declaration"
                );
            }
            return output.toByteArray();
        } finally {
            try {
                response.close();
            } catch (IOException exception) {
                throw new CliDistributionException(
                        "Locker CLI update response did not close cleanly",
                        exception
                );
            }
        }
    }

    private DownloadedResponse open(URI uri, long maximum)
            throws CliDistributionException, TransientNetworkException {
        requireReleaseUri(uri);
        try {
            return transport.open(uri, maximum);
        } catch (IOException exception) {
            if (isTlsFailure(exception)) {
                throw new CliDistributionException(
                        "Locker CLI update TLS verification failed",
                        exception
                );
            }
            throw new TransientNetworkException(
                    "Locker CLI update network request failed",
                    exception
            );
        }
    }

    private static void requireSuccessfulResponse(
            DownloadedResponse response,
            long expectedOrMaximum,
            String label
    ) throws CliDistributionException, TransientNetworkException {
        int status = response.statusCode;
        if (status != 200) {
            if (status == 408
                    || status == 425
                    || status == 429
                    || (status >= 500 && status <= 599)) {
                throw new TransientNetworkException(
                        label + " returned transient HTTP status " + status
                );
            }
            throw invalid(
                    label + " returned unexpected HTTP status " + status
            );
        }
        if (response.contentLength.isPresent()) {
            long length = response.contentLength.get();
            if (length < 1 || length > expectedOrMaximum) {
                throw invalid(
                        label + " Content-Length is outside bounds"
                );
            }
        }
    }

    private static boolean isTlsFailure(Throwable failure) {
        Set<Throwable> visited =
                Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>()
                );
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof SSLException
                    || current instanceof CertificateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private byte[] loadPublicKey() throws CliDistributionException {
        if (trustedPublicKey != null) {
            if (trustedPublicKey.length
                    != SignedUpdateContract.PUBLIC_KEY_BYTES) {
                throw invalid(
                        "The Locker CLI release public key is invalid"
                );
            }
            return trustedPublicKey.clone();
        }
        return SignedUpdateContract.decodePublicKey(
                COMPILED_RELEASE_PUBLIC_KEY
        );
    }

    static String compiledReleasePublicKey() {
        return COMPILED_RELEASE_PUBLIC_KEY;
    }

    private static Layout prepareLayout(
            Path configuredHome,
            PlatformIdentity platform
    ) throws CliDistributionException {
        final Path home;
        try {
            Path absolute = configuredHome.toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(
                    absolute,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()) {
                throw invalid(
                        "The user home must be a real directory"
                );
            }
            home = absolute.toRealPath();
        } catch (IOException | InvalidPathException exception) {
            throw new CliDistributionException(
                    "The user home directory is unavailable",
                    exception
            );
        }

        UserPrincipal homeOwner;
        try {
            homeOwner = Files.getOwner(
                    home,
                    LinkOption.NOFOLLOW_LINKS
            );
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "The user home owner cannot be verified",
                    exception
            );
        }
        Path locker = home.resolve(".locker");
        ensureSafeSharedDirectory(locker, homeOwner);
        Path sdkCli = locker.resolve("sdk-cli");
        ensureSafeSharedDirectory(sdkCli, homeOwner);
        Path directory = sdkCli.resolve("java");
        ensurePrivateDirectory(directory);
        Path generations = directory.resolve("generations");
        ensurePrivateDirectory(generations);
        String executableName = platform.isWindows()
                ? "locker.exe"
                : "locker";
        return new Layout(
                generations,
                executableName,
                directory.resolve("locker.current.json"),
                directory.resolve("locker.update-state.json"),
                directory.resolve("locker.update.lock")
        );
    }

    private static void ensureSafeSharedDirectory(
            Path path,
            UserPrincipal expectedOwner
    ) throws CliDistributionException {
        try {
            boolean created = false;
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(
                            path,
                            privateAttributes(path.getParent(), true)
                    );
                    created = true;
                } catch (FileAlreadyExistsException alreadyExists) {
                    // A concurrent SDK resolver won creation.
                }
            }
            if (created) {
                securePrivate(path, true, false);
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || !expectedOwner.equals(Files.getOwner(
                    path,
                    LinkOption.NOFOLLOW_LINKS
            ))) {
                throw invalid(
                        "A shared Locker CLI cache ancestor is unsafe"
                );
            }
            requireSharedPrivateAfterCreation(path);
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "A shared Locker CLI cache ancestor "
                            + "cannot be verified",
                    exception
            );
        }
    }

    private static void requireSharedPrivateAfterCreation(
            Path path
    ) throws CliDistributionException {
        CliDistributionException lastFailure = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                requirePrivate(path, true, false);
                return;
            } catch (CliDistributionException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CliDistributionException(
                        "Interrupted while validating a shared "
                                + "Locker CLI cache ancestor",
                        exception
                );
            }
        }
        throw lastFailure;
    }

    private static GenerationLayout generationLayout(
            Layout layout,
            String generation
    ) throws CliDistributionException {
        if (generation == null
                || !GENERATION_PATTERN.matcher(generation).matches()) {
            throw invalid(
                    "Managed Locker CLI generation identifier is invalid"
            );
        }
        Path directory = layout.generations
                .resolve(generation)
                .normalize();
        if (!layout.generations.equals(directory.getParent())) {
            throw invalid(
                    "Managed Locker CLI generation path is unsafe"
            );
        }
        BasicFileAttributes attributes = attributes(
                directory,
                "Managed Locker CLI generation"
        );
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || attributes.isOther()) {
            throw invalid(
                    "Managed Locker CLI generation must be "
                            + "a real directory"
            );
        }
        try {
            if (!Files.getOwner(
                    layout.generations,
                    LinkOption.NOFOLLOW_LINKS
            ).equals(Files.getOwner(
                    directory,
                    LinkOption.NOFOLLOW_LINKS
            ))) {
                throw invalid(
                        "Managed Locker CLI generation owner is unsafe"
                );
            }
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "Managed Locker CLI generation owner "
                            + "cannot be verified",
                    exception
            );
        }
        requirePrivate(directory, true, false);
        return generationLayoutAt(
                directory,
                layout.executableName
        );
    }

    private static GenerationLayout generationLayoutAt(
            Path directory,
            String executableName
    ) {
        return new GenerationLayout(
                directory,
                directory.resolve(executableName),
                directory.resolve("locker.sig"),
                directory.resolve("locker.latest.json"),
                directory.resolve("locker.manifest.json")
        );
    }

    private static void ensurePrivateDirectory(Path path)
            throws CliDistributionException {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(
                            path,
                            privateAttributes(path.getParent(), true)
                    );
                } catch (FileAlreadyExistsException alreadyExists) {
                    // A concurrent resolver won creation; validate below.
                }
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || !Files.getOwner(
                    path.getParent(),
                    LinkOption.NOFOLLOW_LINKS
            ).equals(Files.getOwner(
                    path,
                    LinkOption.NOFOLLOW_LINKS
            ))) {
                throw invalid(
                        "A managed Locker CLI ancestor is unsafe"
                );
            }
            securePrivate(path, true, false);
            requirePrivate(path, true, false);
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "The managed Locker CLI directory cannot be secured",
                    exception
            );
        }
    }

    private static LockHandle acquireLock(Path path)
            throws CliDistributionException {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    try (FileChannel channel = FileChannel.open(
                            path,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS
                    )) {
                        channel.force(true);
                        securePrivate(path, false, false);
                    }
                } catch (FileAlreadyExistsException alreadyExists) {
                    // A concurrent resolver won creation; validate below.
                }
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()) {
                throw invalid(
                        "The managed Locker CLI lock path is unsafe"
                );
            }
            securePrivate(path, false, false);
            requirePrivate(path, false, false);

            FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
            long deadline = System.nanoTime()
                    + LOCK_TIMEOUT.toNanos();
            while (true) {
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        return new LockHandle(channel, lock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // Another updater in this JVM owns the native lock.
                }
                if (System.nanoTime() >= deadline) {
                    channel.close();
                    throw invalid(
                            "Timed out waiting for the managed CLI lock"
                    );
                }
                try {
                    Thread.sleep(LOCK_RETRY_MILLIS);
                } catch (InterruptedException exception) {
                    channel.close();
                    Thread.currentThread().interrupt();
                    throw new CliDistributionException(
                            "Interrupted while waiting for the managed "
                                    + "CLI lock",
                            exception
                    );
                }
            }
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "The managed Locker CLI lock is unavailable",
                    exception
            );
        }
    }

    private static void writeAtomic(
            Path target,
            byte[] bytes,
            boolean executable
    ) throws CliDistributionException {
        if (bytes == null || bytes.length < 1) {
            throw invalid(
                    "Managed Locker CLI cache bytes must not be empty"
            );
        }
        Path temporary = createPrivateTemporary(
                target.getParent(),
                "." + target.getFileName() + "-",
                ".tmp"
        );
        boolean published = false;
        try {
            try (FileChannel output = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            } catch (IOException exception) {
                throw new CliDistributionException(
                        "Managed Locker CLI cache cannot be written",
                        exception
                );
            }
            try {
                securePrivate(temporary, false, executable);
            } catch (IOException exception) {
                throw new CliDistributionException(
                        "Managed Locker CLI cache permissions "
                                + "cannot be secured",
                        exception
                );
            }
            publishTemporary(temporary, target, executable);
            published = true;
        } finally {
            if (!published) {
                deleteTemporary(temporary);
            }
        }
    }

    private static void publishTemporary(
            Path temporary,
            Path target,
            boolean executable
    ) throws CliDistributionException {
        try {
            rejectSymbolicLink(target, true);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Atomic managed CLI publication is unavailable",
                        exception
                );
            }
            securePrivate(target, false, executable);
            requirePrivate(target, false, executable);
            forceDirectory(target.getParent());
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "Managed Locker CLI cache cannot be published",
                    exception
            );
        }
    }

    private static void publishGenerationDirectory(
            Path temporary,
            Path target
    ) throws CliDistributionException {
        try {
            rejectSymbolicLink(target, true);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Managed Locker CLI generation already exists"
                );
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Atomic managed CLI generation publication "
                                + "is unavailable",
                        exception
                );
            }
            securePrivate(target, true, false);
            requirePrivate(target, true, false);
            forceDirectory(target.getParent());
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "Managed Locker CLI generation cannot be published",
                    exception
            );
        }
    }

    private static Path createPrivateTemporary(
            Path directory,
            String prefix,
            String suffix
    ) throws CliDistributionException {
        try {
            Path temporary = Files.createTempFile(
                    directory,
                    prefix,
                    suffix,
                    privateAttributes(directory, false)
            );
            securePrivate(temporary, false, false);
            return temporary;
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "A private managed CLI temporary file "
                            + "cannot be created",
                    exception
            );
        }
    }

    private static Path createPrivateTemporaryDirectory(
            Path parent
    ) throws CliDistributionException {
        try {
            Path temporary = Files.createTempDirectory(
                    parent,
                    ".generation-",
                    privateAttributes(parent, true)
            );
            securePrivate(temporary, true, false);
            requirePrivate(temporary, true, false);
            return temporary;
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "A private managed CLI temporary generation "
                            + "cannot be created",
                    exception
            );
        }
    }

    private static void deleteTemporaryGeneration(
            GenerationLayout generation
    ) throws CliDistributionException {
        deleteTemporary(generation.binary);
        deleteTemporary(generation.signature);
        deleteTemporary(generation.latest);
        deleteTemporary(generation.manifest);
        deleteTemporary(generation.directory);
    }

    private static void forceDirectory(Path directory)
            throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                directory,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (posix == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(
                directory,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            channel.force(true);
        }
    }

    private static byte[] readRegularFile(
            Path path,
            long maximum,
            String label
    ) throws CliDistributionException {
        BasicFileAttributes before = attributes(path, label);
        if (!before.isRegularFile()
                || before.size() < 1
                || before.size() > maximum) {
            throw invalid(
                    label + " must be a bounded regular non-symlink file"
            );
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            BasicFileAttributes after = attributes(path, label);
            if (bytes.length != before.size()
                    || !sameIdentity(before, after)) {
                Arrays.fill(bytes, (byte) 0);
                throw invalid(label + " changed while it was read");
            }
            return bytes;
        } catch (IOException exception) {
            throw new CliDistributionException(
                    label + " cannot be read",
                    exception
            );
        }
    }

    private static BasicFileAttributes attributes(
            Path path,
            String label
    ) throws CliDistributionException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()) {
                throw invalid(label + " must not be a symbolic link");
            }
            return attributes;
        } catch (IOException exception) {
            throw new CliDistributionException(
                    label + " is unavailable",
                    exception
            );
        }
    }

    private static boolean sameIdentity(
            BasicFileAttributes before,
            BasicFileAttributes after
    ) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return before.size() == after.size()
                && before.lastModifiedTime().equals(
                after.lastModifiedTime()
        )
                && (beforeKey == null
                || afterKey == null
                || beforeKey.equals(afterKey));
    }

    private static void rejectSymbolicLink(
            Path path,
            boolean allowAbsent
    ) throws CliDistributionException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (allowAbsent) {
                return;
            }
            throw invalid("Managed Locker CLI path is missing");
        }
        BasicFileAttributes attributes = attributes(
                path,
                "Managed Locker CLI path"
        );
        if (attributes.isSymbolicLink()) {
            throw invalid(
                    "Managed Locker CLI path is a symbolic link"
            );
        }
    }

    private static void securePrivate(
            Path path,
            boolean directory,
            boolean executable
    ) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (posix != null) {
            String permissions = directory || executable
                    ? "rwx------"
                    : "rw-------";
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(permissions)
            );
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (acl == null) {
            throw new IOException(
                    "The filesystem cannot enforce private permissions"
            );
        }
        UserPrincipal owner = acl.getOwner();
        AclEntry ownerAccess = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(
                        EnumSet.allOf(AclEntryPermission.class)
                )
                .build();
        acl.setAcl(Collections.singletonList(ownerAccess));
    }

    private static void requirePrivate(
            Path path,
            boolean directory,
            boolean executable
    ) throws CliDistributionException {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path,
                    PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (posix != null) {
                Set<java.nio.file.attribute.PosixFilePermission>
                        permissions = posix.readAttributes().permissions();
                Set<java.nio.file.attribute.PosixFilePermission>
                        allowed = PosixFilePermissions.fromString(
                        directory || executable
                                ? "rwx------"
                                : "rw-------"
                );
                if (!allowed.containsAll(permissions)
                        || !permissions.contains(
                        java.nio.file.attribute.PosixFilePermission
                                .OWNER_READ
                )
                        || !permissions.contains(
                        java.nio.file.attribute.PosixFilePermission
                                .OWNER_WRITE
                )
                        || ((directory || executable)
                        && !permissions.contains(
                        java.nio.file.attribute.PosixFilePermission
                                .OWNER_EXECUTE
                ))) {
                    throw invalid(
                            "Managed Locker CLI permissions are not private"
                    );
                }
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(
                    path,
                    AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (acl == null) {
                throw invalid(
                        "The filesystem cannot verify private permissions"
                );
            }
            UserPrincipal owner = acl.getOwner();
            boolean ownerAllowed = false;
            for (AclEntry entry : acl.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW) {
                    if (!owner.equals(entry.principal())) {
                        throw invalid(
                                "Managed Locker CLI ACL is not private"
                        );
                    }
                    ownerAllowed = true;
                }
            }
            if (!ownerAllowed) {
                throw invalid(
                        "Managed Locker CLI owner has no ACL access"
                );
            }
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "Managed Locker CLI permissions cannot be verified",
                    exception
            );
        }
    }

    private static FileAttribute<?>[] privateAttributes(
            Path parent,
            boolean directory
    ) {
        PosixFileAttributeView view = Files.getFileAttributeView(
                parent,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[]{
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString(
                                directory
                                        ? "rwx------"
                                        : "rw-------"
                        )
                )
        };
    }

    private static URI releaseUri(String relativePath)
            throws CliDistributionException {
        URI uri;
        try {
            uri = URI.create(
                    SignedUpdateContract.BASE_URL + relativePath
            );
        } catch (IllegalArgumentException exception) {
            throw new CliDistributionException(
                    "Signed Locker CLI release path is invalid",
                    exception
            );
        }
        requireReleaseUri(uri);
        return uri;
    }

    private static void requireReleaseUri(URI uri)
            throws CliDistributionException {
        if (uri == null
                || !"https".equals(uri.getScheme())
                || !"files.locker.io".equals(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !uri.getRawPath().startsWith("/cli/")
                || uri.getRawPath().contains("\\")
                || uri.getRawPath().contains("/../")
                || uri.getRawPath().contains("/./")) {
            throw invalid("Locker CLI release URI is unsafe");
        }
    }

    private static MessageDigest sha256Digest()
            throws CliDistributionException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new CliDistributionException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    private static String hexadecimal(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(
                    Character.forDigit((value >>> 4) & 0x0f, 16)
            );
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        Arrays.fill(bytes, (byte) 0);
        return result.toString();
    }

    private static boolean constantTimeEquals(
            String left,
            String right
    ) {
        if (left == null
                || right == null
                || !SHA256_PATTERN.matcher(left).matches()
                || !SHA256_PATTERN.matcher(right).matches()) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static void requireSameVersionTuple(
            SignedUpdateContract.Latest left,
            SignedUpdateContract.Latest right,
            String message
    ) throws CliDistributionException {
        if (left.getVersion().equals(right.getVersion())
                && (!left.getSourceCommit().equals(
                right.getSourceCommit()
        )
                || !constantTimeEquals(
                left.getManifestSha256(),
                right.getManifestSha256()
        )
                || left.getManifestSize() != right.getManifestSize())) {
            throw invalid(message);
        }
    }

    private static byte[] readBounded(
            InputStream input,
            int maximum
    ) throws IOException, CliDistributionException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total = Math.addExact(total, read);
            if (total > maximum) {
                throw invalid("Packaged release metadata is too large");
            }
            output.write(buffer, 0, read);
        }
        Arrays.fill(buffer, (byte) 0);
        return output.toByteArray();
    }

    private static String decodeAscii(byte[] bytes, String label)
            throws CliDistributionException {
        try {
            return StandardCharsets.US_ASCII
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new CliDistributionException(
                    label + " must be ASCII",
                    exception
            );
        }
    }

    private static boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void deleteTemporary(Path path)
            throws CliDistributionException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "A managed Locker CLI temporary file "
                            + "cannot be removed",
                    exception
            );
        }
    }

    private static void requireExactFields(
            JsonObject object,
            Set<String> expected,
            String label
    )
            throws CliDistributionException {
        Set<String> actual = new HashSet<>();
        object.entrySet().forEach(entry -> actual.add(entry.getKey()));
        if (!actual.equals(expected)) {
            throw invalid(
                    label + " fields are invalid"
            );
        }
    }

    private static void requireCanonical(
            byte[] bytes,
            JsonObject object,
            String label
    ) throws CliDistributionException {
        byte[] canonical = SignedUpdateContract.canonicalize(object);
        byte[] expected = Arrays.copyOf(
                canonical,
                canonical.length + 1
        );
        expected[expected.length - 1] = '\n';
        try {
            if (!MessageDigest.isEqual(bytes, expected)) {
                throw invalid(label + " is not canonical");
            }
        } finally {
            Arrays.fill(canonical, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private static String requireMetadataString(
            JsonObject object,
            String field,
            String label
    ) throws CliDistributionException {
        JsonElement value = object.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw invalid(label + " is invalid");
        }
        return value.getAsString();
    }

    private static long requireMetadataLong(
            JsonObject object,
            String field,
            String label
    ) throws CliDistributionException {
        JsonElement value = object.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(label + " is invalid");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new CliDistributionException(
                    label + " integer is invalid",
                    exception
            );
        }
    }

    private static String requireSha256(
            String value,
            String label
    ) throws CliDistributionException {
        if (value == null
                || !SHA256_PATTERN.matcher(value).matches()) {
            throw invalid(label + " SHA-256 is invalid");
        }
        return value;
    }

    private static Path configuredUserHome() {
        String value = System.getProperty("user.home");
        if (value == null
                || value.isBlank()
                || value.indexOf('\0') >= 0) {
            throw new IllegalStateException(
                    "The user home directory is unavailable"
            );
        }
        return Paths.get(value);
    }

    private static PlatformIdentity currentPlatform() {
        try {
            return PlatformIdentity.current();
        } catch (CliDistributionException exception) {
            throw new IllegalStateException(
                    "The current Java platform is unsupported",
                    exception
            );
        }
    }

    private static CliDistributionException invalid(String message) {
        return new CliDistributionException(message);
    }

    interface DownloadTransport {
        DownloadedResponse open(
                URI uri,
                long maximumBytes
        ) throws IOException, CliDistributionException;
    }

    enum PublicationBoundary {
        GENERATION_BINARY_PUBLISHED,
        GENERATION_SIGNATURE_PUBLISHED,
        GENERATION_MANIFEST_PUBLISHED,
        GENERATION_LATEST_PUBLISHED,
        GENERATION_DIRECTORY_PUBLISHED,
        CURRENT_POINTER_PUBLISHED,
        ACCEPTED_STATE_PUBLISHED,
        RETRY_STATE_PUBLISHED,
        UPDATE_STATE_PUBLISHED
    }

    @FunctionalInterface
    interface PublicationObserver {
        void reached(PublicationBoundary boundary)
                throws CliDistributionException;
    }

    static final class DownloadedResponse implements AutoCloseable {
        private final int statusCode;
        private final Optional<Long> contentLength;
        private final InputStream body;

        DownloadedResponse(
                int statusCode,
                Optional<Long> contentLength,
                InputStream body
        ) {
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            this.body = body;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private static final class HttpDownloadTransport
            implements DownloadTransport {
        private static final Duration CONNECT_TIMEOUT =
                Duration.ofSeconds(15);
        private static final Duration REQUEST_TIMEOUT =
                Duration.ofMinutes(5);
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public DownloadedResponse open(
                URI uri,
                long maximumBytes
        ) throws IOException, CliDistributionException {
            long deadline;
            try {
                deadline = Math.addExact(
                        System.nanoTime(),
                        REQUEST_TIMEOUT.toNanos()
                );
            } catch (ArithmeticException exception) {
                throw new IOException(
                        "Locker CLI update deadline is invalid",
                        exception
                );
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(REQUEST_TIMEOUT)
                    .header(
                            "User-Agent",
                            "LockerSM-Java-Updater-v2"
                    )
                    .header("Accept", "application/octet-stream")
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CliDistributionException(
                        "Locker CLI update request was interrupted",
                        exception
                );
            }
            if (!response.uri().equals(uri)) {
                try {
                    response.body().close();
                } catch (IOException exception) {
                    throw new CliDistributionException(
                            "Locker CLI update response URI changed "
                                    + "and could not be closed",
                            exception
                    );
                }
                throw invalid("Locker CLI update response URI changed");
            }
            Optional<Long> contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .stream()
                    .boxed()
                    .findFirst();
            if (contentLength.isPresent()
                    && contentLength.get() > maximumBytes) {
                try {
                    response.body().close();
                } catch (IOException exception) {
                    throw new CliDistributionException(
                            "Locker CLI update response is too large "
                                    + "and could not be closed",
                            exception
                    );
                }
                throw invalid("Locker CLI update response is too large");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                try {
                    response.body().close();
                } catch (IOException exception) {
                    throw new CliDistributionException(
                            "Expired Locker CLI update response "
                                    + "could not be closed",
                            exception
                    );
                }
                throw new java.net.SocketTimeoutException(
                        "Locker CLI update response deadline expired"
                );
            }
            return new DownloadedResponse(
                    response.statusCode(),
                    contentLength,
                    new ResponseDeadlineInputStream(
                            response.body(),
                            Duration.ofNanos(remaining)
                    )
            );
        }
    }

    private static final class Layout {
        private final Path generations;
        private final String executableName;
        private final Path pointer;
        private final Path state;
        private final Path lock;

        private Layout(
                Path generations,
                String executableName,
                Path pointer,
                Path state,
                Path lock
        ) {
            this.generations = generations;
            this.executableName = executableName;
            this.pointer = pointer;
            this.state = state;
            this.lock = lock;
        }
    }

    private static final class GenerationLayout {
        private final Path directory;
        private final Path binary;
        private final Path signature;
        private final Path latest;
        private final Path manifest;

        private GenerationLayout(
                Path directory,
                Path binary,
                Path signature,
                Path latest,
                Path manifest
        ) {
            this.directory = directory;
            this.binary = binary;
            this.signature = signature;
            this.latest = latest;
            this.manifest = manifest;
        }
    }

    private static final class GenerationPointer {
        private final String generation;
        private final String version;
        private final String latestSha256;

        private GenerationPointer(
                String generation,
                String version,
                String latestSha256
        ) {
            this.generation = generation;
            this.version = version;
            this.latestSha256 = latestSha256;
        }
    }

    private static final class TrustedDocuments {
        private final SignedUpdateContract.Latest latest;
        private final SignedUpdateContract.Artifact artifact;
        private final byte[] latestBytes;

        private TrustedDocuments(
                SignedUpdateContract.Latest latest,
                SignedUpdateContract.Artifact artifact,
                byte[] latestBytes
        ) {
            this.latest = latest;
            this.artifact = artifact;
            this.latestBytes = latestBytes.clone();
        }
    }

    private static final class VerifiedCache {
        private final Path binary;
        private final TrustedDocuments documents;
        private final String latestSha256;

        private VerifiedCache(
                Path binary,
                TrustedDocuments documents,
                String latestSha256
        ) {
            this.binary = binary;
            this.documents = documents;
            this.latestSha256 = latestSha256;
        }
    }

    private static final class CheckState {
        private final String version;
        private final String sourceCommit;
        private final String manifestSha256;
        private final long manifestSize;
        private final long lastSuccessfulCheck;
        private final long retryAfter;

        private CheckState(
                String version,
                String sourceCommit,
                String manifestSha256,
                long manifestSize,
                long lastSuccessfulCheck,
                long retryAfter
        ) {
            this.version = version;
            this.sourceCommit = sourceCommit;
            this.manifestSha256 = manifestSha256;
            this.manifestSize = manifestSize;
            this.lastSuccessfulCheck = lastSuccessfulCheck;
            this.retryAfter = retryAfter;
        }

        private static CheckState accepted(
                SignedUpdateContract.Latest latest
        ) {
            return new CheckState(
                    latest.getVersion(),
                    latest.getSourceCommit(),
                    latest.getManifestSha256(),
                    latest.getManifestSize(),
                    0,
                    0
            );
        }

        private static CheckState fromCache(VerifiedCache cache) {
            return accepted(cache.documents.latest);
        }

        private CheckState accept(SignedUpdateContract.Latest latest) {
            boolean same = matches(latest);
            return new CheckState(
                    latest.getVersion(),
                    latest.getSourceCommit(),
                    latest.getManifestSha256(),
                    latest.getManifestSize(),
                    same ? lastSuccessfulCheck : 0,
                    0
            );
        }

        private CheckState successful(long now) {
            return new CheckState(
                    version,
                    sourceCommit,
                    manifestSha256,
                    manifestSize,
                    now,
                    0
            );
        }

        private CheckState withRetryAfter(long now)
                throws CliDistributionException {
            final long retry;
            try {
                retry = Math.addExact(now, RETRY_DELAY_SECONDS);
            } catch (ArithmeticException exception) {
                throw new CliDistributionException(
                        "The system clock is invalid",
                        exception
                );
            }
            return new CheckState(
                    version,
                    sourceCommit,
                    manifestSha256,
                    manifestSize,
                    lastSuccessfulCheck,
                    retry
            );
        }

        private boolean isFresh(
                SignedUpdateContract.Latest expected,
                long now
        )
                throws CliDistributionException {
            if (lastSuccessfulCheck > now) {
                throw invalid(
                        "Managed Locker CLI update state is inconsistent"
                );
            }
            return matches(expected)
                    && lastSuccessfulCheck > 0
                    && now - lastSuccessfulCheck
                    < CHECK_INTERVAL_SECONDS;
        }

        private void requireCompatibleActiveGeneration(
                SignedUpdateContract.Latest active,
                String message
        ) throws CliDistributionException {
            if (version.equals(active.getVersion())
                    && !matches(active)) {
                throw invalid(message);
            }
        }

        private void requireCandidate(
                SignedUpdateContract.Latest candidate,
                String message
        ) throws CliDistributionException {
            SignedUpdateContract.requireNotOlder(
                    candidate.getVersion(),
                    version
            );
            if (version.equals(candidate.getVersion())
                    && !matches(candidate)) {
                throw invalid(message);
            }
        }

        private boolean matches(
                SignedUpdateContract.Latest latest
        ) {
            return version.equals(latest.getVersion())
                    && sourceCommit.equals(latest.getSourceCommit())
                    && constantTimeEquals(
                    manifestSha256,
                    latest.getManifestSha256()
            )
                    && manifestSize == latest.getManifestSize();
        }

        private boolean shouldDelayRetry(long now) {
            return retryAfter > now;
        }

        private void requireNotFuture(long now)
                throws CliDistributionException {
            long maximumRetry;
            try {
                maximumRetry = Math.addExact(
                        now,
                        RETRY_DELAY_SECONDS
                );
            } catch (ArithmeticException exception) {
                throw new CliDistributionException(
                        "The system clock is invalid",
                        exception
                );
            }
            if (lastSuccessfulCheck > now
                    || retryAfter > maximumRetry) {
                throw invalid(
                        "Managed Locker CLI update state is inconsistent"
                );
            }
        }
    }

    private static final class LockHandle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LockHandle(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws CliDistributionException {
            try {
                lock.release();
                channel.close();
            } catch (IOException exception) {
                throw new CliDistributionException(
                        "The managed Locker CLI lock cannot be released",
                        exception
                );
            }
        }
    }

    private static final class TransientNetworkException
            extends Exception {
        private static final long serialVersionUID = 1L;

        private TransientNetworkException(String message) {
            super(message);
        }

        private TransientNetworkException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }
}
