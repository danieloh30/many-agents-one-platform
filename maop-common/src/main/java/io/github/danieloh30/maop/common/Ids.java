package io.github.danieloh30.maop.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/** Identifier helpers: time-ordered UUIDv7 for new entities, name-based UUIDv5 for deterministic event IDs. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** RFC 9562 UUID version 7 (48-bit Unix millisecond timestamp + random bits). */
    public static UUID v7() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | 0x7000L | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /** RFC 9562 UUID version 5 (SHA-1, name-based) in the given namespace. */
    public static UUID v5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(namespace));
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            hash[6] &= 0x0f;
            hash[6] |= 0x50;
            hash[8] &= 0x3f;
            hash[8] |= (byte) 0x80;
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] toBytes(UUID u) {
        byte[] b = new byte[16];
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (msb >>> (8 * (7 - i)));
            b[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return b;
    }
}
