package io.airtap.preview;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

public class FileTransferStoreTest {
    static int passed;
    interface Check { void run() throws Exception; }
    static void test(String name, Check run) throws Exception { run.run(); System.out.println("PASS " + name); passed++; }
    static void eq(Object actual, Object expected) { if (!Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected); }
    static void refused(String code, Check run) throws Exception {
        try { run.run(); throw new AssertionError("Expected " + code); }
        catch (FileTransferStore.Failure f) { eq(f.code, code); }
    }
    static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    static Path fresh() throws Exception { return Files.createTempDirectory("airtap-files-test-").resolve("pair"); }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] data) { return FileTransferStore.digest(data); }
    static FileTransferStore store(Path base) throws Exception { return new FileTransferStore(base, () -> {}); }
    static FileTransferStore.Transfer upload(FileTransferStore s, String path, byte[] data) throws Exception {
        String id=id(); s.begin(path,id,data.length,hash(data));
        for(int offset=0;offset<data.length;offset+=4096){byte[] chunk=Arrays.copyOfRange(data,offset,Math.min(data.length,offset+4096));s.chunk(id,offset,chunk,hash(chunk));}
        return s.finish(id);
    }
    public static void main(String[] args) throws Exception {
        test("real multiblock binary Unicode filename and download hash", () -> {
            FileTransferStore s=store(fresh());byte[] data=new byte[9307];new Random(42).nextBytes(data);
            var done=upload(s,"报告📱.bin",data);eq(done.state(),"finished");var stat=s.stat(done.path(),true);eq(stat.sha256(),hash(data));
            ByteArrayOutputStream output=new ByteArrayOutputStream();long at=0;
            while(at<data.length){var chunk=s.read(done.path(),at,4096,stat.version());output.write(chunk.data());at=chunk.next();}
            eq(hash(output.toByteArray()),hash(data));eq(s.list("").size(),1);
        });
        test("empty upload supports stable repeated finish", () -> {
            FileTransferStore s=store(fresh());var done=upload(s,"empty",new byte[0]);eq(s.finish(done.id()),done);eq(s.stat("empty",true).size(),0L);
        });
        test("same begin id rejects a changed specification", () -> {
            FileTransferStore s=store(fresh());String id=id();byte[] b=bytes("abc");var a=s.begin("a",id,3,hash(b));eq(s.begin("a",id,3,hash(b)),a);
            refused("file_transfer_conflict",()->s.begin("b",id,3,hash(b)));
        });
        test("identical chunk retry does not append twice", () -> {
            FileTransferStore s=store(fresh());String id=id();byte[] b=bytes("abc");s.begin("a",id,3,hash(b));s.chunk(id,0,b,hash(b));eq(s.chunk(id,0,b,hash(b)).offset(),3L);eq(s.finish(id).state(),"finished");
        });
        test("partial chunk after process death appends only missing suffix", () -> {
            Path base=fresh();FileTransferStore s=store(base);String id=id();byte[] b=bytes("abcdef");s.begin("a",id,6,hash(b));
            Files.write(base.resolve("transfers/"+id+".part"),bytes("abc"));s=store(base);eq(s.chunk(id,0,b,hash(b)).offset(),6L);eq(s.finish(id).file().sha256(),hash(b));
        });
        test("missing empty staging file recovers before finish", () -> {
            Path base=fresh();FileTransferStore s=store(base);String id=id();s.begin("empty",id,0,hash(new byte[0]));Files.delete(base.resolve("transfers/"+id+".part"));
            s=store(base);eq(s.finish(id).state(),"finished");
        });
        test("mismatched overlap and skipped offset rejected", () -> {
            FileTransferStore s=store(fresh());String id=id();byte[] b=bytes("abc");s.begin("a",id,6,hash(bytes("abcdef")));s.chunk(id,0,b,hash(b));
            refused("file_offset_conflict",()->s.chunk(id,0,bytes("xyz"),hash(bytes("xyz"))));refused("file_offset_conflict",()->s.chunk(id,4,bytes("x"),hash(bytes("x"))));
        });
        test("chunk and final hash enforced without publication", () -> {
            FileTransferStore s=store(fresh());String id=id();byte[] b=bytes("abc");s.begin("a",id,3,hash(bytes("xyz")));
            refused("file_hash_mismatch",()->s.chunk(id,0,b,hash(bytes("xyz"))));s.chunk(id,0,b,hash(b));refused("file_hash_mismatch",()->s.finish(id));refused("file_not_found",()->s.stat("a",false));
        });
        test("incomplete transfer cannot publish", () -> {FileTransferStore s=store(fresh());String id=id();s.begin("a",id,3,hash(bytes("abc")));refused("file_incomplete",()->s.finish(id));});
        test("publish never overwrites an existing destination", () -> {
            Path base=fresh();FileTransferStore s=store(base);String id=id();byte[] b=bytes("new");s.begin("a",id,3,hash(b));s.chunk(id,0,b,hash(b));Files.write(base.resolve("workspace/a"),bytes("old"));
            refused("file_exists",()->s.finish(id));eq(Files.readString(base.resolve("workspace/a")),"old");
        });
        test("committing journal recovers atomic published inode after process restart", () -> {
            Path base=fresh();FileTransferStore s=store(base);String id=id();byte[] b=bytes("abc");s.begin("a",id,3,hash(b));s.chunk(id,0,b,hash(b));
            Path m=base.resolve("transfers/"+id+".properties");Properties p=new Properties();try(InputStream in=Files.newInputStream(m)){p.load(in);}p.setProperty("state","committing");try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}
            Files.createLink(base.resolve("workspace/a"),base.resolve("transfers/"+id+".part"));s=store(base);eq(s.status(id).state(),"finished");eq(s.stat("a",true).sha256(),hash(b));
        });
        test("rename publication recovers consumed staging name using persisted inode", () -> {
            Path base=fresh();FileTransferStore s=store(base);String id=id();byte[] b=bytes("abc");s.begin("a",id,3,hash(b));s.chunk(id,0,b,hash(b));
            Path part=base.resolve("transfers/"+id+".part"),m=base.resolve("transfers/"+id+".properties");Properties p=new Properties();try(InputStream in=Files.newInputStream(m)){p.load(in);}
            p.setProperty("state","committing");p.setProperty("commit_identity",Files.readAttributes(part,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey().toString());try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}
            Files.move(part,base.resolve("workspace/a"),StandardCopyOption.ATOMIC_MOVE);s=store(base);eq(s.status(id).state(),"finished");eq(s.finish(id).file().sha256(),hash(b));
        });
        test("same bytes at another inode cannot impersonate renamed publication", () -> {
            Path base=fresh();FileTransferStore s=store(base);String id=id();byte[] b=bytes("abc");s.begin("a",id,3,hash(b));s.chunk(id,0,b,hash(b));
            Path part=base.resolve("transfers/"+id+".part"),m=base.resolve("transfers/"+id+".properties");Properties p=new Properties();try(InputStream in=Files.newInputStream(m)){p.load(in);}
            p.setProperty("state","committing");p.setProperty("commit_identity",Files.readAttributes(part,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey().toString());try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}
            Files.write(base.resolve("workspace/a"),b);Files.delete(part);FileTransferStore resumed=store(base);refused("file_commit_unknown",()->resumed.status(id));
        });
        test("two pending destinations serialize publication without replacement", () -> {
            FileTransferStore s=store(fresh());String first=id(),second=id();byte[] a=bytes("first"),b=bytes("second");s.begin("a",first,a.length,hash(a));s.begin("a",second,b.length,hash(b));s.chunk(first,0,a,hash(a));s.chunk(second,0,b,hash(b));s.finish(first);refused("file_exists",()->s.finish(second));eq(s.stat("a",true).sha256(),hash(a));
        });
        test("stale receiving journal cannot mutate a published staging inode", () -> {
            Path base=fresh();FileTransferStore s=store(base);String id=id();byte[] b=bytes("abc");s.begin("a",id,6,hash(bytes("abcdef")));s.chunk(id,0,b,hash(b));
            Files.createLink(base.resolve("workspace/a"),base.resolve("transfers/"+id+".part"));refused("file_exists",()->s.chunk(id,3,bytes("def"),hash(bytes("def"))));eq(Files.readString(base.resolve("workspace/a")),"abc");
        });
        test("abort is idempotent and never deletes a finished file", () -> {
            FileTransferStore s=store(fresh());String id=id();s.begin("a",id,3,hash(bytes("abc")));eq(s.abort(id).state(),"aborted");eq(s.abort(id).state(),"aborted");
            var done=upload(s,"b",bytes("abc"));eq(s.abort(done.id()).state(),"finished");eq(s.stat("b",true).sha256(),hash(bytes("abc")));
        });
        test("expired staged data and receipts are removed", () -> {
            Path base=fresh();AtomicLong now=new AtomicLong(1000);FileTransferStore s=new FileTransferStore(base,()->{},now::get);String id=id();s.begin("a",id,3,hash(bytes("abc")));now.addAndGet(FileTransferStore.TTL+1);
            FileTransferStore recovered=new FileTransferStore(base,()->{},now::get);refused("file_transfer_not_found",()->recovered.status(id));eq(Files.exists(base.resolve("transfers/"+id+".part")),false);
        });
        test("cancellation fences reject chunk and final publication", () -> {
            Path base=fresh();AtomicBoolean active=new AtomicBoolean(true);Runnable fence=()->{if(!active.get())FileTransferStore.fail("permission_denied");};FileTransferStore s=new FileTransferStore(base,fence);String id=id();byte[] b=bytes("abc");s.begin("a",id,3,hash(b));
            active.set(false);refused("permission_denied",()->s.chunk(id,0,b,hash(b)));eq(Files.size(base.resolve("transfers/"+id+".part")),0L);
            active.set(true);s.chunk(id,0,b,hash(b));active.set(false);refused("permission_denied",()->s.finish(id));eq(Files.exists(base.resolve("workspace/a")),false);
        });
        test("cancel exactly before commit leaves no destination and can resume", () -> {
            Path base=fresh();AtomicBoolean armed=new AtomicBoolean(false);Runnable fence=()->{if(armed.get()){try(var files=Files.list(base.resolve("transfers"))){for(Path m:files.filter(p->p.toString().endsWith(".properties")).toList()){Properties p=new Properties();try(InputStream in=Files.newInputStream(m)){p.load(in);}if("committing".equals(p.getProperty("state")))FileTransferStore.fail("permission_denied");}}catch(IOException e){throw new RuntimeException(e);}}};
            FileTransferStore s=new FileTransferStore(base,fence);String id=id();byte[] b=bytes("abc");s.begin("a",id,3,hash(b));s.chunk(id,0,b,hash(b));armed.set(true);refused("permission_denied",()->s.finish(id));eq(Files.exists(base.resolve("workspace/a")),false);armed.set(false);eq(s.finish(id).state(),"finished");
        });
        test("traversal absolute paths control characters and unpaired surrogate rejected", () -> {
            for(String p:List.of("../x","/x","a//b","a/./b","a\\b","a:b","a\u0000b","\ud800"))refused("invalid_request",()->FileTransferStore.validPath(p,false));FileTransferStore.validPath("文档/😀.bin",false);
        });
        test("workspace and child symlinks cannot expose other files", () -> {
            Path base=fresh();FileTransferStore s=store(base);Path outside=Files.createTempFile("private-outside-", ".txt");Files.writeString(outside,"not shared");Files.createSymbolicLink(base.resolve("workspace/link"),outside);
            refused("file_symlink_refused",()->s.stat("link",true));refused("file_symlink_refused",()->s.read("link",0,10,"0".repeat(64)));
            Path other=fresh();Files.createDirectories(other);Files.createSymbolicLink(other.resolve("workspace"),outside.getParent());refused("file_scope_unavailable",()->store(other));
        });
        test("stale read version rejects replaced content", () -> {
            Path base=fresh();FileTransferStore s=store(base);upload(s,"a",bytes("abc"));String version=s.stat("a",true).version();Files.delete(base.resolve("workspace/a"));Files.writeString(base.resolve("workspace/a"),"longer");refused("file_changed",()->s.read("a",0,3,version));
        });
        test("finished receipt is historical; stat detects later deletion", () -> {Path base=fresh();FileTransferStore s=store(base);var done=upload(s,"a",bytes("abc"));Files.delete(base.resolve("workspace/a"));eq(s.status(done.id()),done);refused("file_not_found",()->s.stat("a",true));});
        test("parallel staging count bounded", () -> {FileTransferStore s=store(fresh());for(int i=0;i<4;i++)s.begin("a"+i,id(),1,hash(bytes("x")));refused("file_transfer_limit",()->s.begin("last",id(),1,hash(bytes("x"))));});
        test("streamed byte count must match provider metadata", () -> {refused("file_changed",()->FileTransferStore.digest(new ByteArrayInputStream(bytes("abc")),()->{},0));});
        System.out.println("TOTAL " + passed);
    }
}
