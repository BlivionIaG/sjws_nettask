package sjws_nettask.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;

class SwappableKeyManagerTest {

    @Test
    void swapIsVisibleToTheNextHandshakeLookup() {
        var first = TlsMaterial.selfSigned("one.example");
        var second = TlsMaterial.selfSigned("two.example");
        var keys = new SwappableKeyManager(first);
        assertEquals(SwappableKeyManager.ALIAS, keys.chooseEngineServerAlias("EC", null, null));
        assertEquals(SwappableKeyManager.ALIAS, keys.chooseServerAlias("ECDSA", null, null));
        assertEquals(SwappableKeyManager.ALIAS, keys.chooseEngineServerAlias("EC_EC", null, null));
        assertNull(keys.chooseServerAlias("RSA", null, null));
        assertArrayEquals(new String[] {SwappableKeyManager.ALIAS}, keys.getServerAliases("EC", null));
        assertEquals(0, keys.getServerAliases("RSA", null).length);
        assertEquals(0, keys.getClientAliases("EC", null).length);
        assertNull(keys.chooseClientAlias(new String[] {"EC"}, null, null));
        assertNull(keys.chooseEngineClientAlias(new String[] {"EC"}, null, null));
        assertNull(keys.getCertificateChain("other"));
        assertNull(keys.getPrivateKey(null));
        X509Certificate[] chain = keys.getCertificateChain(SwappableKeyManager.ALIAS);
        assertEquals(first.chain()[0], chain[0]);
        assertSame(first.key(), keys.getPrivateKey(SwappableKeyManager.ALIAS));
        keys.set(second);
        assertEquals(second.chain()[0], keys.getCertificateChain(SwappableKeyManager.ALIAS)[0]);
        assertEquals(SwappableKeyManager.ALIAS, keys.chooseServerAlias(null, null, null));
    }
}
