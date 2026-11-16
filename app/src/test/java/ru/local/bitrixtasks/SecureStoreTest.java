package ru.local.bitrixtasks;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SecureStoreTest {
    private static final String WEBHOOK = "https://bitrix.example/rest/97/secret/";

    // Роботект не предоставляет AndroidKeyStore: тесты проверяют учёт save/load через
    // тестовый шов SecureStore с постоянным AES-ключом. Аппаратный keystore проверяется на устройстве.
    private static SecretKey testKeystoreKey() throws Exception {
        return SecureStore.derivedKey("keystore-test-salt".getBytes(StandardCharsets.UTF_8));
    }

    @Test @Config(sdk = {23, 34}) public void modernAndroidEncryptsWithKeystore() throws Exception {
        SecureStore.setKeystoreProviderForTests(SecureStoreTest::testKeystoreKey);
        try {
            Context c = RuntimeEnvironment.getApplication();
            SecureStore store = new SecureStore(c);
            assertTrue(store.clear());
            store.save(WEBHOOK);
            SharedPreferences prefs = c.getSharedPreferences("connection", Context.MODE_PRIVATE);
            assertEquals("keystore", prefs.getString("scheme", null));
            assertNotEquals(WEBHOOK, prefs.getString("data", null));
            assertEquals(WEBHOOK, store.load());
            assertTrue(store.clear());
            assertNull(store.load());
        } finally { SecureStore.setKeystoreProviderForTests(null); }
    }

    @Test public void legacyAndroid44RecordIsReadableAndNeverPlain() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = c.getSharedPreferences("connection", Context.MODE_PRIVATE);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, SecureStore.derivedKey(salt));
        prefs.edit().clear()
                .putString("scheme", "derived")
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(cipher.doFinal(WEBHOOK.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit();
        assertEquals(WEBHOOK, new SecureStore(c).load());
    }

    @Test public void derivedKeyIsStableAnd256Bits() throws Exception {
        byte[] salt = new byte[16];
        java.util.Arrays.fill(salt, (byte) 7);
        SecretKey a = SecureStore.derivedKey(salt), b = SecureStore.derivedKey(salt.clone());
        assertArrayEquals(a.getEncoded(), b.getEncoded());
        assertEquals("AES", a.getAlgorithm());
        assertEquals(32, a.getEncoded().length);
    }

    @Test @Config(sdk = {23, 34}) public void legacyAndKeystoreRecordsDoNotMix() throws Exception {
        SecureStore.setKeystoreProviderForTests(SecureStoreTest::testKeystoreKey);
        try {
            Context c = RuntimeEnvironment.getApplication();
            assertTrue(c.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit());
            SecureStore store = new SecureStore(c);
            store.save(WEBHOOK);
            SharedPreferences prefs = c.getSharedPreferences("connection", Context.MODE_PRIVATE);
            prefs.edit().putString("scheme", "derived").putString("salt", "AAAAAAAAAAAAAAAAAAAAAA==").commit();
            // Метка scheme=derived с неверным salt не должна вернуть старый секрет: расшифровка срывается.
            try { assertNotEquals(WEBHOOK, store.load()); fail("Ожидался срыв расшифровки"); }
            catch (java.security.GeneralSecurityException expected) { }
            store.save(WEBHOOK);
            assertEquals(WEBHOOK, store.load());
        } finally { SecureStore.setKeystoreProviderForTests(null); }
    }
}
