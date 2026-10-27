package io.airtap.preview;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reclaims only internal payloads; durable publication identities outlive their byte staging. */
final class SafStagingCleanup {
    private static final Set<String> STATES=Set.of("receiving","creating","writing","verifying","finished","aborted");
    static boolean reclaimed(Properties p) { return "true".equals(p.getProperty("payload_reclaimed")); }
    private static boolean exists(Path p) { return Files.exists(p,LinkOption.NOFOLLOW_LINKS); }
    private static void safe(Path p) throws IOException {
        if(Files.isSymbolicLink(p))FileTransferStore.fail("file_symlink_refused");
        if(exists(p)&&!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))FileTransferStore.fail("file_type_unsupported");
    }
    private static Properties read(Path p)throws IOException {
        safe(p);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>16384)FileTransferStore.fail("file_storage_failure");
        Properties out=new Properties();try(InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){out.load(in);}return out;
    }
    private static long expires(Properties p) {
        try {long n=Long.parseLong(p.getProperty("expires"));if(n<0)throw new NumberFormatException();return n;}
        catch(RuntimeException invalid){FileTransferStore.fail("file_storage_failure");return 0;}
    }
    static void reclaim(Path saf,long now,boolean inactive,Runnable fence,FileTransferStore.DirectorySync sync)throws IOException {
        safe(saf);if(!exists(saf))return;
        Path records=saf.resolve("publications"),staging=saf.resolve("staging"),transfers=staging.resolve("transfers"),workspace=staging.resolve("workspace");
        for(Path p:List.of(records,staging,transfers,workspace))safe(p);
        Map<String,Boolean> eligible=new HashMap<>();List<Path> remove=new ArrayList<>();Map<Path,Properties> retire=new LinkedHashMap<>();
        if(exists(records))try(var entries=Files.newDirectoryStream(records,"*.properties")){for(Path m:entries){
            fence.run();String name=m.getFileName().toString();if(!name.matches("[a-f0-9]{32}\\.properties"))FileTransferStore.fail("file_storage_failure");
            Properties p=read(m);if(!STATES.contains(p.getProperty("state","")))FileTransferStore.fail("file_storage_failure");
            boolean yes=inactive||reclaimed(p)||expires(p)<=now;String id=name.substring(0,32);eligible.put(id,yes);
            if(yes){if(!reclaimed(p)){p.setProperty("payload_reclaimed","true");p.setProperty("reclaim_reason",inactive?"pairing_inactive":"expired");retire.put(m,p);}
                for(Path f:List.of(workspace.resolve(id),transfers.resolve(id+".part"),transfers.resolve(id+".properties"),transfers.resolve(id+".pending"))) {safe(f);if(exists(f))remove.add(f);}
            }
        }}
        // Orphans have no provider identity to lose. Never remove a live publication's journal/payload.
        for(Path dir:List.of(workspace,transfers))if(exists(dir))try(var entries=Files.newDirectoryStream(dir)){for(Path f:entries){
            fence.run();safe(f);String name=f.getFileName().toString();String id=name.split("\\.",2)[0];
            if(!id.matches("[a-f0-9]{32}")||!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS))continue;
            if(!eligible.containsKey(id)&&(inactive||Files.getLastModifiedTime(f,LinkOption.NOFOLLOW_LINKS).toMillis()<=now-FileTransferStore.TTL))remove.add(f);
        }}
        // Persist tombstones first: interruption can only leave excess bytes, never permit a duplicate create.
        for(var item:retire.entrySet()){
            fence.run();Path m=item.getKey(),tmp=m.resolveSibling(m.getFileName().toString().replace(".properties",".pending"));safe(tmp);
            try(FileOutputStream out=new FileOutputStream(tmp.toFile())){item.getValue().store(out,null);out.getFD().sync();}
            Files.move(tmp,m,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.sync(records);
        }
        for(Path p:new LinkedHashSet<>(remove)){fence.run();safe(p);Files.deleteIfExists(p);sync.sync(p.getParent());}
    }
}
