package com.pokergame.persistence.wal;

import com.pokergame.persistence.PersistenceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link EncryptionKeyring}. */
@Tag("unit")
class EncryptionKeyringTest {

    private static SecretKey aes256Key(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return new SecretKeySpec(bytes, "AES");
    }

    @Test
    void returnsCurrentKeyAndSupportsRotatedPreviousKeys() {
        SecretKey keyV1 = aes256Key((byte) 1);
        SecretKey keyV2 = aes256Key((byte) 2);
        EncryptionKeyring keyring = new EncryptionKeyring("v2", Map.of("v1", keyV1, "v2", keyV2));

        assertThat(keyring.currentKeyId()).isEqualTo("v2");
        assertThat(keyring.currentKey()).isSameAs(keyV2);
        assertThat(keyring.key("v1")).isSameAs(keyV1);
        assertThat(keyring.key("v2")).isSameAs(keyV2);
    }

    @Test
    void unknownKeyIdThrowsMissingPersistenceKeyException() {
        EncryptionKeyring keyring = new EncryptionKeyring("v1", Map.of("v1", aes256Key((byte) 1)));

        assertThatThrownBy(() -> keyring.key("missing"))
                .isInstanceOf(MissingPersistenceKeyException.class);
        assertThatThrownBy(() -> keyring.key(""))
                .isInstanceOf(MissingPersistenceKeyException.class);
    }

    @Test
    void blankOrMissingCurrentKeyIdThrowsPersistenceException() {
        Map<String, SecretKey> keys = Map.of("v1", aes256Key((byte) 1));

        assertThatThrownBy(() -> new EncryptionKeyring(null, keys))
                .isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> new EncryptionKeyring("  ", keys))
                .isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> new EncryptionKeyring("v2", keys))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void non256BitKeyIsRejectedOnConstruction() {
        SecretKey aes128 = new SecretKeySpec(new byte[16], "AES");

        assertThatThrownBy(() -> new EncryptionKeyring("short", Map.of("short", aes128)))
                .isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> new EncryptionKeyring("current",
                Map.of("current", aes256Key((byte) 1), "legacy", aes128)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void fromBase64DecodesKeysAndRejectsInvalidBase64() {
        String valid = Base64.getEncoder().encodeToString(new byte[32]);

        EncryptionKeyring keyring = EncryptionKeyring.fromBase64("v1", Map.of("v1", valid));

        assertThat(keyring.currentKey().getEncoded()).hasSize(32);
        assertThatThrownBy(() -> EncryptionKeyring.fromBase64("v1", Map.of("v1", "not base64!")))
                .isInstanceOf(PersistenceException.class)
                .hasMessage("Persistence key v1 is not valid Base64");
        assertThatThrownBy(() -> EncryptionKeyring.fromBase64("v1",
                Map.of("v1", Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(PersistenceException.class);
    }
}
