package com.queueless.office_service.config;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        fileStorageService = new FileStorageService(tempDir.toString());
    }

    @Test
    @DisplayName("Should save file and load it back safely as a Resource")
    void saveAndLoadFile_Success() throws Exception {
        byte[] content = "sample-pdf-bytes".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile multipartFile = new MockMultipartFile(
                "primaryDocument",
                "license.pdf",
                "application/pdf",
                content
        );

        String savedUrl = fileStorageService.saveFile(multipartFile);
        assertTrue(savedUrl.startsWith("/uploads/documents/"));
        assertTrue(savedUrl.endsWith(".pdf"));

        String storedFilename = savedUrl.substring("/uploads/documents/".length());
        assertTrue(Files.exists(tempDir.resolve(storedFilename)));

        Resource loaded = fileStorageService.loadFileAsResource(storedFilename);
        assertNotNull(loaded);
        assertTrue(loaded.exists());
        assertTrue(loaded.isReadable());
        try (var inputStream = loaded.getInputStream()) {
            assertArrayEquals(content, inputStream.readAllBytes());
        }
    }

    @Test
    @DisplayName("Should reject directory traversal and invalid filenames")
    void loadFileAsResource_RejectsInvalidFilenames() {
        assertThrows(IllegalArgumentException.class, () -> fileStorageService.loadFileAsResource(null));
        assertThrows(IllegalArgumentException.class, () -> fileStorageService.loadFileAsResource("   "));
        assertThrows(IllegalArgumentException.class, () -> fileStorageService.loadFileAsResource("../secret.pdf"));
        assertThrows(IllegalArgumentException.class, () -> fileStorageService.loadFileAsResource("..\\secret.pdf"));
        assertThrows(IllegalArgumentException.class, () -> fileStorageService.loadFileAsResource("nested/file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> fileStorageService.loadFileAsResource("nested\\file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> fileStorageService.loadFileAsResource("bad\0name.pdf"));
    }

    @Test
    @DisplayName("Should throw FileNotFoundException when file does not exist")
    void loadFileAsResource_MissingFileThrowsNotFound() {
        FileNotFoundException ex = assertThrows(
                FileNotFoundException.class,
                () -> fileStorageService.loadFileAsResource("does-not-exist.pdf")
        );
        assertEquals("Document file not found: does-not-exist.pdf", ex.getMessage());
    }
}
