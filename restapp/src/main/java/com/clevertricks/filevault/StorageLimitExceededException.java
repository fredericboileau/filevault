package com.clevertricks.filevault;

public class StorageLimitExceededException extends StorageException {
    private final long available;
    private final long attempted;

    public StorageLimitExceededException(long available, long attempted) {
        super("Storage limit exceeded: attempted " + attempted + " bytes but only " + available + " bytes available");
        this.available = available;
        this.attempted = attempted;
    }

    public long getAvailable() {
        return available;
    }

    public long getAttempted() {
        return attempted;
    }
}
