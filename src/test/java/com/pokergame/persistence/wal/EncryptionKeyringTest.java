package com.pokergame.persistence.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokergame.persistence.PersistenceException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link EncryptionKeyring}. */
@Tag("unit")
class EncryptionKeyringTest {

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

    @ParameterizedTest
    @ValueSource(strings = {"missing", ""})
    void unknownKeyIdThrowsMissingPersistenceKeyException(String keyId) {
        EncryptionKeyring keyring = new EncryptionKeyring("v1", Map.of("v1", aes256Key((byte) 1)));

        assertThatThrownBy(() -> keyring.key(keyId))
                .isInstanceOf(MissingPersistenceKeyException.class)
                .hasMessage("Required persistence key is unavailable: " + keyId);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void blankCurrentKeyIdIsRejected(String currentKeyId) {
        Map<String, SecretKey> keys = Map.of("v1", aes256Key((byte) 1));

        assertThatThrownBy(() -> new EncryptionKeyring(currentKeyId, keys))
                .isExactlyInstanceOf(PersistenceException.class)
                .hasMessage("A current persistence key ID is required");
    }

    @Test
    void currentKeyIdAbsentFromKeysIsRejectedAsMissingKey() {
        Map<String, SecretKey> keys = Map.of("v1", aes256Key((byte) 1));

        assertThatThrownBy(() -> new EncryptionKeyring("v2", keys))
                .isInstanceOf(MissingPersistenceKeyException.class)
                .hasMessage("Required persistence key is unavailable: v2");
    }

    @Test
    void non256BitCurrentKeyIsRejectedOnConstruction() {
        SecretKey aes128 = new SecretKeySpec(new byte[16], "AES");

        assertThatThrownBy(() -> new EncryptionKeyring("short", Map.of("short", aes128)))
                .isExactlyInstanceOf(PersistenceException.class)
                .hasMessage("Persistence key short is not 256-bit");
    }

    @Test
    void non256BitPreviousKeyIsRejectedOnConstruction() {
        SecretKey aes128 = new SecretKeySpec(new byte[16], "AES");
        Map<String, SecretKey> keys = Map.of("current", aes256Key((byte) 1), "legacy", aes128);

        assertThatThrownBy(() -> new EncryptionKeyring("current", keys))
                .isExactlyInstanceOf(PersistenceException.class)
                .hasMessage("Persistence key legacy is not 256-bit");
    }

    @Test
    void fromBase64DecodesKeys() {
        String valid = Base64.getEncoder().encodeToString(new byte[32]);

        EncryptionKeyring keyring = EncryptionKeyring.fromBase64("v1", Map.of("v1", valid));

        assertThat(keyring.currentKey().getEncoded()).hasSize(32);
    }

    @Test
    void fromBase64RejectsInvalidBase64() {
        assertThatThrownBy(() -> EncryptionKeyring.fromBase64("v1", Map.of("v1", "not base64!")))
                .isExactlyInstanceOf(PersistenceException.class)
                .hasMessage("Persistence key v1 is not valid Base64");
    }

    @Test
    void fromBase64RejectsDecodedKeyThatIsNot256Bit() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> EncryptionKeyring.fromBase64("v1", Map.of("v1", shortKey)))
                .isExactlyInstanceOf(PersistenceException.class)
                .hasMessage("Persistence key v1 is not 256-bit");
    }

    private static SecretKey aes256Key(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return new SecretKeySpec(bytes, "AES");
    }
}
