package com.queueless.office_service.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OfficeDocumentRepository extends JpaRepository<OfficeDocument, Long> {

    Optional<OfficeDocument> findByFileUrl(String fileUrl);

    @Query("SELECT d FROM OfficeDocument d JOIN FETCH d.officeProfile p JOIN FETCH p.user WHERE d.fileUrl = :fileUrl")
    Optional<OfficeDocument> findByFileUrlWithOwner(@Param("fileUrl") String fileUrl);
}
