package io.airtap.preview;

import java.util.concurrent.atomic.AtomicLong;

public final class CommandTimelineTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        AtomicLong now = new AtomicLong(-10_000_000);
        CommandTimeline timelines = new CommandTimeline(now::get);
        var first = timelines.begin("owner-a", "file_upload_chunk", 41, -15_000_000, -13_000_000);
        first.preparing(); now.addAndGet(4_000_000); first.executing(); now.addAndGet(6_000_000);
        first.finish(CommandTimeline.Phase.reply_enqueued); now.addAndGet(20_000_000);
        var s = timelines.snapshot("owner-a");
        check(s.sequence() == 41 && s.elapsedMs() == 15 && s.mainQueueMs() == 2
                && s.admissionMs() == 3 && s.preparationMs() == 4 && s.executionReplyMs() == 6);
        check(s.phase() == CommandTimeline.Phase.reply_enqueued);
        System.out.println("PASS independent queue/admission/preparation/execution durations freeze at response enqueue");
        var newer = timelines.begin("owner-a", "file_upload_chunk", 42, now.get(), now.get());
        first.finish(CommandTimeline.Phase.authority_lost);
        check(timelines.snapshot("owner-a").sequence() == 42 && timelines.snapshot("owner-b") == null);
        timelines.begin("owner-b", "file_stat", 7, now.get(), now.get()).finish(CommandTimeline.Phase.reply_rejected);
        now.addAndGet(8_000_000); timelines.interrupt(); now.addAndGet(10_000_000);
        check(timelines.snapshot("owner-a").phase() == CommandTimeline.Phase.interrupted
                && timelines.snapshot("owner-a").elapsedMs() == 8
                && timelines.snapshot("owner-b").phase() == CommandTimeline.Phase.reply_rejected);
        newer.executing(); check(timelines.snapshot("owner-a").phase() == CommandTimeline.Phase.interrupted);
        System.out.println("PASS late callbacks cannot replace a newer sequence or change terminal outcomes across owners");
        for (int i=0; i<8; i++) timelines.begin("other-"+i,"file_stat",i,now.get(),now.get());
        check(timelines.snapshot("owner-a") == null && timelines.snapshot("owner-b") == null
                && timelines.snapshot("other-0") != null && timelines.snapshot("other-7") != null);
        System.out.println("PASS duration records are bounded to eight controller scopes");
        System.out.println("TOTAL 3");
    }
}
