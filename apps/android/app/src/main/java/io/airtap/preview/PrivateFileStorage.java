package io.airtap.preview;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** All-pairing accounting and explicit owner cleanup. Caller holds FileScopes' file IO lock. */
final class PrivateFileStorage {
    static final long BUDGET = 256L * 1024 * 1024, FREE_RESERVE = 16L * 1024 * 1024;
    static final long JOURNAL_RESERVE = 65536; // Two stores/journals and their atomic replacement files.
    record Usage(long usedBytes, long reservedBytes, long workspaceBytes, long workspaceFiles) {}
    private final Path root;
    private final Runnable fence;
    private final FileTransferStore.DirectorySync sync;
    private final long budget;
    PrivateFileStorage(Path root, Runnable fence, FileTransferStore.DirectorySync sync) { this(root,fence,sync,BUDGET); }
    PrivateFileStorage(Path root, Runnable fence, FileTransferStore.DirectorySync sync, long budget) {
        this.root=root;this.fence=fence;this.sync=sync;this.budget=budget;
    }
    private boolean exists(Path p) { return Files.exists(p,LinkOption.NOFOLLOW_LINKS); }
    private void safe(Path p) throws IOException {
        fence.run(); if(Files.isSymbolicLink(p))FileTransferStore.fail("file_symlink_refused");
        if(exists(p)&&!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))FileTransferStore.fail("file_type_unsupported");
    }
    private List<Path> claims() throws IOException {
        safe(root);List<Path> result=new ArrayList<>();if(!exists(root))return result;
        try(var dirs=Files.newDirectoryStream(root)){for(Path p:dirs){safe(p);if(p.getFileName().toString().matches("[a-f0-9]{32}")&&Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))result.add(p);}}
        return result;
    }
    private long[] count(Path p) throws IOException {
        long[] total={0,0};safe(p);if(!exists(p))return total;
        Files.walkFileTree(p,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{safe(d);return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{safe(f);total[0]=Math.addExact(total[0],a.size());total[1]++;return FileVisitResult.CONTINUE;}
        });return total;
    }
    private Properties record(Path p) throws IOException {
        safe(p);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>16384)FileTransferStore.fail("file_storage_failure");
        Properties value=new Properties();try(InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){value.load(in);}return value;
    }
    private long size(Properties p) {
        try{long n=Long.parseLong(p.getProperty("size"));if(n<0||n>FileTransferStore.MAX_FILE)throw new NumberFormatException();return n;}
        catch(RuntimeException invalid){FileTransferStore.fail("file_storage_failure");return 0;}
    }
    private long length(Path p)throws IOException {safe(p);return Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)?Files.size(p):0;}
    private long reservations(Path store, Path publications)throws IOException {
        Path dir=store.resolve("transfers");safe(dir);if(!exists(dir))return 0;long total=0;
        try(var entries=Files.newDirectoryStream(dir,"*.properties")){for(Path m:entries){
            fence.run();String name=m.getFileName().toString();if(!name.matches("[a-f0-9]{32}\\.properties"))FileTransferStore.fail("file_storage_failure");
            Properties p=record(m);String state=p.getProperty("state");if(state==null)FileTransferStore.fail("file_storage_failure");
            if(Set.of("finished","aborted").contains(state))continue;
            if(!Set.of("receiving","committing").contains(state))FileTransferStore.fail("file_storage_failure");
            String id=name.substring(0,32);long stored=length(dir.resolve(id+".part"));
            if(state.equals("committing")&&!exists(dir.resolve(id+".part"))){String relative=p.getProperty("path");FileTransferStore.validPath(relative,false);Path target=store.resolve("workspace");for(String part:relative.split("/")){target=target.resolve(part);safe(target);}stored=length(target);}
            long journals=length(m)+length(dir.resolve(id+".pending"));
            if(publications!=null)journals+=length(publications.resolve(name))+length(publications.resolve(id+".pending"));
            total=Math.addExact(total,Math.max(0,size(p)-stored)+Math.max(0,JOURNAL_RESERVE-journals));
        }}return total;
    }
    Usage usage(boolean includeReservations)throws IOException {
        long used=count(root)[0],work=0,files=0,reserved=0;
        for(Path claim:claims()){
            for(String name:List.of("workspace","transfers")){long[] n=count(claim.resolve(name));work+=n[0];files+=n[1];}
            if(!includeReservations)continue;
            reserved+=reservations(claim,null);Path saf=claim.resolve("saf"),staging=saf.resolve("staging");safe(saf);safe(staging);reserved+=reservations(staging,saf.resolve("publications"));
            Path publications=saf.resolve("publications");safe(publications);if(!exists(publications))continue;
            try(var entries=Files.newDirectoryStream(publications,"*.properties")){for(Path m:entries){
                Properties p=record(m);String name=m.getFileName().toString();if(!name.matches("[a-f0-9]{32}\\.properties"))FileTransferStore.fail("file_storage_failure");String id=name.substring(0,32);
                String state=p.getProperty("state");
                if(state==null)FileTransferStore.fail("file_storage_failure");
                if(Set.of("finished","aborted").contains(state)||SafStagingCleanup.reclaimed(p))continue;
                if(!Set.of("receiving","creating","writing","verifying").contains(state))FileTransferStore.fail("file_storage_failure");
                Path inner=staging.resolve("transfers").resolve(name);
                if(exists(inner)&&Set.of("receiving","committing").contains(record(inner).getProperty("state")))continue; // Included by reservations(staging).
                long remaining=0;
                // Receiving is durably recorded before the inner transfer exists. Do not lose its reservation.
                if(state.equals("receiving")&&!exists(inner)){
                    long stored=Math.max(length(staging.resolve("transfers").resolve(id+".part")),length(staging.resolve("workspace").resolve(id)));
                    remaining=Math.max(0,size(p)-stored);
                }
                // A sealed inner file does not need size reserved again, but provider finish/abort still journals.
                long journals=length(m)+length(publications.resolve(id+".pending"))+length(inner)+length(staging.resolve("transfers").resolve(id+".pending"));
                reserved+=remaining+Math.max(0,JOURNAL_RESERVE-journals);
            }}
        }return new Usage(used,reserved,work,files);
    }
    Usage ownerUsage()throws IOException {
        Usage actual=usage(false);
        try { return usage(true); }
        catch (IOException|RuntimeException invalidReservation) { return new Usage(actual.usedBytes,-1,actual.workspaceBytes,actual.workspaceFiles); }
    }
    void check(long newBytes, long availableBytes)throws IOException {
        if(newBytes<0||newBytes>FileTransferStore.MAX_FILE)FileTransferStore.fail("invalid_request");
        Usage u=usage(true);long extra=newBytes==0?0:newBytes+JOURNAL_RESERVE;
        if(u.usedBytes+u.reservedBytes+extra>budget)FileTransferStore.fail("file_private_storage_budget");
        if(availableBytes<u.reservedBytes+extra+FREE_RESERVE)FileTransferStore.fail("file_storage_full");
    }
    void checkNew(long newBytes,long availableBytes)throws IOException {
        // Empty files also need a durable journal; reserve it before accepting begin.
        Usage u=usage(true);long extra=newBytes+JOURNAL_RESERVE;
        if(newBytes<0||newBytes>FileTransferStore.MAX_FILE)FileTransferStore.fail("invalid_request");
        if(u.usedBytes+u.reservedBytes+extra>budget)FileTransferStore.fail("file_private_storage_budget");
        if(availableBytes<u.reservedBytes+extra+FREE_RESERVE)FileTransferStore.fail("file_storage_full");
    }
    Usage reclaimSaf(String currentOwner,long now,boolean includeInactive)throws IOException {
        for(Path claim:claims()) {
            fence.run();
            SafStagingCleanup.reclaim(claim.resolve("saf"),now,includeInactive&&!claim.getFileName().toString().equals(currentOwner),fence,sync);
        }
        return ownerUsage();
    }
    Usage clearWorkspaces()throws IOException {
        List<Path> targets=new ArrayList<>();for(Path claim:claims())for(String child:List.of("workspace","transfers"))targets.add(claim.resolve(child));
        // Preflight all targets so a symlink or unsupported object never leads to traversal outside the scope.
        for(Path p:targets)count(p);
        for(Path p:targets){fence.run();if(!exists(p))continue;Files.walkFileTree(p,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{safe(d);return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{safe(f);Files.delete(f);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path d,IOException error)throws IOException{if(error!=null)throw error;fence.run();Files.delete(d);return FileVisitResult.CONTINUE;}
        });sync.sync(p.getParent());}
        return usage(false);
    }
}
