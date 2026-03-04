package com.clevertricks.filevault;

public class StorageFileEmptyException extends StorageException {
    public StorageFileEmptyException(String message) {
        super(message);
    }

    public StorageFileEmptyException(String message, Throwable cause) {
        super(message, cause);
    }
}
