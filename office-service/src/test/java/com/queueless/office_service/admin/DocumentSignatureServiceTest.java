package com.queueless.office_service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class DocumentSignatureServiceTest {

    private final DocumentSignatureService service =
            new DocumentSignatureService("test-jwt-secret-key-at-least-32-bytes-long");

    @Test
    void signedPathValidatesCorrectlyAndRejectsTampering() {
        String signedPath = service.createSignedViewPath("e6e768d2-7c4b-41d6-af2c-5afb40c817f1.pptx");
        assertTrue(signedPath.startsWith("/api/admin/documents/view/e6e768d2-7c4b-41d6-af2c-5afb40c817f1.pptx?"));

        URI uri = URI.create("http://localhost" + signedPath);
        Map<String, String> params = Stream.of(uri.getQuery().split("&"))
                .map(kv -> kv.split("=", 2))
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));

        long expires = Long.parseLong(params.get("expires"));
        String sig = params.get("sig");

        assertTrue(service.isValidSignature("e6e768d2-7c4b-41d6-af2c-5afb40c817f1.pptx", expires, sig));
        assertFalse(service.isValidSignature("other-file.pptx", expires, sig));
        assertFalse(service.isValidSignature("e6e768d2-7c4b-41d6-af2c-5afb40c817f1.pptx", expires + 1, sig));
        assertFalse(service.isValidSignature("e6e768d2-7c4b-41d6-af2c-5afb40c817f1.pptx", Instant.now().getEpochSecond() - 5, sig));
        assertEquals(false, service.isValidSignature("../evil.pptx", expires, sig));
    }
}
