package io.agentteams.worker;

import io.agentscope.core.skill.util.SkillUtil;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Turns a verified Skill archive into a safe, read-only runtime directory. */
class SkillArtifactMaterializer {
    private static final int TAR_BLOCK_BYTES = 512;
    private final SkillArtifactFetcher fetcher;
    private final long maxExpandedBytes;

    SkillArtifactMaterializer(SkillArtifactFetcher fetcher, long maxExpandedBytes) {
        this.fetcher = fetcher;
        if (maxExpandedBytes <= 0) throw new IllegalArgumentException("maxExpandedBytes must be positive");
        this.maxExpandedBytes = maxExpandedBytes;
    }

    Path materialize(ResourceBindingLoader.ResourceBinding binding, Path versionDirectory) {
        if (fetcher == null) throw new IllegalStateException("Skill artifact fetcher is required");
        Path archive = fetcher.fetch(binding, versionDirectory);
        Path skillsRoot = versionDirectory.toAbsolutePath().normalize().resolve("skills").normalize();
        Path target = skillsRoot.resolve(SkillArtifactFetcher.artifactDirectoryName(binding)).normalize();
        if (!target.startsWith(skillsRoot)) throw new IllegalArgumentException("Skill directory path is unsafe");
        try {
            Files.createDirectories(skillsRoot);
            if (Files.isDirectory(target)) {
                recheck(target);
                return target;
            }
            Path staging = Files.createTempDirectory(skillsRoot, ".skill-stage-");
            try {
                extract(archive, staging);
                Path extractedRoot = locateSkillRoot(staging);
                recheck(extractedRoot);
                Files.move(extractedRoot, target, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Path extractedRoot = locateSkillRoot(staging);
                recheck(extractedRoot);
                Files.move(extractedRoot, target);
                return target;
            } finally {
                deleteTree(staging);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Skill artifact materialization failed", error);
        }
    }

    private void extract(Path archive, Path staging) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive))) {
            raw.mark(4);
            byte[] signature = raw.readNBytes(4);
            raw.reset();
            if (isZip(signature)) {
                extractZip(new ZipInputStream(raw), staging);
            } else if (isGzip(signature)) {
                extractTar(new GZIPInputStream(raw), staging);
            } else {
                extractTar(raw, staging);
            }
        }
    }

    private void extractZip(ZipInputStream input, Path staging) throws IOException {
        Set<Path> entries = new HashSet<>();
        long[] total = {0};
        try (ZipInputStream zip = input) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = safeEntry(staging, entry.getName());
                if (!entries.add(target)) throw unsafe("duplicate archive path");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    writeEntry(zip, target, total);
                }
                zip.closeEntry();
            }
        }
    }

    private void extractTar(InputStream input, Path staging) throws IOException {
        Set<Path> entries = new HashSet<>();
        byte[] header = new byte[TAR_BLOCK_BYTES];
        long[] total = {0};
        while (true) {
            int read = readAtMost(input, header);
            if (read == 0 || isZeroBlock(header)) return;
            if (read != TAR_BLOCK_BYTES) throw new EOFException("incomplete tar header");
            String name = field(header, 0, 100);
            int type = header[156] & 0xff;
            long size = tarSize(header);
            if (size < 0 || size > maxExpandedBytes) {
                throw unsafe("expanded Skill package exceeds the configured limit");
            }
            if (type == 'x' || type == 'g') {
                if (total[0] > maxExpandedBytes - size) {
                    throw unsafe("expanded Skill package exceeds the configured limit");
                }
                skipExact(input, size);
                skipExact(input, padding(size));
                total[0] += size;
                continue;
            }
            Path target = safeEntry(staging, name);
            if (!entries.add(target)) throw unsafe("duplicate archive path");
            if (type == '5') {
                Files.createDirectories(target);
                skipExact(input, size);
            } else if (type == 0 || type == '0') {
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    copyLimited(input, output, size, total);
                }
            } else {
                throw unsafe("unsupported archive entry type");
            }
            skipExact(input, padding(size));
            if (total[0] > maxExpandedBytes) throw unsafe("expanded Skill package exceeds the configured limit");
        }
    }

    private void writeEntry(InputStream input, Path target, long[] total) throws IOException {
        Files.createDirectories(target.getParent());
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total[0] += read;
                if (total[0] > maxExpandedBytes) throw unsafe("expanded Skill package exceeds the configured limit");
                output.write(buffer, 0, read);
            }
        }
    }

    private void copyLimited(InputStream input, java.io.OutputStream output, long size, long[] total)
            throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("incomplete tar entry");
            total[0] += read;
            if (total[0] > maxExpandedBytes) throw unsafe("expanded Skill package exceeds the configured limit");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private Path locateSkillRoot(Path staging) throws IOException {
        Path rootSkill = staging.resolve("SKILL.md");
        if (Files.isRegularFile(rootSkill)) return staging;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(staging)) {
            Path only = null;
            for (Path child : children) {
                if (only != null || !Files.isDirectory(child)) throw unsafe("Skill archive must have one root directory");
                only = child;
            }
            if (only == null || !Files.isRegularFile(only.resolve("SKILL.md"))) {
                throw unsafe("Skill archive must contain SKILL.md");
            }
            return only;
        }
    }

    private void recheck(Path root) {
        try {
            if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) throw unsafe("Skill root is unsafe");
            long[] count = {0};
            try (var paths = Files.walk(root)) {
                paths.forEach(path -> {
                    if (Files.isSymbolicLink(path) || (!Files.isDirectory(path) && !Files.isRegularFile(path))) {
                        throw unsafe("Skill package contains an unsafe filesystem entry");
                    }
                    if (Files.isRegularFile(path)) {
                        try { count[0] = Math.addExact(count[0], Files.size(path)); }
                        catch (IOException | ArithmeticException error) { throw unsafe("Skill package size cannot be verified"); }
                        if (count[0] > maxExpandedBytes) throw unsafe("expanded Skill package exceeds the configured limit");
                    }
                });
            }
            String markdown = Files.readString(root.resolve("SKILL.md"), StandardCharsets.UTF_8);
            SkillUtil.createFrom(markdown, null, "agentteams");
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalArgumentException("Skill package scan recheck failed", error);
        }
    }

    private static Path safeEntry(Path staging, String name) {
        if (name == null || name.isBlank() || name.contains("\\") || name.startsWith("/")) {
            throw unsafe("unsafe archive path");
        }
        Path relative = Path.of(name).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isBlank()
                || ".".equals(relative.toString()) || name.contains(":")) {
            throw unsafe("unsafe archive path");
        }
        Path target = staging.resolve(relative).normalize();
        if (!target.startsWith(staging)) throw unsafe("unsafe archive path");
        return target;
    }

    private static IllegalArgumentException unsafe(String message) {
        return new IllegalArgumentException(message);
    }

    private static long tarSize(byte[] header) {
        String value = field(header, 124, 12).replace("\0", "").trim();
        if (value.isEmpty()) return 0;
        try { return Long.parseLong(value, 8); }
        catch (NumberFormatException error) { throw unsafe("invalid tar entry size"); }
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long padding(long size) { return (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES; }

    private static void skipExact(InputStream input, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) throw new EOFException("incomplete archive");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static int readAtMost(InputStream input, byte[] target) throws IOException {
        int total = 0;
        while (total < target.length) {
            int read = input.read(target, total, target.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static boolean isZeroBlock(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }

    private static boolean isZip(byte[] signature) {
        return signature.length >= 4 && signature[0] == 'P' && signature[1] == 'K'
                && (signature[2] == 3 || signature[2] == 5 || signature[2] == 7)
                && (signature[3] == 4 || signature[3] == 6 || signature[3] == 8);
    }

    private static boolean isGzip(byte[] signature) {
        return signature.length >= 2 && (signature[0] & 0xff) == 0x1f && (signature[1] & 0xff) == 0x8b;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
