package io.airtap.preview;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.airtap.preview.FileTransferStoreTest.*;

public class SafPublicationStoreTest {
    static final String ROOT="saf_"+"a".repeat(32);
    static FileTransferStore.DirectorySync sync=path->{try(var dir=java.nio.channels.FileChannel.open(path,StandardOpenOption.READ)){dir.force(true);}};
    static class Provider implements SafPublicationStore.Provider {
        final Path dir;int creates,writes;boolean failAfterCreate,failPartialWrite,corruptWrite,rename,allow=true;
        Provider()throws Exception{dir=Files.createTempDirectory("fake-saf-");}
        void check(){if(!allow)FileTransferStore.fail("file_scope_revoked");}
        public String prepare(String root,String path){check();if(Files.exists(dir.resolve(path)))FileTransferStore.fail("file_exists");return "parent";}
        public void checkDestination(String root,String path,String parent){eq(parent,prepare(root,path));}
        public String create(String root,String path,String parent)throws Exception{
            check();creates++;String name=rename?path+"(1)":path;Files.createFile(dir.resolve(name));if(failAfterCreate)throw new IOException("unknown create result");return name;
        }
        public void write(String root,String path,String reference,Path sealed,long size)throws Exception{
            check();if(!reference.equals(path))FileTransferStore.fail("file_name_conflict");writes++;
            if(failPartialWrite){Files.write(dir.resolve(reference),new byte[]{1});throw new IOException("write interrupted");}
            Files.write(dir.resolve(reference),corruptWrite?new byte[]{1}:Files.readAllBytes(sealed));
        }
        public FileTransferStore.Entry verify(String root,String path,String reference,long size,String hash)throws Exception{
            check();byte[] b=Files.readAllBytes(dir.resolve(reference));if(b.length!=size||!hash(b).equals(hash))FileTransferStore.fail("file_hash_mismatch");
            return new FileTransferStore.Entry(path,path,"file",size,123,"b".repeat(64),hash);
        }
        public void deleteCreated(String root,String reference)throws Exception{check();Files.deleteIfExists(dir.resolve(reference));}
    }
    static SafPublicationStore store(Path base)throws Exception{return new SafPublicationStore(base,()->{},sync);}
    static String receive(SafPublicationStore s,Provider p,String path,byte[] data)throws Exception{
        String id=id();s.begin(ROOT,path,id,data.length,hash(data),p);
        for(int at=0;at<data.length;at+=4096){byte[] b=Arrays.copyOfRange(data,at,Math.min(data.length,at+4096));s.chunk(id,at,b,hash(b));}return id;
    }
    public static void main(String[] args)throws Exception{
        test("SAF verifies sealed bytes and completed receipt; one create",()->{
            var s=store(fresh());var p=new Provider();byte[] data=new byte[9000];new Random(5).nextBytes(data);String id=receive(s,p,"a.bin",data);var done=s.finish(id,p);eq(done.state(),"finished");eq(done.file().sha256(),hash(data));eq(s.finish(id,p),done);eq(p.creates,1);
        });
        test("SAF partial provider write retries same recorded URI",()->{
            Path base=fresh();var s=store(base);var p=new Provider();String id=receive(s,p,"a",bytes("abcdef"));p.failPartialWrite=true;
            try{s.finish(id,p);throw new AssertionError();}catch(IOException expected){}
            eq(s.status(id).state(),"publishing");eq(s.status(id).offset(),6L);eq(s.status(id).cleanupRequired(),false);p.failPartialWrite=false;s=store(base);eq(s.finish(id,p).state(),"finished");eq(p.creates,1);eq(p.writes,2);
        });
        test("SAF unknown creation never blindly creates or deletes",()->{
            Path base=fresh();var s=store(base);var p=new Provider();String id=receive(s,p,"a",bytes("abc"));p.failAfterCreate=true;
            try{s.finish(id,p);throw new AssertionError();}catch(IOException expected){}
            SafPublicationStore recovered=store(base);eq(recovered.status(id).state(),"commit_unknown");eq(recovered.status(id).offset(),3L);refused("file_commit_unknown",()->recovered.finish(id,p));refused("file_commit_unknown",()->recovered.abort(id,p));eq(p.creates,1);eq(Files.exists(p.dir.resolve("a")),true);
        });
        test("SAF provider rename is not reported at requested path; abort knows only created URI",()->{
            var s=store(fresh());var p=new Provider();String id=receive(s,p,"a",bytes("abc"));p.rename=true;refused("file_name_conflict",()->s.finish(id,p));eq(s.status(id).cleanupRequired(),true);eq(Files.exists(p.dir.resolve("a")),false);eq(s.abort(id,p).state(),"aborted");eq(Files.exists(p.dir.resolve("a(1)")),false);
        });
        test("SAF readback hash failure can rewrite same document without recreate",()->{
            var s=store(fresh());var p=new Provider();String id=receive(s,p,"a",bytes("abc"));p.corruptWrite=true;refused("file_hash_mismatch",()->s.finish(id,p));p.corruptWrite=false;eq(s.finish(id,p).state(),"finished");eq(p.creates,1);
        });
        test("SAF revoked scope prevents resume and cleanup until permitted",()->{
            var s=store(fresh());var p=new Provider();String id=receive(s,p,"a",bytes("abc"));p.failPartialWrite=true;
            try{s.finish(id,p);}catch(IOException expected){}p.allow=false;refused("file_scope_revoked",()->s.finish(id,p));refused("file_scope_revoked",()->s.abort(id,p));eq(Files.exists(p.dir.resolve("a")),true);p.allow=true;eq(s.abort(id,p).state(),"aborted");
        });
        test("SAF begin rejects existing file and same ID changed scope",()->{
            var s=store(fresh());var p=new Provider();Files.writeString(p.dir.resolve("a"),"old");refused("file_exists",()->s.begin(ROOT,"a",id(),3,hash(bytes("new")),p));String id=receive(s,p,"b",bytes("abc"));refused("file_transfer_conflict",()->s.begin(ROOT,"c",id,3,hash(bytes("abc")),p));eq(Files.readString(p.dir.resolve("a")),"old");
        });
        test("SAF receiving outer journal recovers missing incoming begin",()->{
            Path base=fresh();var s=store(base);var p=new Provider();String id=id();s.begin(ROOT,"a",id,0,hash(new byte[0]),p);
            Files.delete(base.resolve("staging/transfers/"+id+".properties"));Files.delete(base.resolve("staging/transfers/"+id+".part"));s=store(base);eq(s.status(id).offset(),0L);eq(s.finish(id,p).state(),"finished");
        });
        test("SAF stopped command cannot create provider document",()->{
            Path base=fresh();AtomicBoolean active=new AtomicBoolean(true);Runnable fence=()->{if(!active.get())FileTransferStore.fail("permission_denied");};var s=new SafPublicationStore(base,fence,sync);var p=new Provider();String id=receive(s,p,"a",bytes("abc"));active.set(false);refused("permission_denied",()->s.finish(id,p));eq(p.creates,0);
        });
        test("expired unknown create retains durable identity and never repeats external create",()->{
            Path base=fresh();var p=new Provider();java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(1000);
            var s=new SafPublicationStore(base,()->{},sync,now::get);String id=receive(s,p,"unknown",bytes("payload"));p.failAfterCreate=true;
            try{s.finish(id,p);}catch(IOException expected){}long before=Files.size(base.resolve("staging/workspace/"+id));eq(before,7L);
            now.addAndGet(FileTransferStore.TTL+1);var recovered=new SafPublicationStore(base,()->{},sync,now::get);
            eq(Files.exists(base.resolve("staging/workspace/"+id)),false);eq(Files.exists(base.resolve("publications/"+id+".properties")),true);
            eq(recovered.status(id).state(),"commit_unknown");eq(recovered.begin(ROOT,"unknown",id,7,hash(bytes("payload")),p).state(),"commit_unknown");
            refused("file_commit_unknown",()->recovered.finish(id,p));refused("file_commit_unknown",()->recovered.abort(id,p));eq(p.creates,1);eq(Files.exists(p.dir.resolve("unknown")),true);
        });
        test("expired known publication frees staging but can abort only its recorded document",()->{
            Path base=fresh();var p=new Provider();java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(1000);
            var s=new SafPublicationStore(base,()->{},sync,now::get);String id=receive(s,p,"known",bytes("payload"));p.failPartialWrite=true;
            try{s.finish(id,p);}catch(IOException expected){}Files.writeString(p.dir.resolve("neighbour"),"keep");
            now.addAndGet(FileTransferStore.TTL+1);var recovered=new SafPublicationStore(base,()->{},sync,now::get);
            eq(recovered.status(id).state(),"expired");eq(recovered.status(id).cleanupRequired(),true);refused("file_transfer_expired",()->recovered.finish(id,p));
            eq(recovered.abort(id,p).state(),"aborted");eq(Files.exists(p.dir.resolve("known")),false);eq(Files.readString(p.dir.resolve("neighbour")),"keep");eq(p.creates,1);
        });
        test("expired receiving cannot silently restart lost payload under same identity",()->{
            Path base=fresh();var p=new Provider();java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(1000);
            var s=new SafPublicationStore(base,()->{},sync,now::get);String id=receive(s,p,"receiving",bytes("payload"));
            now.addAndGet(FileTransferStore.TTL+1);var recovered=new SafPublicationStore(base,()->{},sync,now::get);
            eq(recovered.status(id).state(),"expired");eq(recovered.begin(ROOT,"receiving",id,7,hash(bytes("payload")),p).state(),"expired");
            refused("file_transfer_expired",()->recovered.chunk(id,0,bytes("p"),hash(bytes("p"))));eq(recovered.abort(id,p).state(),"aborted");eq(p.creates,0);
        });
        test("picker success consumes exactly one current local attempt",()->{var a=new FileGrantAttempt();a.begin(72,"owner");eq(a.consume(72),"owner");eq(a.consume(72),null);});
        test("picker Home then owner-screen return rejects late result",()->{var a=new FileGrantAttempt();a.begin(72,"owner");a.cancel();eq(a.matches(72),false);eq(a.consume(72),null);});
        test("late old picker cannot consume a new picker attempt",()->{var a=new FileGrantAttempt();a.begin(72,"owner");a.cancel();a.begin(73,"owner");eq(a.consume(72),null);eq(a.consume(73),"owner");});
        test("activity recreation without live attempt refuses old result",()->{var old=new FileGrantAttempt();old.begin(72,"owner");var recreated=new FileGrantAttempt();eq(recreated.consume(72),null);});
        System.out.println("TOTAL "+passed);
    }
}
