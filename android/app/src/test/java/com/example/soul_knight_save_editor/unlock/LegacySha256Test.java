package com.example.soul_knight_save_editor.unlock;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;

public class LegacySha256Test {
    private static final String ROOT = "/data/user/0/com.test.game/";
    private static final String TOKEN = "12345678-1234-1234-1234-123456789abc";

    @Test public void selfTestHasAnExactDependencyFreeOutput() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                LegacySha256.output(new String[] {"--self-test"}));
    }

    @Test public void standardDigestCoversBinaryAndMultipleBuffers() throws Exception {
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                LegacySha256.digest(new ByteArrayInputStream(abc), abc.length));
        byte[] binary = new byte[LegacySha256.MAX_BYTES];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) (i % 251);
        String actual = LegacySha256.digest(new ByteArrayInputStream(binary), binary.length);
        assertEquals(64, actual.length());
        assertTrue(actual.matches("[0-9a-f]{64}"));
        assertEquals(toHex(MessageDigest.getInstance("SHA-256").digest(binary)), actual);
    }

    @Test public void emptyOversizedTruncatedAndGrowingFilesAreRejected() {
        assertThrows(IOException.class, () -> LegacySha256.digest(new ByteArrayInputStream(new byte[0]), 0));
        assertThrows(IOException.class, () -> LegacySha256.digest(new ByteArrayInputStream(new byte[1]), LegacySha256.MAX_BYTES + 1L));
        assertThrows(IOException.class, () -> LegacySha256.digest(new ByteArrayInputStream(new byte[2]), 3));
        assertThrows(IOException.class, () -> LegacySha256.digest(new ByteArrayInputStream(new byte[3]), 2));
    }

    @Test public void onlyExistingSaveShapesAndExactTransactionTempSuffixAreAllowed() {
        for (String path : new String[] {ROOT + "files/game.data", ROOT + "files/account/item_data_123_.data",
                ROOT + "shared_prefs/game.xml", "/data/data/com.test.game/files/opaque.data",
                "/data/user/10/com.test.game/shared_prefs/extra.xml",
                ROOT + "files/game.data.unlock-" + TOKEN + ".tmp",
                ROOT + "shared_prefs/game.xml.unlock-" + TOKEN + ".tmp",
                ROOT + "files/item_data_123_.data.new", ROOT + "files/item_data_123_.data_temp",
                ROOT + "files/item_data_123_.data.new_temp",
                ROOT + "files/item_data_123_.data.new.unlock-" + TOKEN + ".tmp"}) {
            assertTrue(path, LegacySha256.allowedPath(path));
        }
        for (String path : new String[] {"/etc/passwd", ROOT + "files/game.data/child", ROOT + "files/deep/nested/game.data",
                ROOT + "files/a../game.data", ROOT + "files/../game.data", ROOT + "files/game.data.new.bak",
                ROOT + "files/game.data_temp.new", ROOT + "files/game.data.new_temp_temp",
                ROOT + "shared_prefs/extra.data", ROOT + "files/extra.xml", ROOT + "files/game.data.unlock-bad.tmp",
                ROOT + "files/game.data.unlock-" + TOKEN + ".tmp.tmp", ROOT + "files/game.data\n",
                "/data/user/2147483648/com.test.game/files/game.data", "/data/user/0/com.test;echo/files/game.data",
                "/data/user/0/com/files/game.data", ROOT + "files/" + repeat('a', 600) + ".data"}) {
            assertFalse(path, LegacySha256.allowedPath(path));
        }
        assertFalse(LegacySha256.allowedPath(null));
    }

    @Test public void canonicalBoundaryPermitsOnlyTheUserZeroDataAlias() {
        String path = ROOT + "files/game.data";
        assertTrue(LegacySha256.sameSavePath(path, path));
        assertTrue(LegacySha256.sameSavePath(path, "/data/data/com.test.game/files/game.data"));
        assertFalse(LegacySha256.sameSavePath(path, "/data/user/10/com.test.game/files/game.data"));
        assertFalse(LegacySha256.sameSavePath(path, "/data/user/0/com.other.game/files/game.data"));
        assertFalse(LegacySha256.sameSavePath(path, "/data/user/0/com.test.game/files/other.data"));
        assertFalse(LegacySha256.sameSavePath(path, "/etc/passwd"));
    }

    @Test public void invalidArgumentsFailBeforeOpeningFiles() {
        for (String[] args : new String[][] {new String[0], {"--self-test", "extra"}, {"/etc/passwd"}, {null}}) {
            assertThrows(IllegalArgumentException.class, () -> LegacySha256.output(args));
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static String toHex(byte[] value) {
        StringBuilder hex = new StringBuilder();
        for (byte item : value) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return hex.toString();
    }
}
