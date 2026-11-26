package com.queueless.office_service.admin;

import java.io.FileNotFoundException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.queueless.office_service.config.FileStorageService;
import com.queueless.office_service.user.OfficeDocument;
import com.queueless.office_service.user.OfficeDocumentRepository;
import com.queueless.office_service.user.OfficeProfile;
import com.queueless.office_service.user.OfficeProfileRepository;
import com.queueless.office_service.user.VerificationStatus;

/**
 * Administrative endpoints for reviewing office registrations, viewing
 * verification documents, and approving or rejecting office verification status.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminOfficeController {

    private static final String DOCUMENTS_URL_PREFIX = "/uploads/documents/";

    private final OfficeProfileRepository profileRepository;
    private final OfficeDocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final DocumentSignatureService signatureService;

    public AdminOfficeController(
            OfficeProfileRepository profileRepository,
            OfficeDocumentRepository documentRepository,
            FileStorageService fileStorageService,
            DocumentSignatureService signatureService) {
        this.profileRepository = profileRepository;
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
        this.signatureService = signatureService;
    }

    @Transactional(readOnly = true)
    @GetMapping("/offices")
    public ResponseEntity<List<Map<String, Object>>> listOffices(
            @RequestParam(value = "status", required = false) VerificationStatus status) {

        List<OfficeProfile> profiles = status != null
                ? profileRepository.findByVerificationStatus(status)
                : profileRepository.findAll();

        List<Map<String, Object>> result = profiles.stream()
                .sorted(Comparator.comparing(OfficeProfile::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toAdminOfficeMap)
                .toList();

        return ResponseEntity.ok(result);
    }

    @Transactional(readOnly = true)
    @GetMapping("/offices/{id}")
    public ResponseEntity<Map<String, Object>> getOffice(@PathVariable("id") Long id) {
        OfficeProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Office not found: " + id));
        return ResponseEntity.ok(toAdminOfficeMap(profile));
    }

    @Transactional
    @PutMapping("/offices/{id}/status")
    public ResponseEntity<Map<String, Object>> updateOfficeStatus(
            @PathVariable("id") Long id,
            @RequestParam(value = "status", required = false) VerificationStatus statusParam,
            @RequestBody(required = false) Map<String, String> body) {

        VerificationStatus targetStatus = resolveTargetStatus(statusParam, body);

        OfficeProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Office not found: " + id));

        profile.setVerificationStatus(targetStatus);
        OfficeProfile saved = profileRepository.save(profile);

        return ResponseEntity.ok(Map.of(
                "message", "Office verification status updated to " + saved.getVerificationStatus().name(),
                "id", saved.getId(),
                "officeId", saved.getOfficeId() != null ? saved.getOfficeId() : "",
                "status", saved.getVerificationStatus().name()
        ));
    }

    @GetMapping("/documents/{filename:.+}/signed-url")
    public ResponseEntity<Map<String, Object>> createSignedDocumentUrl(
            @PathVariable("filename") String filename) {

        validateStoredDocumentExists(filename);
        String signedViewPath = signatureService.createSignedViewPath(filename);

        return ResponseEntity.ok(Map.of(
                "filename", filename,
                "signedViewPath", signedViewPath,
                "expiresInSeconds", DocumentSignatureService.DEFAULT_TTL_SECONDS
        ));
    }

    /**
     * Permanently removes a verification document record from the database and
     * deletes its backing file from storage to reclaim database/volume space.
     */
    @Transactional
    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable("documentId") Long documentId) {

        OfficeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId));

        String filename = extractFilename(document.getFileUrl());
        OfficeProfile profile = document.getOfficeProfile();

        if (profile != null && profile.getDocuments() != null) {
            profile.getDocuments().removeIf(d -> documentId.equals(d.getId()));
            profileRepository.saveAndFlush(profile);
        } else {
            documentRepository.delete(document);
        }

        boolean deletedFromDisk = false;
        if (filename != null) {
            deletedFromDisk = fileStorageService.deleteFile(filename);
        }

        return ResponseEntity.ok(Map.of(
                "message", "Document deleted from database and storage",
                "deletedDocumentId", documentId,
                "filename", filename != null ? filename : "",
                "deletedFromDisk", deletedFromDisk
        ));
    }

    /**
     * Streams a verification document to a browser tab when authorized by a valid,
     * unexpired HMAC-SHA256 signature generated for an authenticated ADMIN.
     */
    @GetMapping("/documents/view/{filename:.+}")
    public ResponseEntity<?> viewSignedDocument(
            @PathVariable("filename") String filename,
            @RequestParam(value = "expires", required = false) Long expires,
            @RequestParam(value = "sig", required = false) String sig) {

        if (!signatureService.isValidSignature(filename, expires, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Document link is invalid or expired"));
        }

        OfficeDocument document = validateStoredDocumentExists(filename);

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

    private OfficeDocument validateStoredDocumentExists(String filename) {
        try {
            FileStorageService.validateFilename(filename);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document filename");
        }
        return documentRepository.findByFileUrl(DOCUMENTS_URL_PREFIX + filename)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    private VerificationStatus resolveTargetStatus(VerificationStatus statusParam, Map<String, String> body) {
        if (statusParam != null) {
            return statusParam;
        }
        if (body != null && body.get("status") != null && !body.get("status").isBlank()) {
            try {
                return VerificationStatus.valueOf(body.get("status").trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status. Expected PENDING, APPROVED, or REJECTED");
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification status is required");
    }

    private Map<String, Object> toAdminOfficeMap(OfficeProfile p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("officeId", p.getOfficeId());
        map.put("name", p.getUser() != null ? p.getUser().getName() : "Office");
        map.put("email", p.getUser() != null ? p.getUser().getEmail() : "");
        map.put("category", p.getCategory() != null ? p.getCategory().name() : "OTHER");
        map.put("phone", p.getPhone());
        map.put("address", p.getAddress());
        map.put("city", p.getCity());
        map.put("state", p.getState());
        map.put("pincode", p.getPincode());
        map.put("openingTime", p.getOpeningTime());
        map.put("closingTime", p.getClosingTime());
        map.put("description", p.getDescription());
        map.put("doctorName", p.getDoctorName());
        map.put("specialization", p.getSpecialization());
        map.put("medicalRegistrationNumber", p.getMedicalRegistrationNumber());
        map.put("salonType", p.getSalonType());
        map.put("tradeLicenseNumber", p.getTradeLicenseNumber());
        map.put("verificationStatus", p.getVerificationStatus() != null ? p.getVerificationStatus().name() : "PENDING");
        map.put("isOpen", p.getIsOpen());
        map.put("dailyMaxTokens", p.getDailyMaxTokens());
        map.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);

        List<Map<String, Object>> docs = p.getDocuments() == null
                ? List.of()
                : p.getDocuments().stream().map(this::toAdminDocumentMap).toList();
        map.put("documents", docs);
        return map;
    }

    private Map<String, Object> toAdminDocumentMap(OfficeDocument doc) {
        String filename = extractFilename(doc.getFileUrl());
        String signedViewPath = null;
        if (filename != null) {
            try {
                signedViewPath = signatureService.createSignedViewPath(filename);
            } catch (IllegalArgumentException ignored) {
                signedViewPath = null;
            }
        }

        Map<String, Object> docMap = new LinkedHashMap<>();
        docMap.put("id", doc.getId());
        docMap.put("documentType", doc.getDocumentType());
        docMap.put("originalFileName", doc.getOriginalFileName());
        docMap.put("fileUrl", doc.getFileUrl());
        docMap.put("filename", filename);
        docMap.put("contentType", doc.getContentType());
        docMap.put("fileSize", doc.getFileSize());
        docMap.put("uploadedAt", doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : null);
        docMap.put("signedViewPath", signedViewPath);
        return docMap;
    }

    private String extractFilename(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith(DOCUMENTS_URL_PREFIX)) {
            return null;
        }
        return fileUrl.substring(DOCUMENTS_URL_PREFIX.length());
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
