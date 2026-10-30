package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Known-answer vectors for in-tree XXH64 (seed 0), matching cyan4973/xxHash. */
class XXH64Test {

    @Test
    void knownAnswersSeed0() {
        assertEquals(0xEF46DB3751D8E999L, XXH64.hash(new byte[0]));
        assertEquals(0xD24EC4F1A98C6E5BL, XXH64.hash("a".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x65F708CA92D04A61L, XXH64.hash("ab".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x44BC2CF5AD770999L, XXH64.hash("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x3F5FC178A81867E7L, XXH64.hash("0123456789".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x5C5B90C34E376D0BL, XXH64.hash("0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x0B242D361FDA71BCL,
                XXH64.hash("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)));
        assertEquals(XXH64.hash("abc".getBytes(StandardCharsets.UTF_8)), XXH64.hashUtf8("abc"));
    }

    @Test
    void remainderUnsignedMatchesStickyShardMath() {
        long h = XXH64.hashUtf8("/tmp/ledger/part-0");
        int n = 8;
        int shard = (int) Long.remainderUnsigned(h, n);
        assertTrue(shard >= 0 && shard < n);
    }
}
