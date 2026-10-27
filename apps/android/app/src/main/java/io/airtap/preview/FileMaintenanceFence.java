package io.airtap.preview;

import java.util.function.Supplier;

/** Captured before queuing local cleanup; checked again under the IO lock and at every mutation. */
final class FileMaintenanceFence implements Runnable {
    record Identity(String owner,long fileEpoch,int sessionEpoch) {}
    private final Identity expected;
    private final Supplier<Identity> current;
    FileMaintenanceFence(Identity expected,Supplier<Identity> current){this.expected=expected;this.current=current;}
    @Override public void run(){if(!expected.equals(current.get()))FileTransferStore.fail("file_scope_changed");}
}
