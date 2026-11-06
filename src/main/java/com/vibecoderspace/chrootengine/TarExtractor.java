package com.vibecoderspace.chrootengine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Extracts a (gzipped) tar archive without external tools.
 *
 * <p>Android denies {@code link(2)} to app processes, so a hard link becomes a
 * real hard link when the filesystem allows it and a symbolic link otherwise.
 * This is enough for the Ubuntu base image: its few hard links become
 * equivalent symbolic links when the app cannot create hard links.</p>
 *
 * <p>Only the entry types present in distro base images are handled: regular
 * files, directories, symbolic links and hard links. PAX and GNU long-name
 * headers are understood; device nodes and FIFOs are skipped because they
 * cannot be created by an app anyway.</p>
 *
 * <p>Free of Android dependencies so the parser can be unit tested on the
 * host.</p>
 */
public final class TarExtractor {

    private static final int BLOCK = 512;

    private TarExtractor() {
    }

    /** Extracts a {@code .tar.gz} archive into {@code destination}. */
    public static void extractGzip(File archive, File destination) throws IOException {
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive), 64 * 1024);
             GZIPInputStream gzip = new GZIPInputStream(file, 64 * 1024)) {
            extract(gzip, destination);
        }
    }

    /** Extracts an uncompressed tar stream into {@code destination}. */
    public static void extract(InputStream tar, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs() && !destination.isDirectory()) {
            throw new IOException("Could not create " + destination);
        }
        Path root = destination.getCanonicalFile().toPath();

        byte[] header = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        Map<String, String> pax = new HashMap<>();

        while (true) {
            int read = readFully(tar, header, 0, BLOCK);
            if (read == 0) {
                break;
            }
            if (read != BLOCK) {
                throw new IOException("Truncated tar header");
            }
            if (isZero(header)) {
                break;
            }

            String name = string(header, 0, 100);
            long mode = number(header, 100, 8);
            long size = number(header, 124, 12);
            char type = (char) (header[156] & 0xff);
            String link = string(header, 157, 100);
            String prefix = string(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            // Metadata entries describe the next real entry.
            if (type == 'x' || type == 'g') {
                byte[] data = readData(tar, size);
                if (type == 'x') {
                    parsePax(data, pax);
                }
                continue;
            }
            if (type == 'L') {
                longName = trimNul(readData(tar, size));
                continue;
            }
            if (type == 'K') {
                longLink = trimNul(readData(tar, size));
                continue;
            }

            if (pax.containsKey("path")) {
                name = pax.get("path");
            }
            if (pax.containsKey("linkpath")) {
                link = pax.get("linkpath");
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (longLink != null) {
                link = longLink;
                longLink = null;
            }
            pax.clear();

            File target = resolve(root, name);
            switch (type) {
                case '0':
                case 0:
                case '7':
                    writeFile(tar, target, size, mode);
                    break;
                case '5':
                    ensureDirectory(target, mode);
                    skipData(tar, size);
                    break;
                case '2':
                    createSymbolicLink(target, link);
                    skipData(tar, size);
                    break;
                case '1':
                    createHardLink(root, target, link);
                    skipData(tar, size);
                    break;
                default:
                    // Character/block devices, FIFOs and anything unknown.
                    skipData(tar, size);
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ entries

    private static void writeFile(
        InputStream tar, File target, long size, long mode) throws IOException {
        ensureParent(target);
        deleteExisting(target);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 64 * 1024)) {
            copyExactly(tar, out, size);
        }
        applyMode(target, mode);
    }

    private static void ensureDirectory(File target, long mode) throws IOException {
        if (Files.isSymbolicLink(target.toPath()) || (target.exists() && !target.isDirectory())) {
            deleteExisting(target);
        }
        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            throw new IOException("Could not create directory " + target);
        }
        applyMode(target, mode);
    }

    private static void createSymbolicLink(File target, String link) throws IOException {
        if (link.isEmpty()) {
            throw new IOException("Symbolic link without a target: " + target);
        }
        ensureParent(target);
        deleteExisting(target);
        Files.createSymbolicLink(target.toPath(), Paths.get(link));
    }

    private static void createHardLink(Path root, File target, String link) throws IOException {
        if (link.isEmpty()) {
            throw new IOException("Hard link without a target: " + target);
        }
        File source = resolve(root, link);
        ensureParent(target);
        deleteExisting(target);
        try {
            Files.createLink(target.toPath(), source.toPath());
        } catch (Exception e) {
            // Android's SELinux policy refuses link(2) for apps. Emulate the
            // hard link with a symbolic link to the guest path, which is what
            // PRoot's and noptrace's link2symlink do as well.
            String linkTarget = "/" + stripLeadingSlashes(link);
            Files.createSymbolicLink(target.toPath(), Paths.get(linkTarget));
        }
    }

    private static File resolve(Path root, String name) throws IOException {
        String normalized = stripLeadingSlashes(name.replace('\\', '/'));
        if (normalized.isEmpty()) {
            throw new IOException("Tar entry without a path");
        }
        Path candidate = root.resolve(normalized).normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("Tar entry escapes the destination: " + name);
        }
        // Also guard against an existing symbolic link in the prefix.
        if (!candidate.toFile().getCanonicalFile().toPath().startsWith(root)) {
            throw new IOException("Tar entry escapes the destination: " + name);
        }
        return candidate.toFile();
    }

    // -------------------------------------------------------------- permissions

    private static void applyMode(File file, long mode) {
        int bits = (int) (mode & 0777);
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        addPermission(permissions, bits, 0400, PosixFilePermission.OWNER_READ);
        addPermission(permissions, bits, 0200, PosixFilePermission.OWNER_WRITE);
        addPermission(permissions, bits, 0100, PosixFilePermission.OWNER_EXECUTE);
        addPermission(permissions, bits, 0040, PosixFilePermission.GROUP_READ);
        addPermission(permissions, bits, 0020, PosixFilePermission.GROUP_WRITE);
        addPermission(permissions, bits, 0010, PosixFilePermission.GROUP_EXECUTE);
        addPermission(permissions, bits, 0004, PosixFilePermission.OTHERS_READ);
        addPermission(permissions, bits, 0002, PosixFilePermission.OTHERS_WRITE);
        addPermission(permissions, bits, 0001, PosixFilePermission.OTHERS_EXECUTE);

        try {
            Files.setPosixFilePermissions(file.toPath(), permissions);
        } catch (Exception e) {
            // POSIX attributes are not available on every volume.
            if ((bits & 0111) != 0) {
                //noinspection ResultOfMethodCallIgnored
                file.setExecutable(true, false);
            }
        }
    }

    private static void addPermission(
        Set<PosixFilePermission> permissions, int bits, int mask, PosixFilePermission permission) {
        if ((bits & mask) != 0) {
            permissions.add(permission);
        }
    }

    // --------------------------------------------------------------------- i/o

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IOException("Unsupported tar entry size " + size);
        }
        byte[] data = new byte[(int) size];
        if (readFully(in, data, 0, data.length) != data.length) {
            throw new IOException("Truncated tar entry");
        }
        skipPadding(in, size);
        return data;
    }

    private static void copyExactly(InputStream in, OutputStream out, long size)
        throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int want = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                throw new IOException("Truncated tar entry");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        skipPadding(in, size);
    }

    private static void skipData(InputStream in, long size) throws IOException {
        skipFully(in, size);
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        skipFully(in, (BLOCK - (size % BLOCK)) % BLOCK);
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] buffer = new byte[4096];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated tar stream");
            }
            remaining -= read;
        }
    }

    private static int readFully(InputStream in, byte[] buffer, int offset, int length)
        throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    // ------------------------------------------------------------------ parsing

    private static boolean isZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String string(byte[] block, int offset, int length) {
        int end = offset;
        while (end < offset + length && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long number(byte[] block, int offset, int length) {
        int first = block[offset] & 0xff;
        if ((first & 0x80) != 0) {
            // GNU base-256 encoding (used for very large values).
            long value = first & 0x7f;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (block[offset + i] & 0xff);
            }
            return value;
        }

        long value = 0;
        boolean seen = false;
        for (int i = 0; i < length; i++) {
            int c = block[offset + i] & 0xff;
            if (c == 0 || c == ' ') {
                if (seen) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                break;
            }
            value = (value << 3) + (c - '0');
            seen = true;
        }
        return value;
    }

    private static void parsePax(byte[] data, Map<String, String> values) {
        int position = 0;
        while (position < data.length) {
            int space = indexOf(data, (byte) ' ', position, data.length);
            if (space < 0) {
                return;
            }
            int length;
            try {
                length = Integer.parseInt(
                    new String(data, position, space - position, StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException e) {
                return;
            }
            int end = position + length;
            if (length <= 0 || end > data.length) {
                return;
            }
            int equals = indexOf(data, (byte) '=', space + 1, end);
            if (equals < 0) {
                return;
            }
            String key = new String(data, space + 1, equals - space - 1, StandardCharsets.UTF_8);
            String value = new String(data, equals + 1, end - equals - 1, StandardCharsets.UTF_8);
            if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            values.put(key, value);
            position = end;
        }
    }

    private static int indexOf(byte[] data, byte value, int from, int to) {
        for (int i = from; i < to; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static String trimNul(byte[] data) {
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------- helpers

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create " + parent);
        }
    }

    private static void deleteExisting(File file) throws IOException {
        Path path = file.toPath();
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        if (file.isDirectory()) {
            throw new IOException("Refusing to replace directory " + file);
        }
        Files.deleteIfExists(path);
    }

    private static String stripLeadingSlashes(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }
}
