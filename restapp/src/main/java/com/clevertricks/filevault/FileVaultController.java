package com.clevertricks.filevault;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@RestController
public class FileVaultController {

    private final StorageService storageService;

    @Autowired
    public FileVaultController(StorageService storageService) {
        this.storageService = storageService;
    }

    private String extractUserId(OidcUser oidcUser) {
        return oidcUser.getSubject();
    }

    record ListFilesView(
            List<String> files,
            String username,
            long totalSize,
            long maxSize,
            Map<String, String> otherUsers,
            Map<String, List<String>> shares) {
    }

    @GetMapping("/")
    public ListFilesView listFiles(@AuthenticationPrincipal OidcUser user) {
        var userId = extractUserId(user);
        var otherUsers = storageService.listAllUsers();
        otherUsers.remove(userId);
        return new ListFilesView(
                storageService.loadAll(userId).collect(Collectors.toList()),
                user.getPreferredUsername(),
                storageService.getTotalSize(userId),
                storageService.getMaxFilesSize(),
                otherUsers,
                storageService.listShares(userId));
    }

    @PostMapping("/files")
    public ResponseEntity<String> handleFileUpload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal OidcUser oidcUser) {
        try {
            storageService.store(file, extractUserId(oidcUser));
        } catch (StorageFileNotFoundException e) {
            return ResponseEntity.status(409).body("File already exists");
        } catch (StorageFileEmptyException e) {
            return ResponseEntity.badRequest().body("Cannot upload empty file");
        } catch (StorageLimitExceededException e) {
            return ResponseEntity.status(413).body("Storage limit exceeded: " + e.getAvailable() + "bytes available");
        }
        return ResponseEntity.ok("Uploaded" + file.getOriginalFilename());
    }

    @GetMapping("/api/files/{filename:.+}")
    public ResponseEntity<Resource> serveFiles(
            @PathVariable String filename,
            @AuthenticationPrincipal OidcUser oidcUser) {
        Resource file = storageService.loadAsResource(filename, extractUserId(oidcUser));
        if (file == null)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"").body(file);

    }

}
