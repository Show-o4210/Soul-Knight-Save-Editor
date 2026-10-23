package com.example.soul_knight_save_editor.unlock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only app_process entry point for old Android images without a SHA-256 applet. */
public final class LegacySha256 {
    // Keep in sync with UnlockEngine.MAX_BYTES without loading any Kotlin or Android classes.
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern PATH = Pattern.compile(
            "^/data/(?:user/([0-9]+)|data)/([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/"
                    + "(?:files/(?:[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+\\.data(?:\\.new)?(?:_temp)?"
                    + "|shared_prefs/[a-zA-Z0-9_.-]+\\.xml)"
                    + "(?:\\.unlock-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp)?$");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LegacySha256() { }

    public static void main(String[] args) {
        try {
            System.out.println(output(args));
        } catch (Exception error) {
            // Neither paths nor exception text may expose account identifiers in diagnostics.
            System.err.println("Legacy SHA-256 failed");
            System.exit(1);
        }
    }

    static String output(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args == null || args.length != 1) throw new IllegalArgumentException();
        if ("--self-test".equals(args[0])) {
            return hex(MessageDigest.getInstance("SHA-256").digest());
        }
        String path = args[0];
        if (!allowedPath(path)) throw new IllegalArgumentException();
        File file = new File(path);
        String canonical = file.getCanonicalPath();
        if (!sameSavePath(path, canonical) || !file.isFile()) throw new IOException();
        long expectedLength = file.length();
        if (expectedLength < 1 || expectedLength > MAX_BYTES) throw new IOException();
        String digest;
        try (InputStream input = new FileInputStream(file)) {
            digest = digest(input, expectedLength);
        }
        if (file.length() != expectedLength || !sameSavePath(path, file.getCanonicalPath())) throw new IOException();
        return digest + "  " + path;
    }

    static boolean allowedPath(String path) {
        if (path == null || path.length() > 600 || path.contains("..")) return false;
        Matcher matcher = PATH.matcher(path);
        if (!matcher.matches()) return false;
        if (matcher.group(1) != null) {
            try {
                Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    static boolean sameSavePath(String expected, String actual) {
        return expected.equals(actual) || alias(expected).equals(alias(actual));
    }

    private static String alias(String path) {
        return path.startsWith("/data/user/0/") ? "/data/data/" + path.substring(13) : path;
    }

    static String digest(InputStream input, long expectedLength) throws IOException, NoSuchAlgorithmException {
        if (expectedLength < 1 || expectedLength > MAX_BYTES) throw new IOException();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > expectedLength || total > MAX_BYTES) throw new IOException();
            digest.update(buffer, 0, count);
        }
        if (total != expectedLength) throw new IOException();
        return hex(digest.digest());
    }

    private static String hex(byte[] digest) {
        char[] result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            result[i * 2] = HEX[(digest[i] & 0xff) >>> 4];
            result[i * 2 + 1] = HEX[digest[i] & 0xf];
        }
        return new String(result);
    }
}
