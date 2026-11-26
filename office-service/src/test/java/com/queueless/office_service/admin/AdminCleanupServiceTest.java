package com.queueless.office_service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.queueless.office_service.config.FileStorageService;
import com.queueless.office_service.user.OfficeDocument;
import com.queueless.office_service.user.OfficeProfile;
import com.queueless.office_service.user.OfficeProfileRepository;
import com.queueless.office_service.user.Role;
import com.queueless.office_service.user.User;
import com.queueless.office_service.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@ExtendWith(MockitoExtension.class)
class AdminCleanupServiceTest {

    @Mock
    private OfficeProfileRepository profileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private EntityManager entityManager;

    private AdminCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new AdminCleanupService(profileRepository, userRepository, fileStorageService);
        ReflectionTestUtils.setField(cleanupService, "entityManager", entityManager);
    }

    @Test
    void deleteOfficeCascadesChildDataAndDiskFiles() {
        User owner = new User();
        owner.setId(5L);
        owner.setRole(Role.OFFICE);

        OfficeProfile profile = new OfficeProfile();
        profile.setId(12L);
        profile.setUser(owner);

        OfficeDocument doc = new OfficeDocument();
        doc.setFileUrl("/uploads/documents/file1.pdf");
        profile.setDocuments(new ArrayList<>(List.of(doc)));

        Query mockQuery = mock(Query.class);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.executeUpdate()).thenReturn(2);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(entityManager.createQuery(anyString())).thenReturn(mockQuery);
        when(profileRepository.findById(12L)).thenReturn(Optional.of(profile));
        when(fileStorageService.deleteFile("file1.pdf")).thenReturn(true);

        Map<String, Object> summary = cleanupService.deleteOfficeAndData(12L);
        assertEquals(12L, summary.get("deletedOfficeId"));
        assertEquals(true, summary.get("deletedOwnerAccount"));
        assertEquals(1, summary.get("deletedDocumentsFromDisk"));
        verify(profileRepository).delete(profile);
        verify(userRepository).deleteById(5L);
    }

    @Test
    void deleteCustomerRejectsNonCustomerAndDeletesCustomerData() {
        User officeUser = new User();
        officeUser.setId(9L);
        officeUser.setRole(Role.OFFICE);
        when(userRepository.findById(9L)).thenReturn(Optional.of(officeUser));
        assertThrows(ResponseStatusException.class, () -> cleanupService.deleteCustomerAndData(9L));

        User customer = new User();
        customer.setId(20L);
        customer.setEmail("cust@example.com");
        customer.setRole(Role.CUSTOMER);

        Query mockQuery = mock(Query.class);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.executeUpdate()).thenReturn(3);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(entityManager.createQuery(anyString())).thenReturn(mockQuery);
        when(userRepository.findById(20L)).thenReturn(Optional.of(customer));

        Map<String, Object> summary = cleanupService.deleteCustomerAndData(20L);
        assertEquals(20L, summary.get("deletedCustomerId"));
        assertEquals(3, summary.get("deletedTokens"));
        verify(userRepository).delete(customer);
    }
}
