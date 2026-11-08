package com.promptengine.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the API key with a hardware-backed Android Keystore key so it never
 * lands on disk as plaintext.
 */
public final class Crypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "prompt_engine_master_v1";
    private static final String ENC_PREFIX = "enc1:";
    private static final String PLAIN_PREFIX = "raw1:";

    private Crypto() {
    }

    private static SecretKey masterKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey());
            byte[] iv = cipher.getIV();
            byte[] sealed = cipher.doFinal(plain.getBytes("UTF-8"));
            return ENC_PREFIX
                    + Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(sealed, Base64.NO_WRAP);
        } catch (Throwable failure) {
            // Never lose the key because a device Keystore misbehaves.
            return PLAIN_PREFIX + Base64.encodeToString(plain.getBytes(), Base64.NO_WRAP);
        }
    }

    public static String open(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        try {
            if (stored.startsWith(PLAIN_PREFIX)) {
                return new String(Base64.decode(stored.substring(PLAIN_PREFIX.length()), Base64.NO_WRAP), "UTF-8");
            }
            if (!stored.startsWith(ENC_PREFIX)) {
                return stored;
            }
            String[] parts = stored.substring(ENC_PREFIX.length()).split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8");
        } catch (Throwable failure) {
            return "";
        }
    }
}
