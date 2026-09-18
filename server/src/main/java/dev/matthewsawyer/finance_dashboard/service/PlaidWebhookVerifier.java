package dev.matthewsawyer.finance_dashboard.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Checks the {@code Plaid-Verification} JWT that Plaid signs every webhook with, following
 * https://plaid.com/docs/api/webhooks/webhook-verification/.
 */
@Component
public class PlaidWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(PlaidWebhookVerifier.class);

    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final PlaidApi plaidApi;
    private final Map<String, ECKey> keyCache = new ConcurrentHashMap<>();

    public PlaidWebhookVerifier(PlaidApi plaidApi) {
        this.plaidApi = plaidApi;
    }

    public boolean isValid(String rawBody, String verificationHeader) {
        if (rawBody == null || verificationHeader == null) {
            return false;
        }

        try {
            SignedJWT jwt = SignedJWT.parse(verificationHeader);
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
                log.warn("Rejected Plaid webhook signed with {}", jwt.getHeader().getAlgorithm());
                return false;
            }

            ECKey key = verificationKey(jwt.getHeader().getKeyID());
            if (key == null || !jwt.verify(new ECDSAVerifier(key))) {
                log.warn("Rejected Plaid webhook with an unverifiable signature");
                return false;
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (isStale(claims.getIssueTime())) {
                log.warn("Rejected Plaid webhook issued at {}", claims.getIssueTime());
                return false;
            }

            String claimedHash = claims.getStringClaim("request_body_sha256");
            if (claimedHash == null || !matchesBody(claimedHash, rawBody)) {
                log.warn("Rejected Plaid webhook whose body does not match its signature");
                return false;
            }

            return true;
        } catch (ParseException | JOSEException | IOException | IllegalArgumentException e) {
            log.warn("Rejected unreadable Plaid webhook verification header", e);
            return false;
        }
    }

    private ECKey verificationKey(String keyId) throws IOException {
        if (keyId == null) {
            return null;
        }

        ECKey cached = keyCache.get(keyId);
        if (cached != null) {
            return cached;
        }

        Response<WebhookVerificationKeyGetResponse> response = plaidApi
                .webhookVerificationKeyGet(new WebhookVerificationKeyGetRequest().keyId(keyId))
                .execute();
        if (!response.isSuccessful() || response.body() == null || response.body().getKey() == null) {
            log.warn("Plaid webhook verification key {} could not be fetched", keyId);
            return null;
        }

        JWKPublicKey jwk = response.body().getKey();
        if (!"EC".equals(jwk.getKty()) || !"P-256".equals(jwk.getCrv())) {
            log.warn("Plaid webhook verification key {} is not an EC P-256 key", keyId);
            return null;
        }

        ECKey key = new ECKey.Builder(Curve.P_256, new Base64URL(jwk.getX()), new Base64URL(jwk.getY()))
                .keyID(keyId)
                .build();
        // A rotated key stays usable for webhooks signed before it expired, but never cache it.
        if (jwk.getExpiredAt() == null) {
            keyCache.put(keyId, key);
        }

        return key;
    }

    private static boolean isStale(Date issuedAt) {
        return issuedAt == null || issuedAt.toInstant().isBefore(Instant.now().minus(MAX_AGE));
    }

    private static boolean matchesBody(String claimedHash, String rawBody) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawBody.getBytes(UTF_8));
            return MessageDigest.isEqual(
                    claimedHash.getBytes(UTF_8),
                    HexFormat.of().formatHex(digest).getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
