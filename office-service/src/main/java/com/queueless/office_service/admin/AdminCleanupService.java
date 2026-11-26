package com.queueless.office_service.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.queueless.office_service.config.FileStorageService;
import com.queueless.office_service.user.OfficeDocument;
import com.queueless.office_service.user.OfficeProfile;
import com.queueless.office_service.user.OfficeProfileRepository;
import com.queueless.office_service.user.Role;
import com.queueless.office_service.user.User;
import com.queueless.office_service.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Performs cascading database and storage cleanup when an administrator
 * deletes an office or customer account to reclaim MySQL and volume space.
 */
@Service
public class AdminCleanupService {

    private static final String DOCUMENTS_URL_PREFIX = "/uploads/documents/";

    private final OfficeProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminCleanupService(
            OfficeProfileRepository profileRepository,
            UserRepository userRepository,
            FileStorageService fileStorageService) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCustomers() {
        List<User> customers = userRepository.findByRoleOrderByIdDesc(Role.CUSTOMER);

        Map<Long, Long> tokenCounts = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rawCounts = entityManager.createQuery(
                "SELECT t.customerId, COUNT(t) FROM QueueToken t WHERE t.customerId IS NOT NULL GROUP BY t.customerId"
        ).getResultList();
        for (Object[] row : rawCounts) {
            if (row[0] instanceof Number idNum && row[1] instanceof Number cntNum) {
                tokenCounts.put(idNum.longValue(), cntNum.longValue());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(customers.size());
        for (User c : customers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("email", c.getEmail());
            item.put("enabled", c.getEnabled());
            item.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
            item.put("tokenCount", tokenCounts.getOrDefault(c.getId(), 0L));
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> deleteOfficeAndData(Long profileId) {
        OfficeProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Office not found: " + profileId));

        User owner = profile.getUser();
        List<String> filenames = new ArrayList<>();
        if (profile.getDocuments() != null) {
            for (OfficeDocument doc : profile.getDocuments()) {
                String fileUrl = doc.getFileUrl();
                if (fileUrl != null && fileUrl.startsWith(DOCUMENTS_URL_PREFIX)) {
                    filenames.add(fileUrl.substring(DOCUMENTS_URL_PREFIX.length()));
                }
            }
        }

        int deletedFavorites = entityManager.createNativeQuery(
                "DELETE FROM customer_favorite_offices WHERE office_id = :officeId"
        ).setParameter("officeId", profileId).executeUpdate();

        int deletedTokens = entityManager.createQuery(
                "DELETE FROM QueueToken t WHERE t.office.id = :officeId"
        ).setParameter("officeId", profileId).executeUpdate();

        entityManager.createQuery(
                "DELETE FROM ProviderSchedule ps WHERE ps.provider.id IN (SELECT p.id FROM Provider p WHERE p.office.id = :officeId)"
        ).setParameter("officeId", profileId).executeUpdate();

        int deletedProviders = entityManager.createQuery(
                "DELETE FROM Provider p WHERE p.office.id = :officeId"
        ).setParameter("officeId", profileId).executeUpdate();

        profileRepository.delete(profile);
        profileRepository.flush();

        boolean deletedOwnerAccount = false;
        if (owner != null && owner.getId() != null && owner.getRole() == Role.OFFICE) {
            userRepository.deleteById(owner.getId());
            deletedOwnerAccount = true;
        }

        int deletedFilesFromDisk = 0;
        for (String filename : filenames) {
            if (fileStorageService.deleteFile(filename)) {
                deletedFilesFromDisk++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("message", "Office and all related records deleted successfully");
        summary.put("deletedOfficeId", profileId);
        summary.put("deletedFavorites", deletedFavorites);
        summary.put("deletedTokens", deletedTokens);
        summary.put("deletedProviders", deletedProviders);
        summary.put("deletedOwnerAccount", deletedOwnerAccount);
        summary.put("deletedDocumentsFromDisk", deletedFilesFromDisk);
        return summary;
    }

    @Transactional
    public Map<String, Object> deleteCustomerAndData(Long customerId) {
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found: " + customerId));

        if (customer.getRole() != Role.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not a CUSTOMER account: " + customerId);
        }

        String email = customer.getEmail();

        int deletedFavorites = entityManager.createNativeQuery(
                "DELETE FROM customer_favorite_offices WHERE customer_id = :customerId OR (:email IS NOT NULL AND customer_email = :email)"
        )
                .setParameter("customerId", customerId)
                .setParameter("email", email)
                .executeUpdate();

        int deletedTokens = entityManager.createQuery(
                "DELETE FROM QueueToken t WHERE t.customerId = :customerId OR (:email IS NOT NULL AND t.customerEmail = :email)"
        )
                .setParameter("customerId", customerId)
                .setParameter("email", email)
                .executeUpdate();

        userRepository.delete(customer);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("message", "Customer and all related records deleted successfully");
        summary.put("deletedCustomerId", customerId);
        summary.put("deletedFavorites", deletedFavorites);
        summary.put("deletedTokens", deletedTokens);
        return summary;
    }
}
