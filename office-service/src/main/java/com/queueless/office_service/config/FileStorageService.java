package com.queueless.office_service.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {

    private final Path uploadDir;

    public FileStorageService(@Value("${file.upload-dir:uploads/documents}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory", e);
        }
    }

    public String saveFile(MultipartFile file) {
        try {
            String originalFileName = file.getOriginalFilename();
            String extension = "";
            if (originalFileName != null && originalFileName.contains(".")) {
                String rawExt = originalFileName.substring(originalFileName.lastIndexOf("."));
                if (!rawExt.contains("/") && !rawExt.contains("\\")) {
                    extension = rawExt;
                }
            }

            String uniqueFileName = UUID.randomUUID() + extension;
            Path targetLocation = uploadDir.resolve(uniqueFileName).normalize();

            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            return "/uploads/documents/" + uniqueFileName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + e.getMessage(), e);
        }
    }

    public Resource loadFileAsResource(String filename) throws FileNotFoundException {
        validateFilename(filename);

        Path targetLocation = this.uploadDir.resolve(filename).normalize();
        if (!targetLocation.startsWith(this.uploadDir) || !this.uploadDir.equals(targetLocation.getParent())) {
            throw new IllegalArgumentException("Invalid file path: " + filename);
        }

        if (!Files.exists(targetLocation) || !Files.isRegularFile(targetLocation) || !Files.isReadable(targetLocation)) {
            throw new FileNotFoundException("Document file not found: " + filename);
        }

        try {
            Resource resource = new UrlResource(targetLocation.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new FileNotFoundException("Document file not readable: " + filename);
            }
            return resource;
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid file URI: " + filename, e);
        }
    }

    public static void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\") || filename.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
    }
}
