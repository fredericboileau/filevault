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
import org.springframework.web.bind.annotation.RequestBody;
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

    record ShareRequest(List<String> files, String userToShareWith) {
    }

    record UserSummary(String userId, String userName, long totalSize, List<String> files) {
    }

    record AdminPageView(List<UserSummary> usersSummary, long totalFiles, long totalSize) {
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

    @GetMapping("/files/{filename:.+}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String filename,
            @AuthenticationPrincipal OidcUser oidcUser) {
        Resource file = storageService.loadAsResource(filename, extractUserId(oidcUser));
        if (file == null)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"").body(file);

    }

    @GetMapping("/shared/{owner}/{filename:.+}")
    public ResponseEntity<Resource> serveSharedFile(
            @PathVariable String owner,
            @PathVariable String filename,
            @AuthenticationPrincipal OidcUser oidcUser) {
        if (!storageService.isSharedWith(filename, owner, extractUserId(oidcUser))) {
            return ResponseEntity.status(403).build();
        }
        Resource file = storageService.loadAsResource(filename, owner);
        if (file == null)
            return ResponseEntity.notFound().build();

        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"").body(file);
    }

    @PostMapping("/files")
    public ResponseEntity<String> handleFileUpload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal OidcUser oidcUser) {
        try {
            storageService.store(file, extractUserId(oidcUser));
        } catch (StorageFileAlreadyExistsException e) {
            return ResponseEntity.status(409).body("File already exists");
        } catch (StorageFileEmptyException e) {
            return ResponseEntity.badRequest().body("Cannot upload empty file");
        } catch (StorageLimitExceededException e) {
            return ResponseEntity.status(413)
                    .body("Storage limit exceeded: " + e.getAvailable() + "bytes available");
        }
        return ResponseEntity.ok("");
    }

    @PostMapping("/share")
    public ResponseEntity<String> shareFiles(
            @RequestBody ShareRequest body,
            @AuthenticationPrincipal OidcUser oidcUser) {
        try {
            storageService.shareFilesWithUser(body.files(), extractUserId(oidcUser), body.userToShareWith());
        } catch (StorageException e) {
            return ResponseEntity.badRequest().body("Couldn't share file");

        }
        return ResponseEntity.ok("");

    }

    @PostMapping("/files/delete")
    public ResponseEntity<String> deleteFiles(
            @RequestBody List<String> files,
            @AuthenticationPrincipal OidcUser oidcUser) {

        var owner = extractUserId(oidcUser);
        try {
            files.forEach(f -> storageService.delete(f, owner));
        } catch (StorageException e) {
            return ResponseEntity.status(500).body("Couldn't delete all files");
        }
        return ResponseEntity.ok("");
    }

    @GetMapping("/admin")
    public AdminPageView adminPage(
            @AuthenticationPrincipal OidcUser oidcUser) {
        List<UserSummary> usersSummaries = storageService.listOwners()
                .map(userId -> new UserSummary(
                        userId,
                        storageService.lookupUsername(userId),
                        storageService.getTotalSize(userId),
                        storageService.loadAll(userId).collect(Collectors.toList())))
                .collect(Collectors.toList());
        return new AdminPageView(
                usersSummaries,
                usersSummaries.stream().mapToLong(u -> u.files().size()).sum(),
                usersSummaries.stream().mapToLong(UserSummary::totalSize).sum());

    }

    @PostMapping("/admin/delete-user")
    public ResponseEntity<String> deleteUser(@RequestBody String userId) {
        try {
            storageService.deleteAllForOwner(userId);
        } catch (StorageException e) {
            return ResponseEntity.status(500).body("Could not delete all files for user" + userId);
        }
        return ResponseEntity.ok("");

    }

}
