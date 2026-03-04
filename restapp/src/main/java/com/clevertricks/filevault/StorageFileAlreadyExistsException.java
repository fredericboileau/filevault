package com.clevertricks.filevault;

public class StorageFileAlreadyExistsException extends StorageException {
    public StorageFileAlreadyExistsException(String message) {
        super(message);
    }
}
