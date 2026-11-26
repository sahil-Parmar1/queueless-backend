package com.queueless.office_service.user;

import java.io.FileNotFoundException;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.queueless.office_service.config.FileStorageService;

@RestController
public class OfficeDocumentController {

    private static final String DOCUMENTS_URL_PREFIX = "/uploads/documents/";

    private final OfficeDocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    public OfficeDocumentController(
            OfficeDocumentRepository documentRepository,
            UserRepository userRepository,
            FileStorageService fileStorageService) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping({"/uploads/documents/{filename:.+}", "/api/office/documents/{filename:.+}"})
    public ResponseEntity<?> downloadDocument(
            @PathVariable("filename") String filename,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        boolean isAdmin = hasAdminAuthority(authentication);
        User authenticatedUser = null;
        if (!isAdmin) {
            authenticatedUser = resolveAuthenticatedUser(authentication);
            if (authenticatedUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Authenticated user not found"));
            }

            if (authenticatedUser.getRole() != Role.OFFICE) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Only office accounts can access verification documents"));
            }
        }

        try {
            FileStorageService.validateFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid document filename"));
        }

        String fileUrl = DOCUMENTS_URL_PREFIX + filename;
        OfficeDocument document = documentRepository.findByFileUrlWithOwner(fileUrl)
                .orElse(null);

        if (document == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Document not found"));
        }

        if (!isAdmin) {
            OfficeProfile profile = document.getOfficeProfile();
            User owner = profile != null ? profile.getUser() : null;
            if (owner == null || owner.getId() == null || !owner.getId().equals(authenticatedUser.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You do not have permission to access this document"));
            }
        }

        Resource resource;
        try {
            resource = fileStorageService.loadFileAsResource(filename);
        } catch (FileNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Document file not found"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid document filename"));
        }

        MediaType mediaType = resolveMediaType(document.getContentType());
        String safeOriginalName = sanitizeDownloadFilename(document.getOriginalFileName(), filename);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.inline().filename(safeOriginalName).build());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "private, no-store");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(mediaType)
                .body(resource);
    }

    private boolean hasAdminAuthority(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private User resolveAuthenticatedUser(Authentication authentication) {
        if (authentication.getPrincipal() instanceof User u) {
            return u;
        }
        String email = authentication.getName();
        if (email == null || email.isBlank()) {
            return null;
        }
        return userRepository.findByEmail(email).orElse(null);
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String sanitizeDownloadFilename(String originalFileName, String fallback) {
        String candidate = (originalFileName != null && !originalFileName.isBlank()) ? originalFileName : fallback;
        return candidate.replaceAll("[\\r\\n\"\\\\/]", "_");
    }
}
