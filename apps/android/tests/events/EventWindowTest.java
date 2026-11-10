package io.airtap.preview;
import java.util.List;
public class EventWindowTest {
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        EventWindow w=new EventWindow();long now=100000000L;
        w.append(now,"notification","app","key","title","body");
        w.append(now,"notification","app","key","title","body");check(w.sequence==1);
        check(w.read("",0,16,now).entries().size()==1);
        check(w.read(w.epoch,0,16,now).entries().size()==1); // Repeated reads don't consume.
        for(int i=0;i<80;i++)w.append(now,"notification","app","key", "title",Integer.toString(i));
        check(w.entries.size()==64);check(w.read(w.epoch,1,16,now).gap());
        EventWindow restored=new EventWindow(w.epoch,w.sequence,List.copyOf(w.entries));
        check(restored.read(w.epoch,80,16,now).entries().size()==1);
        check(restored.read("a".repeat(32),80,16,now).gap());
        check(restored.read(w.epoch,0,16,now+EventWindow.RETENTION_MS+1).gap());
        check(restored.entries.isEmpty());
        boolean refused=false;try{w.read(w.epoch,1000,1,now);}catch(IllegalArgumentException e){refused=true;}check(refused);
        check(EventWindow.clip("x".repeat(255)+"😀").equals("x".repeat(255)));
        w.append(now,"collection_gap","android","","","disconnected");
        check(w.read(w.epoch,81,16,now).entries().get(0).kind().equals("collection_gap"));
        System.out.println("PASS event_dedup_replay_retention_epoch_gap_unicode");System.out.println("TOTAL 1");
    }
}
