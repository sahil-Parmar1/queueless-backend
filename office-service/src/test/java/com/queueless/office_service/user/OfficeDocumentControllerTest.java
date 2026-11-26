package com.queueless.office_service.user;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.queueless.office_service.config.FileStorageService;

@ExtendWith(MockitoExtension.class)
class OfficeDocumentControllerTest {

    @Mock
    private OfficeDocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private OfficeDocumentController controller;

    private User ownerUser;
    private User otherOfficeUser;
    private OfficeDocument ownedDocument;

    @BeforeEach
    void setUp() {
        ownerUser = new User();
        ownerUser.setId(10L);
        ownerUser.setEmail("owner@office.com");
        ownerUser.setRole(Role.OFFICE);

        otherOfficeUser = new User();
        otherOfficeUser.setId(99L);
        otherOfficeUser.setEmail("other@office.com");
        otherOfficeUser.setRole(Role.OFFICE);

        OfficeProfile profile = new OfficeProfile();
        profile.setId(1L);
        profile.setUser(ownerUser);

        ownedDocument = new OfficeDocument();
        ownedDocument.setId(5L);
        ownedDocument.setOfficeProfile(profile);
        ownedDocument.setOriginalFileName("clinic-license.pdf");
        ownedDocument.setFileUrl("/uploads/documents/doc-123.pdf");
        ownedDocument.setContentType("application/pdf");
    }

    private Authentication authFor(User user) {
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    @Test
    @DisplayName("Authorized office can download its own document")
    void downloadDocument_AuthorizedOwner_ReturnsFile() throws Exception {
        byte[] fileBytes = "pdf-content".getBytes(StandardCharsets.UTF_8);
        when(documentRepository.findByFileUrlWithOwner("/uploads/documents/doc-123.pdf"))
                .thenReturn(Optional.of(ownedDocument));
        when(fileStorageService.loadFileAsResource("doc-123.pdf"))
                .thenReturn(new ByteArrayResource(fileBytes));

        ResponseEntity<?> response = controller.downloadDocument("doc-123.pdf", authFor(ownerUser));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        Resource body = assertInstanceOf(Resource.class, response.getBody());
        assertArrayEquals(fileBytes, body.getInputStream().readAllBytes());
    }

    @Test
    @DisplayName("Another office cannot download another office's document")
    void downloadDocument_DifferentOffice_ReturnsForbidden() throws Exception {
        when(documentRepository.findByFileUrlWithOwner("/uploads/documents/doc-123.pdf"))
                .thenReturn(Optional.of(ownedDocument));

        ResponseEntity<?> response = controller.downloadDocument("doc-123.pdf", authFor(otherOfficeUser));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(fileStorageService, never()).loadFileAsResource(anyString());
    }

    @Test
    @DisplayName("Missing document record returns 404 Not Found")
    void downloadDocument_MissingDbRecord_ReturnsNotFound() throws Exception {
        when(documentRepository.findByFileUrlWithOwner("/uploads/documents/missing.pdf"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.downloadDocument("missing.pdf", authFor(ownerUser));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(fileStorageService, never()).loadFileAsResource(anyString());
    }

    @Test
    @DisplayName("Document record with missing physical file returns 404 Not Found")
    void downloadDocument_MissingDiskFile_ReturnsNotFound() throws Exception {
        when(documentRepository.findByFileUrlWithOwner("/uploads/documents/doc-123.pdf"))
                .thenReturn(Optional.of(ownedDocument));
        when(fileStorageService.loadFileAsResource("doc-123.pdf"))
                .thenThrow(new FileNotFoundException("Document file not found"));

        ResponseEntity<?> response = controller.downloadDocument("doc-123.pdf", authFor(ownerUser));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Invalid filenames are rejected with 400 Bad Request")
    void downloadDocument_InvalidFilename_ReturnsBadRequest() throws Exception {
        ResponseEntity<?> traversalResponse = controller.downloadDocument("../secret.pdf", authFor(ownerUser));
        ResponseEntity<?> slashResponse = controller.downloadDocument("sub/doc.pdf", authFor(ownerUser));
        ResponseEntity<?> blankResponse = controller.downloadDocument("   ", authFor(ownerUser));

        assertEquals(HttpStatus.BAD_REQUEST, traversalResponse.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, slashResponse.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, blankResponse.getStatusCode());
        verify(documentRepository, never()).findByFileUrlWithOwner(anyString());
        verify(fileStorageService, never()).loadFileAsResource(anyString());
    }

    @Test
    @DisplayName("Unauthenticated request returns 401 Unauthorized")
    void downloadDocument_Unauthenticated_ReturnsUnauthorized() {
        ResponseEntity<?> response = controller.downloadDocument("doc-123.pdf", null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
