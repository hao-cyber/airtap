package io.airtap.preview;

import java.util.concurrent.atomic.AtomicLong;

public final class FileCommandMetricsTest {
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        AtomicLong now=new AtomicLong(-10_000_000); FileCommandMetrics m=new FileCommandMetrics(now::get);
        m.check(()->now.addAndGet(2_000_000)); now.addAndGet(3_000_000);m.beginIo();
        m.check(()->now.addAndGet(4_000_000));now.addAndGet(5_000_000);
        var running=m.snapshot();check(running.elapsedMs()==14&&running.checkCount()==2&&running.checkMs()==6&&running.ioMs()==9&&!running.ioFinished());
        System.out.println("PASS queue/IO/check durations are separate and a running snapshot does not claim IO finished");
        RuntimeException failure=new RuntimeException("synthetic");
        try{m.check(()->{now.addAndGet(1_000_000);throw failure;});throw new AssertionError();}catch(RuntimeException seen){check(seen==failure);}
        m.endIo();now.addAndGet(2_000_000);var ended=m.snapshot();
        check(ended.elapsedMs()==17&&ended.checkCount()==3&&ended.checkMs()==7&&ended.ioMs()==10&&ended.ioFinished());
        System.out.println("PASS failed checks preserve the original refusal and finished IO does not include delivery queue time");
        System.out.println("TOTAL 2");
    }
}
