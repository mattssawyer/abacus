package dev.matthewsawyer.finance_dashboard.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidWebhookVerifierTests {

    private static final String KEY_ID = "key-1";
    private static final String BODY = """
            {"webhook_type":"TRANSACTIONS","webhook_code":"SYNC_UPDATES_AVAILABLE","item_id":"item-id"}""";

    private static ECKey signingKey;

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private Call<WebhookVerificationKeyGetResponse> keyCall;

    private PlaidWebhookVerifier verifier;

    @BeforeAll
    static void generateKey() throws JOSEException {
        signingKey = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();
    }

    @BeforeEach
    void setUp() {
        verifier = new PlaidWebhookVerifier(plaidApi);
    }

    @Test
    void acceptsWebhookSignedByPlaid() throws Exception {
        stubVerificationKey();

        assertTrue(verifier.isValid(BODY, signedHeader(BODY, Instant.now())));
    }

    @Test
    void fetchesEachVerificationKeyOnlyOnce() throws Exception {
        stubVerificationKey();

        assertTrue(verifier.isValid(BODY, signedHeader(BODY, Instant.now())));
        assertTrue(verifier.isValid(BODY, signedHeader(BODY, Instant.now())));

        verify(plaidApi, times(1)).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    @Test
    void rejectsBodyThatDoesNotMatchTheSignature() throws Exception {
        stubVerificationKey();
        String header = signedHeader(BODY, Instant.now());

        assertFalse(verifier.isValid(BODY.replace("item-id", "other-item"), header));
    }

    @Test
    void rejectsWebhookOlderThanFiveMinutes() throws Exception {
        stubVerificationKey();

        assertFalse(verifier.isValid(BODY, signedHeader(BODY, Instant.now().minus(Duration.ofMinutes(6)))));
    }

    @Test
    void rejectsSignatureAlgorithmsOtherThanEs256() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(),
                claims(BODY, Instant.now()));
        jwt.sign(new MACSigner("a-secret-long-enough-for-hmac-sha256-signing".getBytes(UTF_8)));

        assertFalse(verifier.isValid(BODY, jwt.serialize()));
        verifyNoInteractions(plaidApi);
    }

    @Test
    void rejectsKeyPlaidWillNotVouchFor() throws Exception {
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class)))
                .thenReturn(keyCall);
        when(keyCall.execute()).thenReturn(
                Response.error(400, ResponseBody.create("{}", MediaType.get("application/json"))));

        assertFalse(verifier.isValid(BODY, signedHeader(BODY, Instant.now())));
    }

    @Test
    void rejectsSignatureFromAnotherKeyPair() throws Exception {
        ECKey otherKey = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(),
                claims(BODY, Instant.now()));
        jwt.sign(new ECDSASigner(otherKey));
        stubVerificationKey();

        assertFalse(verifier.isValid(BODY, jwt.serialize()));
    }

    @Test
    void rejectsMissingOrUnparseableHeader() {
        assertFalse(verifier.isValid(BODY, null));
        assertFalse(verifier.isValid(BODY, "not-a-jwt"));
        verify(plaidApi, never()).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    private void stubVerificationKey() throws IOException {
        ECKey publicKey = signingKey.toPublicJWK();
        WebhookVerificationKeyGetResponse response = new WebhookVerificationKeyGetResponse()
                .key(new JWKPublicKey()
                        .alg("ES256")
                        .kty("EC")
                        .crv("P-256")
                        .kid(KEY_ID)
                        .use("sig")
                        .x(publicKey.getX().toString())
                        .y(publicKey.getY().toString())
                        .createdAt((int) Instant.now().getEpochSecond()));

        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class)))
                .thenReturn(keyCall);
        when(keyCall.execute()).thenReturn(Response.success(response));
    }

    private static String signedHeader(String body, Instant issuedAt) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(),
                claims(body, issuedAt));
        jwt.sign(new ECDSASigner(signingKey));
        return jwt.serialize();
    }

    private static JWTClaimsSet claims(String body, Instant issuedAt) {
        return new JWTClaimsSet.Builder()
                .issueTime(Date.from(issuedAt))
                .claim("request_body_sha256", sha256Hex(body))
                .build();
    }

    private static String sha256Hex(String body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
