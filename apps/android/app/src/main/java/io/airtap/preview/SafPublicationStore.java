package io.airtap.preview;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Journals non-atomic provider publication separately from the verified private byte transfer. */
final class SafPublicationStore {
    interface Provider {
        String prepare(String root, String path) throws Exception;
        void checkDestination(String root, String path, String parent) throws Exception;
        String create(String root, String path, String parent) throws Exception;
        void write(String root, String path, String reference, Path sealed, long size) throws Exception;
        FileTransferStore.Entry verify(String root, String path, String reference, long size, String hash) throws Exception;
        void deleteCreated(String root, String reference) throws Exception;
    }
    record Result(String id, String root, String path, String state, long offset, long size, String hash, long expires,
                  FileTransferStore.Entry file, boolean cleanupRequired) {}
    private final Path records;
    private final FileTransferStore incoming;
    private final Runnable fence;
    private final FileTransferStore.DirectorySync sync;
    private final java.util.function.LongSupplier clock;
    SafPublicationStore(Path base, Runnable fence, FileTransferStore.DirectorySync sync) throws IOException {
        this(base, fence, sync, System::currentTimeMillis);
    }
    SafPublicationStore(Path base, Runnable fence, FileTransferStore.DirectorySync sync, java.util.function.LongSupplier clock) throws IOException {
        this.fence=fence;this.sync=sync;this.clock=clock;
        SafStagingCleanup.reclaim(base,clock.getAsLong(),false,fence,sync);
        incoming=new FileTransferStore(base.resolve("staging"), fence, clock, sync);
        records=base.resolve("publications");
        if(Files.isSymbolicLink(records))FileTransferStore.fail("file_scope_unavailable");
        Files.createDirectories(records);sync.sync(base);cleanup();
    }
    private Path meta(String id){FileTransferStore.id(id);return records.resolve(id+".properties");}
    int activeCount()throws IOException{
        int count=0;try(var files=Files.newDirectoryStream(records,"*.properties")){
            for(Path file:files)if(!Set.of("finished","aborted").contains(read(file.getFileName().toString().substring(0,32)).getProperty("state"))&&!SafStagingCleanup.reclaimed(read(file.getFileName().toString().substring(0,32))))count++;
        }return count;
    }
    boolean known(String id){return Files.isRegularFile(meta(id),LinkOption.NOFOLLOW_LINKS);}
    private Properties read(String id)throws IOException{
        if(!known(id))FileTransferStore.fail("file_transfer_not_found");
        if(Files.size(meta(id))>16384)FileTransferStore.fail("file_storage_failure");
        Properties p=new Properties();try(InputStream in=Files.newInputStream(meta(id),LinkOption.NOFOLLOW_LINKS)){p.load(in);}
        if(Long.parseLong(p.getProperty("expires"))<=clock.getAsLong()&&!SafStagingCleanup.reclaimed(p))FileTransferStore.fail("file_transfer_not_found");
        return p;
    }
    private void save(String id,Properties p)throws IOException{
        Path temp=records.resolve(id+".pending");
        try(FileOutputStream out=new FileOutputStream(temp.toFile())){p.store(out,null);out.getFD().sync();}
        Files.move(temp,meta(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.sync(records);
    }
    private void cleanup()throws IOException{
        SafStagingCleanup.reclaim(records.getParent(),clock.getAsLong(),false,fence,sync);
    }
    Result begin(String root,String path,String id,long size,String hash,Provider provider)throws Exception{
        FileTransferStore.id(id);FileTransferStore.validPath(path,false);FileTransferStore.hash(hash);
        if(known(id)){
            Properties p=read(id);
            if(!root.equals(p.getProperty("root"))||!path.equals(p.getProperty("path"))||size!=Long.parseLong(p.getProperty("size"))||!hash.equals(p.getProperty("hash")))FileTransferStore.fail("file_transfer_conflict");
            if(SafStagingCleanup.reclaimed(p))return status(id);
            if("receiving".equals(p.getProperty("state")))incoming.begin(id,id,size,hash);
            return status(id);
        }
        String parent=provider.prepare(root,path);
        int count=0;try(var files=Files.newDirectoryStream(records,"*.properties")){for(Path file:files)if(!SafStagingCleanup.reclaimed(read(file.getFileName().toString().substring(0,32))))count++;}
        if(count>=FileTransferStore.MAX_RECORDS)FileTransferStore.fail("file_transfer_limit");
        Properties p=new Properties();p.setProperty("root",root);p.setProperty("path",path);p.setProperty("size",Long.toString(size));p.setProperty("hash",hash);
        p.setProperty("parent",parent);p.setProperty("state","receiving");p.setProperty("expires",Long.toString(clock.getAsLong()+FileTransferStore.TTL));
        fence.run();save(id,p);incoming.begin(id,id,size,hash);return status(id);
    }
    Result status(String id)throws IOException{
        fence.run();Properties p=read(id);String state=p.getProperty("state");
        // A process stopped while createDocument could have succeeded: never issue a second create or guess ownership.
        if(state.equals("creating"))state="commit_unknown";
        if(state.equals("writing")||state.equals("verifying"))state="publishing";
        long offset=0,size=Long.parseLong(p.getProperty("size"));
        boolean reclaimed=SafStagingCleanup.reclaimed(p);
        if(reclaimed&&!Set.of("finished","aborted","commit_unknown").contains(state))state="expired";
        if(state.equals("receiving")&&!incoming.known(id))incoming.begin(id,id,size,p.getProperty("hash"));
        if(!reclaimed&&incoming.known(id))offset=incoming.status(id).offset();
        if(state.equals("publishing")||state.equals("commit_unknown"))offset=size;
        FileTransferStore.Entry file=null;
        if(state.equals("finished")){
            offset=size;String path=p.getProperty("path");
            file=new FileTransferStore.Entry(path,path.substring(path.lastIndexOf('/')+1),"file",size,Long.parseLong(p.getProperty("modified")),p.getProperty("version"),p.getProperty("hash"));
        }
        return new Result(id,p.getProperty("root"),p.getProperty("path"),state,offset,size,p.getProperty("hash"),Long.parseLong(p.getProperty("expires")),file,
                (Boolean.parseBoolean(p.getProperty("cleanup_required", "false"))||(reclaimed&&p.containsKey("reference")))&&!Set.of("finished","aborted").contains(state));
    }
    Result chunk(String id,long offset,byte[] bytes,String hash)throws Exception{
        Properties p=read(id);if(SafStagingCleanup.reclaimed(p))FileTransferStore.fail("file_transfer_expired");if(!p.getProperty("state").equals("receiving"))FileTransferStore.fail("file_transfer_not_receiving");
        incoming.chunk(id,offset,bytes,hash);return status(id);
    }
    Result finish(String id,Provider provider)throws Exception{
        Properties p=read(id);String state=p.getProperty("state");
        if(state.equals("finished"))return status(id);
        if(state.equals("creating"))FileTransferStore.fail("file_commit_unknown");
        if(SafStagingCleanup.reclaimed(p))FileTransferStore.fail("file_transfer_expired");
        if(state.equals("aborted"))FileTransferStore.fail("file_transfer_not_receiving");
        String root=p.getProperty("root"),path=p.getProperty("path");long size=Long.parseLong(p.getProperty("size"));
        incoming.finish(id);Path sealed=incoming.committedPath(id);
        if(state.equals("receiving")){
            provider.checkDestination(root,path,p.getProperty("parent"));fence.run();p.setProperty("state","creating");save(id,p);
            fence.run();String reference=provider.create(root,path,p.getProperty("parent"));
            // This exact provider-created reference is the only object that a retry or abort may touch.
            p.setProperty("reference",reference);p.setProperty("state","writing");save(id,p);
        }
        if(p.getProperty("state").equals("writing")){
            fence.run();
            try { provider.write(root,path,p.getProperty("reference"),sealed,size); }
            catch (FileTransferStore.Failure failure) {
                if (failure.code.equals("file_name_conflict")) { p.setProperty("cleanup_required","true");save(id,p); }
                throw failure;
            }
            p.setProperty("state","verifying");save(id,p);
        }
        fence.run();FileTransferStore.Entry actual;
        try { actual=provider.verify(root,path,p.getProperty("reference"),size,p.getProperty("hash")); }
        catch (FileTransferStore.Failure failure) {
            if(failure.code.equals("file_hash_mismatch")){p.setProperty("state","writing");save(id,p);}
            throw failure;
        }
        p.setProperty("state","finished");p.setProperty("modified",Long.toString(actual.modified()));p.setProperty("version",actual.version());save(id,p);
        incoming.discardCommitted(id);return status(id);
    }
    Result abort(String id,Provider provider)throws Exception{
        Properties p=read(id);String state=p.getProperty("state");
        if(state.equals("finished")||state.equals("aborted"))return status(id);
        if(state.equals("creating"))FileTransferStore.fail("file_commit_unknown");
        if(p.containsKey("reference")){fence.run();provider.deleteCreated(p.getProperty("root"),p.getProperty("reference"));}
        fence.run();p.setProperty("state","aborted");save(id,p);
        if(incoming.known(id)){var t=incoming.abort(id);if(t.state().equals("finished"))incoming.discardCommitted(id);}
        return status(id);
    }
}
