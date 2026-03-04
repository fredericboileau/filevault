package com.clevertricks.filevault;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

@Service
public class PostgresqlFileSystemStorageService implements StorageService {

    private final String baseUrl = "jdbc:postgresql://";
    private final String host;
    private final int port;
    private final String db;
    private final String dbUrl;
    private final long maxFilesSize;

    private final Connection conn;

    private final Path rootLocation;

    @Autowired
    PostgresqlFileSystemStorageService(StorageProperties properties) {
        this.host = properties.getHost();
        this.port = properties.getPort();
        this.db = properties.getDb();
        this.dbUrl = baseUrl + host + ":" + String.valueOf(port) + "/" + db;
        this.maxFilesSize = properties.getMaxFilesSize();

        if (properties.getLocation().trim().length() == 0) {
            throw new StorageException("File upload location cannot be Empty");
        }
        this.rootLocation = Paths.get(properties.getLocation());

        try {
            this.conn = DriverManager.getConnection(dbUrl, properties.getUsername(), properties.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to database", e);
        }
    }

    @Override
    public void store(MultipartFile file, String owner) {
        long totalFilesSize = getTotalSize(owner);
        long available = maxFilesSize - totalFilesSize;
        if (available < file.getSize()) {
            throw new StorageLimitExceededException(available, file.getSize());
        }

        if (file.isEmpty()) {
            throw new StorageFileEmptyException("Cannot store empty file");
        }
        try {
            Files.createDirectories(Paths.get(rootLocation.toString(), owner));
            var originalFilename = file.getOriginalFilename();
            if (!Paths.get(originalFilename).getFileName().toString().equals(originalFilename)) {
                throw new StorageException("Filename cannot contain path separators");
            }
            var destinationFile = Paths.get(rootLocation.toString(), owner, originalFilename);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile);
            }
            try {
                var filePathsSql = "insert into filepaths (name, owner) values (?,?)";
                var filePathsStmt = conn.prepareStatement(filePathsSql);
                filePathsStmt.setString(1, file.getOriginalFilename());
                filePathsStmt.setObject(2, UUID.fromString(owner));
                filePathsStmt.executeUpdate();

                var updateSql = "update users set total_size = total_size + ? where userId = ?";
                var updateStmt = conn.prepareStatement(updateSql);
                updateStmt.setLong(1, file.getSize());
                updateStmt.setObject(2, UUID.fromString(owner));
                updateStmt.executeUpdate();
            } catch (SQLException e) {
                Files.deleteIfExists(destinationFile);
                if ("23505".equals(e.getSQLState())) {
                    throw new StorageFileAlreadyExistsException(
                            "File already exists: " + originalFilename);

                }
            }

        } catch (FileAlreadyExistsException e) {
            throw new StorageFileAlreadyExistsException("File already exists");
        } catch (IOException e) {
            throw new StorageException("Could not store file", e);
        }
    }

    @Override
    public Stream<String> loadAll(String owner) {
        var sql = "select name from filepaths where owner = ?";
        Stream.Builder<String> builder = Stream.builder();
        try {
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setObject(1, UUID.fromString(owner));
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                builder.add(rs.getString(1));
            }
            return builder.build();
        } catch (SQLException e) {
            throw new StorageException("Could not retrieve filename list", e);
        }

    }

    @Override
    public Resource loadAsResource(String filename, String owner) {
        try {
            Path file = Paths.get(rootLocation.toString(), owner, filename);
            Resource resource = new UrlResource(file.toUri());
            if (resource.isReadable()) {
                return resource;
            } else {
                throw new StorageFileNotFoundException("Could not read file: " + filename);
            }
        } catch (MalformedURLException e) {
            throw new StorageFileNotFoundException("Could not read file: " + filename, e);
        }

    }

    @Override
    public Stream<String> listOwners() {
        var sql = "select distinct owner from filepaths";
        Stream.Builder<String> builder = Stream.builder();
        try {
            ResultSet rs = conn.createStatement().executeQuery(sql);
            while (rs.next()) {
                builder.add(rs.getString(1));
            }
            return builder.build();
        } catch (SQLException e) {
            throw new StorageException("Could not retrieve owner list", e);
        }
    }

    @Override
    public long getMaxFilesSize() {
        return maxFilesSize;
    }

    @Override
    public long getTotalSize(String userId) {
        var sql = "select total_size from users where userId = ?";
        try {
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setObject(1, UUID.fromString(userId));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            // No row found: restore consistency by computing size from filesystem
            Path ownerDir = Paths.get(rootLocation.toString(), userId);
            long totalSize = 0L;
            if (Files.exists(ownerDir)) {
                totalSize = Files.walk(ownerDir)
                        .filter(Files::isRegularFile)
                        .mapToLong(p -> p.toFile().length())
                        .sum();
            }
            var updateSql = "update users set total_size = ? where userId = ?";
            var updateStmt = conn.prepareStatement(updateSql);
            updateStmt.setLong(1, totalSize);
            updateStmt.setObject(2, UUID.fromString(userId));
            updateStmt.executeUpdate();
            return totalSize;
        } catch (SQLException | IOException e) {
            throw new StorageException("Could not retrieve total size for " + userId, e);
        }
    }

    @Override
    public boolean isSharedWith(String filename, String owner, String userId) {
        var sql = """
                select 1 from file_shares fs
                join filepaths fp on fs.file_id = fp.id
                where fp.name = ? and fp.owner = ? and fs.shared_with = ?
                """;
        try {
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, filename);
            stmt.setObject(2, UUID.fromString(owner));
            stmt.setObject(3, UUID.fromString(userId));
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new StorageException("Could not check share permission", e);
        }
    }

    @Override
    public Map<String, String> listAllUsers() {
        var sql = "select userId, username from users";
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try {
            ResultSet rs = conn.createStatement().executeQuery(sql);
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new StorageException("Could not retrieve users", e);
        }
        return result;
    }

    @Override
    public void shareFilesWithUser(List<String> filenames, String owner, String userId) {
        var selectSql = "select id from filepaths where name = ? and owner = ?";
        var insertSql = "insert into file_shares (file_id, shared_with) values (?, ?) on conflict do nothing";
        try {
            PreparedStatement selectStmt = conn.prepareStatement(selectSql);
            PreparedStatement insertStmt = conn.prepareStatement(insertSql);
            for (String filename : filenames) {
                selectStmt.setString(1, filename);
                selectStmt.setObject(2, UUID.fromString(owner));
                ResultSet rs = selectStmt.executeQuery();
                if (!rs.next()) {
                    throw new StorageFileNotFoundException("File not found: " + filename);
                }
                insertStmt.setInt(1, rs.getInt(1));
                insertStmt.setObject(2, UUID.fromString(userId));
                insertStmt.addBatch();
            }
            insertStmt.executeBatch();
        } catch (SQLException e) {
            throw new StorageException("Could not share files with user " + userId, e);
        }
    }

    @Override
    public Map<String, List<String>> listShares(String userId) {
        var sql = """
                select fp.owner, fp.name
                from filepaths fp
                join file_shares fs on fs.file_id = fp.id
                where fs.shared_with = ?
                """;
        try {
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setObject(1, UUID.fromString(userId));
            ResultSet rs = stmt.executeQuery();
            record Share(String owner, String name) {
            }
            List<Share> rows = new ArrayList<>();

            while (rs.next()) {
                rows.add(new Share(rs.getString("owner"), rs.getString("name")));
            }
            return rows.stream().collect(
                    Collectors.groupingBy(Share::owner,
                            Collectors.mapping(Share::name,
                                    Collectors.toList())));
        } catch (SQLException e) {
            throw new StorageException("Could not retrieve shares for " + userId, e);
        }
    }

    @Override
    public String lookupUsername(String userId) {
        var sql = "select username from users where userId = ?";
        try {
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setObject(1, UUID.fromString(userId));
            ResultSet rs = stmt.executeQuery();
            if (rs.next())
                return rs.getString(1);
        } catch (SQLException e) {
            throw new StorageException("Could not look up username for " + userId, e);
        }
        return userId;
    }

    @Override
    public void deleteAllForOwner(String owner) {
        var sql = "delete from filepaths where owner = ?";
        try {
            FileSystemUtils.deleteRecursively(Paths.get(rootLocation.toString(), owner).toFile());
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setObject(1, UUID.fromString(owner));
            stmt.executeUpdate();

            var resetSql = "update users set total_size = 0 where userId = ?";
            var resetStmt = conn.prepareStatement(resetSql);
            resetStmt.setObject(1, UUID.fromString(owner));
            resetStmt.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Could not delete DB entries for owner " + owner, e);
        }
    }

    @Override
    public void deleteAll() {
        try {
            if (Files.exists(rootLocation) && !FileSystemUtils.deleteRecursively(rootLocation.toFile())) {
                throw new StorageException("Could not delete all files");
            }
            conn.createStatement().execute("drop table if exists file_shares");
            conn.createStatement().execute("drop table if exists filepaths");
            conn.createStatement().execute("update users set total_size = 0");
        } catch (SQLException e) {
            throw new StorageException("Could not delete tables", e);
        }
    }

    @Override
    public void delete(String filename, String owner) {
        long fileSize = 0L;
        try {
            Path filePath = Paths.get(rootLocation.toString(), owner, filename);
            fileSize = Files.exists(filePath) ? Files.size(filePath) : 0L;
            if (fileSize > 0) {
                Files.delete(filePath);
            } else {
                System.out.println("File doesn't exist, trying to restore consistency");
            }
        } catch (IOException e) {
            throw new StorageException("Could not delete file");
        }
        try {
            var deleteSql = "delete from filepaths where name = ? and owner = ?";
            var deleteStmt = conn.prepareStatement(deleteSql);
            deleteStmt.setString(1, filename);
            deleteStmt.setObject(2, UUID.fromString(owner));
            deleteStmt.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("File deleted from filesystem but not the corresponding entry in DB");
        }
        try {
            var updateSql = "update users set total_size = greatest(0, total_size - ?) where userId = ?";
            var updateStmt = conn.prepareStatement(updateSql);
            updateStmt.setLong(1, fileSize);
            updateStmt.setObject(2, UUID.fromString(owner));
            updateStmt.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Deleted the file but couldnt update users table");
        }
    }

    @Override
    public void init() {
        try {
            Files.createDirectories(rootLocation);
            var sqlFilePaths = """
                    create table if not exists filepaths (
                            id serial primary key,
                            name varchar(255) not null,
                            owner uuid not null,
                            unique (name, owner)
                            )
                    """;
            conn.createStatement().execute(sqlFilePaths);
            var sqlUsers = """
                    create table if not exists users (
                            userId uuid primary key,
                            username varchar(255),
                            total_size bigint not null default 0
                            )
                    """;
            conn.createStatement().execute(sqlUsers);
            var sqlShares = """
                    create table if not exists file_shares (
                            file_id int references filepaths(id) on delete cascade,
                            shared_with uuid references users(userId) on delete cascade,
                            primary key (file_id, shared_with)
                            )
                    """;
            conn.createStatement().execute(sqlShares);
        } catch (SQLException e) {
            throw new StorageException("Failed to initialize storage", e);
        } catch (IOException e) {
            throw new StorageException("Could not initialize storage", e);
        }
    }
}
