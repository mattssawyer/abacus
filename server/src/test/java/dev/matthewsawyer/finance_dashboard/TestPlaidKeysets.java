package dev.matthewsawyer.finance_dashboard;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.security.GeneralSecurityException;

public final class TestPlaidKeysets {
    public static String create() {
        try {
            AeadConfig.register();
            return TinkJsonProtoKeysetFormat.serializeKeyset(
                    KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM),
                    InsecureSecretKeyAccess.get());
        } catch (GeneralSecurityException e) {
            throw new AssertionError("Unable to create test keyset", e);
        }
    }

    private TestPlaidKeysets() {}
}
