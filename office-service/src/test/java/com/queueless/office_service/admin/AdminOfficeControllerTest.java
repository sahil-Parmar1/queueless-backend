package com.queueless.office_service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.queueless.office_service.config.FileStorageService;
import com.queueless.office_service.user.OfficeCategory;
import com.queueless.office_service.user.OfficeDocument;
import com.queueless.office_service.user.OfficeDocumentRepository;
import com.queueless.office_service.user.OfficeProfile;
import com.queueless.office_service.user.OfficeProfileRepository;
import com.queueless.office_service.user.Role;
import com.queueless.office_service.user.User;
import com.queueless.office_service.user.VerificationStatus;

@ExtendWith(MockitoExtension.class)
class AdminOfficeControllerTest {

    @Mock
    private OfficeProfileRepository profileRepository;

    @Mock
    private OfficeDocumentRepository documentRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private AdminCleanupService cleanupService;

    private DocumentSignatureService signatureService;
    private AdminOfficeController controller;
    private OfficeProfile profile;
    private OfficeDocument doc;

    @BeforeEach
    void setUp() {
        signatureService = new DocumentSignatureService("test-jwt-secret-key-at-least-32-bytes-long");
        controller = new AdminOfficeController(profileRepository, documentRepository, fileStorageService, signatureService, cleanupService);

        User user = new User();
        user.setId(7L);
        user.setName("Demo Clinic");
        user.setEmail("clinic@example.com");
        user.setRole(Role.OFFICE);
        user.setOfficeId("OFF-ABC123");

        profile = new OfficeProfile();
        profile.setId(1L);
        profile.setUser(user);
        profile.setOfficeId("OFF-ABC123");
        profile.setCategory(OfficeCategory.CLINIC);
        profile.setVerificationStatus(VerificationStatus.PENDING);

        doc = new OfficeDocument();
        doc.setId(10L);
        doc.setOfficeProfile(profile);
        doc.setDocumentType("CLINIC_REGISTRATION");
        doc.setOriginalFileName("license.pptx");
        doc.setFileUrl("/uploads/documents/test-doc.pptx");
        doc.setContentType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        profile.setDocuments(new ArrayList<>(List.of(doc)));
    }

    @Test
    void listAndApproveOfficeUpdatesStatus() {
        when(profileRepository.findByVerificationStatus(VerificationStatus.PENDING)).thenReturn(List.of(profile));
        ResponseEntity<List<Map<String, Object>>> listResp = controller.listOffices(VerificationStatus.PENDING);
        assertEquals(HttpStatus.OK, listResp.getStatusCode());
        assertEquals(1, listResp.getBody().size());
        assertEquals("OFF-ABC123", listResp.getBody().get(0).get("officeId"));

        when(profileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(profileRepository.save(any(OfficeProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> approveResp =
                controller.updateOfficeStatus(1L, null, Map.of("status", "APPROVED"));
        assertEquals(HttpStatus.OK, approveResp.getStatusCode());
        assertEquals("APPROVED", approveResp.getBody().get("status"));
        assertEquals(VerificationStatus.APPROVED, profile.getVerificationStatus());
    }

    @Test
    void signedUrlAllowsViewingWithoutAuthHeaderAndRejectsBadSignature() throws Exception {
        when(documentRepository.findByFileUrl("/uploads/documents/test-doc.pptx")).thenReturn(Optional.of(doc));
        when(fileStorageService.loadFileAsResource("test-doc.pptx"))
                .thenReturn(new ByteArrayResource("pptx-bytes".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<Map<String, Object>> signed = controller.createSignedDocumentUrl("test-doc.pptx");
        String path = (String) signed.getBody().get("signedViewPath");
        assertNotNull(path);

        String query = path.substring(path.indexOf('?') + 1);
        long expires = 0;
        String sig = "";
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if ("expires".equals(kv[0])) expires = Long.parseLong(kv[1]);
            if ("sig".equals(kv[0])) sig = kv[1];
        }

        ResponseEntity<?> validView = controller.viewSignedDocument("test-doc.pptx", expires, sig);
        assertEquals(HttpStatus.OK, validView.getStatusCode());

        ResponseEntity<?> badView = controller.viewSignedDocument("test-doc.pptx", expires, "tampered");
        assertEquals(HttpStatus.FORBIDDEN, badView.getStatusCode());
    }

    @Test
    void deleteDocumentRemovesRecordAndDeletesStoredFile() {
        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));
        when(profileRepository.saveAndFlush(any(OfficeProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileStorageService.deleteFile("test-doc.pptx")).thenReturn(true);

        ResponseEntity<Map<String, Object>> deleteResp = controller.deleteDocument(10L);
        assertEquals(HttpStatus.OK, deleteResp.getStatusCode());
        assertEquals(10L, deleteResp.getBody().get("deletedDocumentId"));
        assertEquals(true, deleteResp.getBody().get("deletedFromDisk"));
        assertEquals(0, profile.getDocuments().size());
    }

    @Test
    void deleteOfficeAndCustomerDelegateToCleanupService() {
        when(cleanupService.deleteOfficeAndData(1L)).thenReturn(Map.of("deletedOfficeId", 1L, "deletedTokens", 4));
        when(cleanupService.deleteCustomerAndData(20L)).thenReturn(Map.of("deletedCustomerId", 20L, "deletedTokens", 2));

        ResponseEntity<Map<String, Object>> officeResp = controller.deleteOffice(1L);
        assertEquals(HttpStatus.OK, officeResp.getStatusCode());
        assertEquals(1L, officeResp.getBody().get("deletedOfficeId"));

        ResponseEntity<Map<String, Object>> custResp = controller.deleteCustomer(20L);
        assertEquals(HttpStatus.OK, custResp.getStatusCode());
        assertEquals(20L, custResp.getBody().get("deletedCustomerId"));
    }
}
