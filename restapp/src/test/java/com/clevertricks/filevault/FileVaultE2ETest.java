package com.clevertricks.filevault;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests against a running docker compose stack.
 * Start the stack before running: docker compose up --build
 * Then provision Keycloak: keycloak/setuprealm.py
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileVaultE2ETest {

    private static final String APP_URL = "http://localhost:8080";
    private static final String TOKEN_URL = "http://localhost:8180/realms/filevault/protocol/openid-connect/token";
    private static final String CLIENT_ID = "filevault-app";
    private static final String CLIENT_SECRET = "filevault-secret";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;

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

    @BeforeAll
    void setUp() throws Exception {
        token = fetchToken(USERNAME, PASSWORD);
    }

    private String fetchToken(String username, String password) throws Exception {
        var body = String.format(
                "grant_type=password&client_id=%s&client_secret=%s&username=%s&password=%s",
                CLIENT_ID, CLIENT_SECRET, username, password);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Failed to get token: " + response.body());

        return mapper.readTree(response.body()).get("access_token").asString();
    }

    private String extractUserId(String token) throws Exception {
        String payload = token.split("\\.")[1];
        String decoded = new String(Base64.getUrlDecoder().decode(payload));
        return mapper.readTree(decoded).get("sub").asString();
    }

    @Test
    void uploadShare_listSharesIncluded() throws Exception {

        String aliceToken = fetchToken("alice", "alice");
        String bobToken = fetchToken("bob", "bob");
        var aliceId = extractUserId(aliceToken);
        var bobId = extractUserId(bobToken);

        var boundary = "boundary-" + System.currentTimeMillis();

        var filename = "aliceToShare";
        var content = "HELLO BOB".getBytes(StandardCharsets.UTF_8);
        var uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/files"))
                .header("Authorization", "Bearer " + aliceToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(boundary, content, filename))
                .build();
        var uploadResponse = http.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploadResponse.statusCode(), "Upload failed: " + uploadResponse.body());

        var body = mapper.writeValueAsString(new ShareRequest(List.of("aliceToShare"), bobId));
        var request = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/share"))
                .header("Authorization", "Bearer " + aliceToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var shareRequestResponse = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, shareRequestResponse.statusCode(), "Share request failed: " + shareRequestResponse.body());

        var listRequest = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL))
                .header("Authorization", "Bearer " + bobToken)
                .GET()
                .build();
        var listResponse = http.send(listRequest, HttpResponse.BodyHandlers.ofString());
        ListFilesView view = mapper.readValue(listResponse.body(), ListFilesView.class);
        assertTrue(view.shares().containsKey(aliceId));
        assertEquals(List.of("aliceToShare"), view.shares().get(aliceId));

    }

    @Test
    void listFiles_returnsOk() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        var json = mapper.readTree(response.body());
        assertTrue(json.has("files"));
        assertTrue(json.has("username"));
        assertTrue(json.has("totalSize"));
        assertTrue(json.has("maxSize"));
    }

    @Test
    void uploadFile_thenDownload() throws Exception {
        var filename = "e2e-test-" + System.currentTimeMillis() + ".txt";
        var content = "hello from e2e test".getBytes(StandardCharsets.UTF_8);
        var boundary = "boundary-" + System.currentTimeMillis();

        // Upload
        var uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/files"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(boundary, content, filename))
                .build();

        var uploadResponse = http.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploadResponse.statusCode(), "Upload failed: " + uploadResponse.body());

        // Download
        var downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/files/" + filename))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        var downloadResponse = http.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, downloadResponse.statusCode());
        assertArrayEquals(content, downloadResponse.body());
    }

    @Test
    void uploadEmptyFile_returnsBadRequest() throws Exception {
        var boundary = "boundary-" + System.currentTimeMillis();

        var request = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/files"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(boundary, new byte[0], "empty.txt"))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }

    @Test
    void downloadNonExistentFile_returnsNotFound() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/files/does-not-exist.txt"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    void requestWithoutToken_returnsUnauthorized() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(APP_URL + "/"))
                .GET()
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // Keycloak redirects to login or returns 401
        assertTrue(response.statusCode() == 401 || response.statusCode() == 302);
    }

    private HttpRequest.BodyPublisher multipartBody(String boundary, byte[] fileContent, String filename) {
        var prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        var combined = new byte[prefix.length + fileContent.length + suffix.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(fileContent, 0, combined, prefix.length, fileContent.length);
        System.arraycopy(suffix, 0, combined, prefix.length + fileContent.length, suffix.length);

        return HttpRequest.BodyPublishers.ofByteArray(combined);
    }

}
