package com.queueless.office_service.admin;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.queueless.office_service.config.FileStorageService;

/**
 * Signs and verifies short-lived HMAC-SHA256 URLs for viewing private office
 * verification documents in a browser tab without custom Authorization headers.
 */
@Service
public class DocumentSignatureService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    public static final long DEFAULT_TTL_SECONDS = 900; // 15 minutes

    private final byte[] signingKeyBytes;

    public DocumentSignatureService(@Value("${jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes for document URL signing");
        }
        this.signingKeyBytes = ("doc-view:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public String createSignedViewPath(String filename) {
        FileStorageService.validateFilename(filename);
        long expires = Instant.now().getEpochSecond() + DEFAULT_TTL_SECONDS;
        String sig = computeHmac(filename, expires);
        return "/api/admin/documents/view/" + filename + "?expires=" + expires + "&sig=" + sig;
    }

    public boolean isValidSignature(String filename, Long expires, String sig) {
        if (filename == null || expires == null || sig == null || sig.isBlank()) {
            return false;
        }
        try {
            FileStorageService.validateFilename(filename);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (Instant.now().getEpochSecond() > expires) {
            return false;
        }
        String expected = computeHmac(filename, expires);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String computeHmac(String filename, long expires) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKeyBytes, HMAC_ALGORITHM));
            byte[] raw = mac.doFinal((filename + ":" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign document URL", e);
        }
    }
}
