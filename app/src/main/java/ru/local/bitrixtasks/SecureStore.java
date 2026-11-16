package ru.local.bitrixtasks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Хранит секрет вебхула в зашифрованном виде.
 * Android 6+ использует аппаратный Android Keystore. На Android 4.4–5.x Keystore нет,
 * поэтому применяется AES-GCM с ключом PBKDF2: защита слабее аппаратной,
 * но секрет по-прежнему не лежит в открытом виде в приватных данных приложения.
 */
final class SecureStore {
    private static final String ALIAS = "b24_webhook_v1";
    private static final String SCHEME_KEYSTORE = "keystore";
    private static final String SCHEME_DERIVED = "derived";
    private static final String DERIVED_PASSPHRASE = "puls.bitrixtasks.v1";
    private final SharedPreferences prefs;
    SecureStore(Context c) { prefs = c.getSharedPreferences("connection", Context.MODE_PRIVATE); }

    static SecretKey derivedKey(byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(DERIVED_PASSPHRASE.toCharArray(), salt, 20000, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    // Тестовый шов: на устройстве всегда null и работает аппаратный Android Keystore.
    // Robolectric не предоставляет AndroidKeyStore, поэтому тесты подставляют свой AES-ключ.
    interface KeystoreKeyProvider { SecretKey key() throws Exception; }
    private static volatile KeystoreKeyProvider testKeystore;
    static void setKeystoreProviderForTests(KeystoreKeyProvider provider) { testKeystore = provider; }

    private static SecretKey keystoreKey() throws Exception {
        KeystoreKeyProvider test = testKeystore;
        return test != null ? test.key() : Api23KeyStore.key();
    }

    void save(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SharedPreferences.Editor editor = prefs.edit();
        if (Build.VERSION.SDK_INT >= 23) {
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
            editor.putString("scheme", SCHEME_KEYSTORE);
        } else {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            cipher.init(Cipher.ENCRYPT_MODE, derivedKey(salt));
            editor.putString("scheme", SCHEME_DERIVED)
                  .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        }
        editor.putString("data", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
              .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        if (!editor.commit()) throw new Exception("Storage unavailable");
    }

    String load() throws Exception {
        if (!prefs.contains("data")) return null;
        SecretKey key;
        if (SCHEME_DERIVED.equals(prefs.getString("scheme", SCHEME_KEYSTORE)))
            key = derivedKey(Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP));
        else key = keystoreKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("data", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    // Confirm deletion before reporting that the portal is disconnected.
    boolean clear() { return prefs.edit().clear().commit(); }

    // Вложенный класс с API Android 6+ загружается только на этих версиях: на 4.4 он никогда не вызывается.
    // Новый API здесь намеренно без локальной проверки версии — весь класс недоступен ниже API 23.
    @android.annotation.SuppressLint("NewApi")
    private static final class Api23KeyStore {
        static SecretKey key() throws Exception {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                javax.crypto.KeyGenerator gen = javax.crypto.KeyGenerator.getInstance(
                        android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                gen.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build());
                return gen.generateKey();
            }
            return (SecretKey) ks.getKey(ALIAS, null);
        }
    }
}
