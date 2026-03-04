# Overview
This is a SPA app for storing files and sharing them amongst users
in java spring for the backend and react for the frontend.
The backend is in the directory restapp. It uses keycloak for authentification 
and postgresql for managing user and file data.

# Backend
The backend uses a StorageService for the FileUploadController. It uses keycloak for authentification.
Keycloak syncs user data with the postgresql through an EventListener SPI in ./keycloak/spi.
The only implementation of StorageService is PostgresqlFileSystemStorageService wich
stores files on the disk and uses postgresql to store information about files and links between files and users.
