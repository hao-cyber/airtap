package io.airtap.preview;
import android.content.Context;
import android.media.AudioManager;
import org.json.JSONObject;
import org.json.JSONArray;
final class AudioVolumeTest {
    interface Operation { void run() throws Exception; }
    static int count;
    static void require(boolean value) { if(!value)throw new AssertionError(); }
    static void test(String name,Operation op)throws Exception{op.run();count++;System.out.println("PASS "+name);}
    static void refuses(String code,Operation op)throws Exception{try{op.run();throw new AssertionError(code);}catch(ScopedUi.Refused error){require(code.equals(error.code));}}
    static JSONObject set(String stream,Object level){return RelayApi.json("id","test","action","set_volume","stream",stream,"level",level);}
    static JSONObject adjust(String stream,String direction){return RelayApi.json("id","test","action","adjust_volume","stream",stream,"direction",direction);}
    static JSONObject row(JSONObject inventory,int index){return (JSONObject)((JSONArray)inventory.get("streams")).values.get(index);}
    public static void main(String[] args)throws Exception {
        test("inventory reads actual supported ranges/mute/fixed and keeps unreadable streams unavailable",()->{
            var c=new Context();c.audio.muted[3]=true;c.audio.readUnavailable=0;
            var data=AudioVolume.describe(c);var music=row(data,0);require(((JSONObject)music.get("volume")).get("max").equals(15));require(((JSONObject)music.get("volume")).get("muted").equals(true));
            require(row(data,5).get("available").equals(false));c.audio.fixed=true;require(row(AudioVolume.describe(c),0).get("control").equals("fixed"));require(c.audio.writes==0);
        });
        test("explicit stream set returns actual values including linked ring/notification state without UI flags",()->{
            var c=new Context();var result=AudioVolume.perform(c,set("ring",4),()->{});require(c.audio.lastStream==2&&c.audio.lastFlags==0);
            require(result.get("target_observed").equals(true));require(((JSONObject)result.get("after")).get("current").equals(4));require(((JSONObject)row((JSONObject)result.get("audio_state"),3).get("volume")).get("current").equals(4));
        });
        test("mute/unmute/one-step use the selected stream and return the actual mute state",()->{
            var c=new Context();AudioVolume.perform(c,adjust("music","mute"),()->{});require(c.audio.muted[3]);AudioVolume.perform(c,adjust("music","unmute"),()->{});require(!c.audio.muted[3]);AudioVolume.perform(c,adjust("music","raise"),()->{});require(c.audio.current[3]==6);AudioVolume.perform(c,adjust("music","lower"),()->{});require(c.audio.current[3]==5&&c.audio.current[0]==3);
        });
        test("platform silently ignoring or capping requests is not reported as reached",()->{
            var c=new Context();c.audio.ignore=true;var result=AudioVolume.perform(c,set("music",14),()->{});require(result.get("accepted").equals(true));require(result.get("target_observed").equals(false));require(result.get("changed").equals(false));
        });
        test("concurrent opposite change at a limit cannot be mistaken for reaching that limit",()->{
            var c=new Context();c.audio.current[3]=15;c.audio.ignore=true;
            var result=AudioVolume.perform(c,adjust("music","raise"),()->{c.audio.current[3]=4;});require(result.get("target_observed").equals(false));
            c.audio.current[3]=0;result=AudioVolume.perform(c,adjust("music","lower"),()->{c.audio.current[3]=4;});require(result.get("target_observed").equals(false));
        });
        test("fixed/permission/DND restriction never becomes success",()->{
            var c=new Context();c.audio.fixed=true;refuses("volume_fixed",()->AudioVolume.perform(c,set("music",4),()->{}));c.audio.fixed=false;c.permission=false;refuses("audio_permission_required",()->AudioVolume.perform(c,set("music",4),()->{}));c.permission=true;c.audio.denied=true;refuses("audio_permission_or_policy_denied",()->AudioVolume.perform(c,set("music",4),()->{}));require(c.audio.writes==0);
        });
        test("range rejects invalid call minimum and music maximum instead of clamping",()->{
            var c=new Context();refuses("volume_out_of_range",()->AudioVolume.perform(c,set("voice_call",0),()->{}));refuses("volume_out_of_range",()->AudioVolume.perform(c,set("music",16),()->{}));require(c.audio.writes==0);
        });
        test("strict request shape never accepts ambiguous stream/type/extra settings",()->{
            for(Object raw:new Object[]{-1,0.5,true,"2",2147483648L})refuses("invalid_volume_level",()->AudioVolume.validate(set("music",raw)));
            refuses("invalid_audio_stream",()->AudioVolume.validate(set("active",2)));refuses("invalid_volume_direction",()->AudioVolume.validate(adjust("music","toggle")));refuses("invalid_request",()->AudioVolume.validate(set("music",2).put("flags",1)));
        });
        test("cancel/revocation/lock/owner guard is consulted at dispatch and can stop the write",()->{
            var c=new Context();for(String error:new String[]{"permission_denied","device_locked","protected_airtap_window"})refuses(error,()->AudioVolume.perform(c,set("music",3),()->{throw new ScopedUi.Refused(error);}));require(c.audio.writes==0);
        });
        test("lost readback or runtime uncertainty after dispatch never reports verified or invites replay",()->{
            var c=new Context();c.audio.readFailAfterWrite=true;var result=AudioVolume.perform(c,set("music",4),()->{});require(result.get("verification").equals("readback_unavailable")&&result.get("retry_action").equals(false));require(!result.has("target_observed")&&c.audio.writes==1);
            var d=new Context();d.audio.unknown=true;refuses("audio_action_outcome_unknown",()->AudioVolume.perform(d,set("music",4),()->{}));require(d.audio.writes==1);
        });
        System.out.println("TOTAL "+count);
    }
}
