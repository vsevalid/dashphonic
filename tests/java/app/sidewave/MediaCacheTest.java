package app.sidewave;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Objects;

public final class MediaCacheTest {
    private static int checks;
    private static void expect(Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Check " + checks + ": expected " + expected + ", got " + actual);
    }
    private static String legacyKey(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        MediaCache cache = new MediaCache(root);
        long now = System.currentTimeMillis();
        expect(null, cache.get("art", "Artist", "Song", 240, now));
        String art = "data:image/jpeg;base64,QUJD";
        String lrc = "[00:01.00]Saved lyrics";
        cache.put("art", "Artist", "Song", 240, art);
        cache.put("lyrics", "Artist", "Song", 240, lrc);
        // No connectivity or HTTP collaborator exists: these reads are entirely from disk.
        expect(art, cache.get("art", "Artist", "Song", 0, now));
        expect(art, cache.get("art", " artist ", " SONG ", 245, now));
        expect(lrc, cache.get("lyrics", "Artist", "Song", 0, now));
        expect(lrc, cache.get("lyrics", "Artist", "Song", 244, now));
        expect(null, cache.get("lyrics", "Artist", "Song", 245, now));
        for (String kind : new String[]{"art", "lyrics"}) {
            for (File file : new File(root, kind).listFiles()) file.setLastModified(now - 60L * 86400000);
        }
        expect(art, cache.get("art", "Artist", "Song", 240, now));
        expect(lrc, cache.get("lyrics", "Artist", "Song", 240, now));
        expect(lrc, cache.get("lyrics", "Artist", "Song", 240, now - 86400000));
        cache.put("lyrics", "Artist", "Missing", 240, "");
        now = System.currentTimeMillis();
        expect("", cache.get("lyrics", "Artist", "Missing", 240, now));
        expect(null, cache.get("lyrics", "Artist", "Missing", 240, now + 4L * 86400000));
        cache.put("art", "Artist", "Song", 240, "replacement");
        expect("replacement", cache.get("art", "Artist", "Song", 240, now));
        File legacy = new File(new File(root, "art"), legacyKey("art\nLegacy Artist\nLegacy Song\n0"));
        Files.write(legacy.toPath(), art.getBytes(StandardCharsets.UTF_8));
        legacy.setLastModified(now - 60L * 86400000);
        expect(art, cache.get("art", "Legacy Artist", "Legacy Song", 240, now));
        legacy.delete();
        expect(art, cache.get("art", "legacy artist", "legacy song", 0, now));
        File broken = new File(root, "not-a-folder");
        Files.write(broken.toPath(), new byte[]{1});
        new MediaCache(broken).put("art", "Artist", "Song", 1, art);
        System.out.println("Media cache: " + checks + " assertions passed");
    }
}
