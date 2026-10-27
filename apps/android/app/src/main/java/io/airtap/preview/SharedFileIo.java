package io.airtap.preview;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Recipient IO may queue behind an ordinary command, but never waits without checking its grant. */
final class SharedFileIo {
    static void acquire(ReentrantLock lock,Runnable fence,boolean mayWait)throws IOException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {
            fence.run();
            try{
                if(mayWait?lock.tryLock(50,TimeUnit.MILLISECONDS):lock.tryLock()){
                    try{fence.run();return;}catch(RuntimeException stopped){lock.unlock();throw stopped;}
                }
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("file_interrupted");}
        }while(mayWait&&System.nanoTime()<end);
        throw new IOException("file_busy");
    }
}
