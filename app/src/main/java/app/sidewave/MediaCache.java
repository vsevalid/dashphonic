package app.sidewave;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class MediaCache {
    private static final int MAGIC = 0x444d4331;
    private static final long MISS_TTL = 3L * 24 * 60 * 60 * 1000;
    private final File root;

    MediaCache(File root) { this.root = root; }

    String get(String kind, String artist, String title, int duration, long now) {
        File folder = new File(root, kind);
        File entry = new File(folder, key(artist, title));
        if (entry.isFile()) {
            try {
                byte[] bytes = Files.readAllBytes(entry.toPath());
                if (bytes.length >= 8 && ByteBuffer.wrap(bytes).getInt() == MAGIC) {
                    int storedDuration = ByteBuffer.wrap(bytes).getInt(4);
                    String value = new String(bytes, 8, bytes.length - 8, StandardCharsets.UTF_8);
                    boolean matches = kind.equals("art") || duration == 0 || storedDuration == 0 || Math.abs(duration - storedDuration) <= 4;
                    if (!matches) return null;
                    if (matches && usable(value, entry.lastModified(), now)) {
                        if (!value.isEmpty()) entry.setLastModified(now);
                        return value;
                    }
                }
            } catch (IOException ignored) { /* A cache read failure must not prevent a fresh lookup. */ }
        }
        // Existing installations used a duration-dependent key, including zero before AVRCP supplied it.
        for (int seconds : duration == 0 ? new int[]{0} : new int[]{duration, 0}) {
            File legacy = new File(folder, hash(kind + "\n" + artist + "\n" + title + "\n" + seconds));
            if (!legacy.isFile()) continue;
            try {
                String value = new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8);
                if (!usable(value, legacy.lastModified(), now)) continue;
                if (put(kind, artist, title, seconds, value)) legacy.delete();
                return value;
            } catch (IOException ignored) { /* Try the other legacy key before using the network. */ }
        }
        return null;
    }

    boolean put(String kind, String artist, String title, int duration, String value) {
        File folder = new File(root, kind);
        if (!folder.isDirectory() && !folder.mkdirs()) return false;
        File target = new File(folder, key(artist, title));
        File pending = new File(folder, target.getName() + ".tmp");
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = ByteBuffer.allocate(8 + body.length).putInt(MAGIC).putInt(duration).put(body).array();
        try {
            Files.write(pending.toPath(), bytes);
            if (pending.renameTo(target)) return true;
            pending.delete();
        } catch (IOException ignored) { pending.delete(); }
        return false;
    }

    private static boolean usable(String value, long saved, long now) {
        // Positive results survive TTL expiry and clock corrections; bounded LRU handles eviction.
        return !value.isEmpty() || (now >= saved && now - saved <= MISS_TTL);
    }

    private static String key(String artist, String title) {
        return hash(artist.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + "\n" +
                title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")) + ".cache";
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
