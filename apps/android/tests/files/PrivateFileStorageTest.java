package io.airtap.preview;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class PrivateFileStorageTest {
    static int passed;static final String A="a".repeat(32),B="b".repeat(32),ID="1".repeat(32);
    interface Test{void run()throws Exception;}
    static void test(String name,Test t)throws Exception{t.run();passed++;System.out.println("PASS "+name);}
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void refused(String code,Test t)throws Exception{try{t.run();throw new AssertionError("accepted");}catch(FileTransferStore.Failure f){check(f.code.equals(code));}}
    static Path fresh()throws Exception{return Files.createTempDirectory("airtap-storage-").resolve("scoped-files");}
    static PrivateFileStorage storage(Path root,long budget){return new PrivateFileStorage(root,()->{},p->{},budget);}
    static void file(Path p,int size)throws Exception{Files.createDirectories(p.getParent());try(RandomAccessFile f=new RandomAccessFile(p.toFile(),"rw")){f.setLength(size);}}
    static Properties record(Path m,String state,int size)throws Exception{Properties p=new Properties();p.setProperty("state",state);p.setProperty("size",String.valueOf(size));Files.createDirectories(m.getParent());try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}return p;}
    static void begin(Path root,String claim,String id,int size)throws Exception{new FileTransferStore(root.resolve(claim),()->{}).begin("file-"+id,id,size,FileTransferStore.digest(new byte[size]));}
    static long charged(PrivateFileStorage s)throws Exception{var u=s.usage(true);return u.usedBytes()+u.reservedBytes();}
    public static void main(String[]args)throws Exception{
        test("unpaired empty storage is inspectable and clearable",()->{var s=storage(fresh(),1048576);check(s.usage(false).usedBytes()==0);check(s.clearWorkspaces().workspaceBytes()==0);});
        test("all claims and SAF local bytes count while external files do not",()->{Path r=fresh();file(r.resolve(A+"/workspace/old"),100);file(r.resolve(B+"/workspace/new"),200);file(r.resolve(A+"/saf/staging/workspace/blob"),300);var u=storage(r,10000).usage(true);check(u.usedBytes()==600&&u.workspaceBytes()==300);});
        test("new transfer reservation blocks another claim before allocation",()->{Path r=fresh();var s=storage(r,200000);s.checkNew(100000,100000000);begin(r,A,ID,100000);refused("file_private_storage_budget",()->s.checkNew(100000,100000000));});
        test("partial chunk moves bytes from reserved to used without double count",()->{Path r=fresh();var s=storage(r,200000);begin(r,A,ID,10000);long before=charged(s);FileTransferStore f=new FileTransferStore(r.resolve(A),()->{});byte[] chunk=new byte[4096];s.check(0,100000000);f.chunk(ID,0,chunk,FileTransferStore.digest(chunk));check(charged(s)==before);});
        test("same ID begin does not reserve twice and zero byte still reserves journal",()->{Path r=fresh();var s=storage(r,200000);s.checkNew(0,100000000);begin(r,A,ID,0);long before=charged(s);begin(r,A,ID,0);check(charged(s)==before&&before==PrivateFileStorage.JOURNAL_RESERVE);});
        test("SAF outer receiving without inner journal reserves full bytes",()->{Path r=fresh();record(r.resolve(A+"/saf/publications/"+ID+".properties"),"receiving",10000);var s=storage(r,200000);check(charged(s)==10000+PrivateFileStorage.JOURNAL_RESERVE);});
        test("SAF outer and inner receiving are charged once",()->{Path r=fresh();record(r.resolve(A+"/saf/publications/"+ID+".properties"),"receiving",10000);FileTransferStore f=new FileTransferStore(r.resolve(A+"/saf/staging"),()->{});f.begin(ID,ID,10000,FileTransferStore.digest(new byte[10000]));var s=storage(r,200000);check(charged(s)==10000+PrivateFileStorage.JOURNAL_RESERVE);byte[] b=new byte[4096];f.chunk(ID,0,b,FileTransferStore.digest(b));check(charged(s)==10000+PrivateFileStorage.JOURNAL_RESERVE);});
        test("SAF sealed bytes count once while unfinished publication keeps journal reserve",()->{Path r=fresh();record(r.resolve(A+"/saf/publications/"+ID+".properties"),"creating",10000);record(r.resolve(A+"/saf/staging/transfers/"+ID+".properties"),"finished",10000);file(r.resolve(A+"/saf/staging/workspace/"+ID),10000);var u=storage(r,200000).usage(true);check(u.reservedBytes()>0&&u.usedBytes()+u.reservedBytes()==10000+PrivateFileStorage.JOURNAL_RESERVE);});
        test("old committed workspace and orphan staging constrain new upload",()->{Path r=fresh();file(r.resolve(A+"/workspace/finished"),100000);file(r.resolve(B+"/transfers/orphan.part"),50000);refused("file_private_storage_budget",()->storage(r,200000).checkNew(0,100000000));});
        test("physical free-space reserve includes all unfinished bytes",()->{Path r=fresh();begin(r,A,ID,10000);var s=storage(r,200000);refused("file_storage_full",()->s.check(0,PrivateFileStorage.FREE_RESERVE+10000));});
        test("clear all workspace claims preserves SAF and unrelated enrollment files",()->{Path r=fresh();file(r.resolve(A+"/workspace/a"),100);file(r.resolve(B+"/transfers/part"),200);Path saf=r.resolve(A+"/saf/publications/keep.properties");file(saf,300);Path enrollment=r.getParent().resolve("enrollment");file(enrollment,400);var after=storage(r,200000).clearWorkspaces();check(after.workspaceBytes()==0&&after.usedBytes()==300&&Files.size(saf)==300&&Files.size(enrollment)==400);});
        test("symlink preflight refuses before deleting safe files or target",()->{Path r=fresh();file(r.resolve(A+"/workspace/a"),100);Path out=Files.createTempFile("airtap-outside-",".txt");Files.writeString(out,"keep");Files.createSymbolicLink(r.resolve(A+"/workspace/link"),out);refused("file_symlink_refused",()->storage(r,200000).clearWorkspaces());check(Files.size(r.resolve(A+"/workspace/a"))==100&&Files.readString(out).equals("keep"));});
        test("malformed reservation fails closed but owner can clean workspace",()->{Path r=fresh();record(r.resolve(A+"/transfers/"+ID+".properties"),"receiving",-1);var s=storage(r,200000);refused("file_storage_failure",()->s.checkNew(1,100000000));check(s.ownerUsage().reservedBytes()==-1&&s.ownerUsage().workspaceBytes()>0);check(s.clearWorkspaces().workspaceBytes()==0);});
        test("cancelled cleanup can resume without touching preserved SAF records",()->{Path r=fresh();file(r.resolve(A+"/workspace/a"),100);file(r.resolve(A+"/workspace/b"),100);file(r.resolve(A+"/saf/keep"),100);AtomicBoolean cancel=new AtomicBoolean();var s=new PrivateFileStorage(r,()->{if(cancel.get())FileTransferStore.fail("permission_denied");},p->cancel.set(true),200000);refused("permission_denied",s::clearWorkspaces);check(Files.size(r.resolve(A+"/saf/keep"))==100);check(storage(r,200000).clearWorkspaces().workspaceBytes()==0);});
        test("old SAF payload reclaimed while current unexpired upload and all provider identities survive",()->{
            Path r=fresh();long now=System.currentTimeMillis();
            for(String claim:List.of(A,B)){
                Path m=r.resolve(claim+"/saf/publications/"+ID+".properties");Properties p=record(m,"writing",10000);p.setProperty("expires",Long.toString(now+10000));p.setProperty("reference","provider/exact-doc");try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}
                record(r.resolve(claim+"/saf/staging/transfers/"+ID+".properties"),"finished",10000);file(r.resolve(claim+"/saf/staging/workspace/"+ID),10000);
            }
            var s=storage(r,300000);long before=charged(s);s.reclaimSaf(B,now,true);
            check(!Files.exists(r.resolve(A+"/saf/staging/workspace/"+ID)));check(Files.size(r.resolve(B+"/saf/staging/workspace/"+ID))==10000);
            Properties kept=new Properties();try(InputStream in=Files.newInputStream(r.resolve(A+"/saf/publications/"+ID+".properties"))){kept.load(in);}
            check(kept.getProperty("reference").equals("provider/exact-doc")&&kept.getProperty("state").equals("writing")&&SafStagingCleanup.reclaimed(kept));check(charged(s)<before-10000);s.reclaimSaf(B,now,true);
        });
        test("all-claim expired reclamation releases byte and reservation budget before new upload",()->{
            Path r=fresh();long now=System.currentTimeMillis();Path m=r.resolve(A+"/saf/publications/"+ID+".properties");Properties p=record(m,"receiving",100000);p.setProperty("expires","1");try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}
            file(r.resolve(A+"/saf/staging/transfers/"+ID+".part"),90000);var s=storage(r,200000);refused("file_private_storage_budget",()->s.checkNew(100000,100000000));
            s.reclaimSaf(A,now,false);check(!Files.exists(r.resolve(A+"/saf/staging/transfers/"+ID+".part")));s.checkNew(100000,100000000);
        });
        test("orphan staged files have age fence for active claim and explicit old-claim recovery",()->{
            Path r=fresh();Path current=r.resolve(B+"/saf/staging/transfers/"+ID+".part"),old=r.resolve(A+"/saf/staging/transfers/"+ID+".part");file(current,100);file(old,100);
            var s=storage(r,1000000);s.reclaimSaf(B,System.currentTimeMillis(),false);check(Files.exists(current)&&Files.exists(old));s.reclaimSaf(B,System.currentTimeMillis(),true);check(Files.exists(current)&&!Files.exists(old));
            Files.setLastModifiedTime(current,java.nio.file.attribute.FileTime.fromMillis(1));s.reclaimSaf(B,System.currentTimeMillis(),false);check(!Files.exists(current));
        });
        test("SAF cleanup preflight refuses a payload symlink and preserves external target",()->{
            Path r=fresh();Path m=r.resolve(A+"/saf/publications/"+ID+".properties");Properties p=record(m,"creating",10);p.setProperty("expires","1");try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}
            Path external=Files.createTempFile("airtap-external-",".txt");Files.writeString(external,"keep");Path link=r.resolve(A+"/saf/staging/workspace/"+ID);Files.createDirectories(link.getParent());Files.createSymbolicLink(link,external);
            refused("file_symlink_refused",()->storage(r,1000000).reclaimSaf(B,System.currentTimeMillis(),true));check(Files.readString(external).equals("keep"));
        });
        test("cleanup interruption after tombstone before deletion remains safely retryable",()->{
            Path r=fresh();Path m=r.resolve(A+"/saf/publications/"+ID+".properties");Properties p=record(m,"creating",100);p.setProperty("expires","1");try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}Path blob=r.resolve(A+"/saf/staging/workspace/"+ID);file(blob,100);
            AtomicBoolean cancel=new AtomicBoolean();var s=new PrivateFileStorage(r,()->{if(cancel.get())FileTransferStore.fail("permission_denied");},dir->cancel.set(true));
            refused("permission_denied",()->s.reclaimSaf(B,System.currentTimeMillis(),true));check(Files.exists(blob));storage(r,1000000).reclaimSaf(B,System.currentTimeMillis(),true);check(!Files.exists(blob)&&Files.exists(m));
        });
        test("queued old-owner reclamation cannot retire the newly current claim",()->{
            Path r=fresh();long now=System.currentTimeMillis();Path m=r.resolve(B+"/saf/publications/"+ID+".properties");Properties p=record(m,"receiving",100);p.setProperty("expires",Long.toString(now+100000));try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}Path blob=r.resolve(B+"/saf/staging/workspace/"+ID);file(blob,100);
            var current=new AtomicReference<>(new FileMaintenanceFence.Identity(A,1,3));var guard=new FileMaintenanceFence(current.get(),current::get);
            var s=new PrivateFileStorage(r,guard,dir->{});current.set(new FileMaintenanceFence.Identity(B,1,3));
            refused("file_scope_changed",()->s.reclaimSaf(A,now,true));check(Files.size(blob)==100);Properties after=new Properties();try(InputStream in=Files.newInputStream(m)){after.load(in);}check(!SafStagingCleanup.reclaimed(after));
        });
        test("epoch change after retirement journal blocks payload deletion and preserves retry identity",()->{
            Path r=fresh();long now=System.currentTimeMillis();Path m=r.resolve(A+"/saf/publications/"+ID+".properties");Properties p=record(m,"creating",100);p.setProperty("expires","1");try(OutputStream out=Files.newOutputStream(m)){p.store(out,null);}Path blob=r.resolve(A+"/saf/staging/workspace/"+ID);file(blob,100);
            var current=new AtomicReference<>(new FileMaintenanceFence.Identity(B,1,3));var guard=new FileMaintenanceFence(current.get(),current::get);
            var s=new PrivateFileStorage(r,guard,dir->current.set(new FileMaintenanceFence.Identity(B,2,3)));
            refused("file_scope_changed",()->s.reclaimSaf(B,now,true));check(Files.size(blob)==100&&Files.exists(m));storage(r,1000000).reclaimSaf(B,now,true);check(!Files.exists(blob)&&Files.exists(m));
        });
        test("recipient read queues behind competing file command instead of failing valid share",()->{
            var lock=new java.util.concurrent.locks.ReentrantLock();var started=new java.util.concurrent.CountDownLatch(1);var result=new AtomicReference<Throwable>();var acquired=new AtomicBoolean();lock.lock();
            Thread reader=new Thread(()->{started.countDown();try{SharedFileIo.acquire(lock,()->{},true);try{acquired.set(true);}finally{lock.unlock();}}catch(Throwable e){result.set(e);}});reader.start();started.await();Thread.sleep(120);check(!acquired.get());lock.unlock();reader.join(2000);check(acquired.get()&&result.get()==null&&!reader.isAlive());
        });
        test("revoked recipient waiting for IO refuses before lock is released",()->{
            var lock=new java.util.concurrent.locks.ReentrantLock();var started=new java.util.concurrent.CountDownLatch(1);var result=new AtomicReference<Throwable>();var active=new AtomicBoolean(true);lock.lock();
            Thread reader=new Thread(()->{started.countDown();try{SharedFileIo.acquire(lock,()->{if(!active.get())FileTransferStore.fail("permission_denied");},true);lock.unlock();}catch(Throwable e){result.set(e);}});reader.start();started.await();Thread.sleep(60);active.set(false);reader.join(1000);check(!reader.isAlive()&&result.get() instanceof FileTransferStore.Failure&&lock.isHeldByCurrentThread());lock.unlock();
        });
        System.out.println("TOTAL "+passed);
    }
}
