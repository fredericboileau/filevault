package com.clevertricks.filevault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresqlFileSystemStorageServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Path tempDir;
    static StorageProperties props;
    private static PostgresqlFileSystemStorageService service;
    private static Connection conn;

    private static final String USER_ID = UUID.randomUUID().toString();
    private static final String OTHER_USER_ID = UUID.randomUUID().toString();

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("filevault-test");

        props = new StorageProperties();
        props.setHost(postgres.getHost());
        props.setPort(postgres.getMappedPort(5432));
        props.setDb(postgres.getDatabaseName());
        props.setUsername(postgres.getUsername());
        props.setPassword(postgres.getPassword());
        props.setLocation(tempDir.toString());
        props.setMaxFilesSize(10_000L);

        service = new PostgresqlFileSystemStorageService(props);
        service.init();

        // Pre-insert users since Keycloak SPI normally does this
        conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        insertUser(USER_ID, "alice");
        insertUser(OTHER_USER_ID, "bob");
    }

    @AfterEach
    void tearDown() throws Exception {
        service.deleteAll();
        service.init();
        insertUser(USER_ID, "alice");
        insertUser(OTHER_USER_ID, "bob");
    }

    private static void insertUser(String userId, String username) throws Exception {
        var stmt = conn.prepareStatement(
                "insert into users (userId, username) values (?, ?) on conflict do nothing");
        stmt.setObject(1, UUID.fromString(userId));
        stmt.setString(2, username);
        stmt.executeUpdate();
    }

    @Test
    void store_savesFileAndRecordsInDb() {
        var file = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        service.store(file, USER_ID);

        var files = service.loadAll(USER_ID).toList();
        assertEquals(List.of("hello.txt"), files);
    }

    @Test
    void store_throwsOnDuplicate() {
        var file = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        service.store(file, USER_ID);

        assertThrows(StorageFileAlreadyExistsException.class,
                () -> service.store(file, USER_ID));
    }

    @Test
    void store_throwsOnEmptyFile() {
        var file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        assertThrows(StorageFileEmptyException.class,
                () -> service.store(file, USER_ID));
    }

    @Test
    void store_throwsWhenStorageLimitExceeded() {
        var bigContent = new byte[(int) props.getMaxFilesSize() + 1];
        var file = new MockMultipartFile("file", "big.txt", "text/plain", bigContent);
        assertThrows(StorageLimitExceededException.class,
                () -> service.store(file, USER_ID));
    }

    @Test
    void loadAll_returnsOnlyOwnerFiles() {
        var file1 = new MockMultipartFile("file", "a.txt", "text/plain", "a".getBytes());
        var file2 = new MockMultipartFile("file", "b.txt", "text/plain", "b".getBytes());
        service.store(file1, USER_ID);
        service.store(file2, OTHER_USER_ID);

        var files = service.loadAll(USER_ID).toList();
        assertEquals(List.of("a.txt"), files);
    }

    @Test
    void loadAsResource_returnsReadableResource() {
        var file = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        service.store(file, USER_ID);

        var resource = service.loadAsResource("hello.txt", USER_ID);
        assertNotNull(resource);
        assertTrue(resource.isReadable());
    }

    @Test
    void loadAsResource_returnsNullForMissingFile() {
        assertThrows(StorageFileNotFoundException.class,
                () -> service.loadAsResource("nonexistent.txt", USER_ID));
    }

    @Test
    void delete_removesFileAndUpdatesTotalSize() {
        var file = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        service.store(file, USER_ID);
        long sizeAfterUpload = service.getTotalSize(USER_ID);
        assertTrue(sizeAfterUpload > 0);

        service.delete("hello.txt", USER_ID);

        assertEquals(0L, service.getTotalSize(USER_ID));
        assertEquals(List.of(), service.loadAll(USER_ID).toList());
    }

    @Test
    void getTotalSize_reflectsUploadedFiles() {
        var file = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        service.store(file, USER_ID);

        assertEquals(5L, service.getTotalSize(USER_ID));
    }

    @Test
    void shareFilesWithUser_andListShares() {
        var file = new MockMultipartFile("file", "shared.txt", "text/plain", "data".getBytes());
        service.store(file, USER_ID);

        service.shareFilesWithUser(List.of("shared.txt"), USER_ID, OTHER_USER_ID);

        var shares = service.listShares(OTHER_USER_ID);
        assertTrue(shares.containsKey(USER_ID));
        assertTrue(shares.get(USER_ID).contains("shared.txt"));
    }

    @Test
    void listAllUsers_returnsAllUsers() {
        var users = service.listAllUsers();
        assertTrue(users.containsKey(USER_ID));
        assertTrue(users.containsKey(OTHER_USER_ID));
        assertEquals("alice", users.get(USER_ID));
    }
}
