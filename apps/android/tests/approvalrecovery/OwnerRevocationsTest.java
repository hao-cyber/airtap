package io.airtap.preview;

import android.content.Context;
import java.util.Set;
import org.json.JSONObject;

/** Real durable revoke helper with synthetic preferences and delayed HTTP responses. */
public final class OwnerRevocationsTest {
    private static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static Context context;
    private static EnrollmentStore.Config config;
    private static int passed;
    interface Checked { void run() throws Exception; }
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void resetRuntime() throws Exception {
        var field=OwnerRevocations.class.getDeclaredField("inFlight");field.setAccessible(true);field.set(null,null);
        RelayApi.requests.clear();RelayApi.failStart=false;EnrollmentStore.failRead=false;context=new Context();
    }
    private static void reset() throws Exception {FakeDisk.reset();resetRuntime();config=new EnrollmentStore.Config("https://relay.invalid/d/11111111111111111111111111111111","synthetic-secret-A");FakeDisk.config=config;}
    private static void reboot() throws Exception {FakeDisk.reboot();resetRuntime();}
    private static void test(String name,Checked body) throws Exception {reset();body.run();passed++;System.out.println("PASS "+name);}
    private static void failure(Checked body) throws Exception {try{body.run();throw new AssertionError("failure expected");}catch(IllegalStateException|IllegalArgumentException expected){}}
    private static JSONObject ok(){return RelayApi.json("protocol_version",4,"ok",true);}
    private static JSONObject multi(long generation){return RelayApi.json("protocol_version",4,"mode","multi","generation",generation);}
    private static RelayApi.Request request(int index){return RelayApi.requests.get(index);}
    private static Set<String> pending() throws Exception {return OwnerRevocations.claimsPending(context,config);}
    public static void main(String[] args) throws Exception {
        test("single revoke is durable before any HTTP and stores no device token",()->{
            OwnerRevocations.queue(context,config,A);check(pending().equals(Set.of(A))&&RelayApi.requests.isEmpty());
            for(var entry:FakeDisk.preferences.disk.entrySet()){check(entry.getKey().matches("[a-f0-9]{64}"));check(!entry.getValue().toString().contains(config.token)&&!entry.getValue().toString().contains(config.url));}
            reboot();check(pending().equals(Set.of(A))&&!OwnerRevocations.allPending(context,config));
        });
        test("same target queue is idempotent and all stop supersedes individual targets",()->{
            OwnerRevocations.queue(context,config,A);var original=new java.util.HashMap<>(FakeDisk.preferences.disk);
            OwnerRevocations.queue(context,config,A);check(original.equals(FakeDisk.preferences.disk));OwnerRevocations.queue(context,config,B);
            check(pending().equals(Set.of(A,B)));OwnerRevocations.queue(context,config,null);check(OwnerRevocations.allPending(context,config)&&pending().isEmpty());
            original=new java.util.HashMap<>(FakeDisk.preferences.disk);OwnerRevocations.queue(context,config,A);OwnerRevocations.queue(context,config,null);
            check(original.equals(FakeDisk.preferences.disk));reboot();check(OwnerRevocations.allPending(context,config));
        });
        test("unknown HTTP result keeps the target and retry does not invent a new intent",()->{
            OwnerRevocations.queue(context,config,A);var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);final int[] done={0};
            OwnerRevocations.flush(context,config,()->done[0]++);request(0).respond(null,0,"unknown");check(done[0]==1&&pending().equals(Set.of(A)));
            OwnerRevocations.flush(context,config,()->done[0]++);check(request(0).path.equals(request(1).path)&&A.equals(request(1).body.getString("claim_id")));
            check(saved.equals(FakeDisk.preferences.disk));request(1).respond(ok(),200,null);check(pending().isEmpty()&&done[0]==2);
        });
        test("flush dispatches one target at a time and empty or in-flight calls do not send",()->{
            final int[] done={0};OwnerRevocations.flush(context,config,()->done[0]++);check(done[0]==1&&RelayApi.requests.isEmpty());
            OwnerRevocations.queue(context,config,A);OwnerRevocations.queue(context,config,B);OwnerRevocations.flush(context,config,()->done[0]++);
            OwnerRevocations.flush(context,config,()->done[0]++);check(RelayApi.requests.size()==1&&done[0]==2);
            request(0).respond(ok(),200,null);check(pending().size()==1&&RelayApi.requests.size()==1);
            OwnerRevocations.flush(context,config,null);request(1).respond(ok(),200,null);check(pending().isEmpty());
        });
        test("no network callback retains durable all stop through process restart",()->{
            OwnerRevocations.queue(context,config,null);OwnerRevocations.flush(context,config,null);check(OwnerRevocations.allPending(context,config));
            check("/v1/device/revoke".equals(request(0).path)&&request(0).body==null);reboot();check(OwnerRevocations.allPending(context,config));
            OwnerRevocations.flush(context,config,null);check(RelayApi.requests.size()==1);request(0).respond(ok(),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("success must be HTTP 200 with strict v4 and boolean ok",()->{
            OwnerRevocations.queue(context,config,A);
            JSONObject[] malformed={null,RelayApi.json("protocol_version",3,"ok",true),RelayApi.json("protocol_version",4,"ok","true"),RelayApi.json("protocol_version",4,"ok",false),RelayApi.json("protocol_version",4)};
            for(JSONObject body:malformed){OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(body,200,null);check(pending().equals(Set.of(A)));}
            OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(ok(),500,null);check(pending().equals(Set.of(A)));
            OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(ok(),200,"transport failure");check(pending().equals(Set.of(A)));
        });
        test("all stop supersedes in-flight single revoke and old single success cannot clear it",()->{
            OwnerRevocations.queue(context,config,A);OwnerRevocations.flush(context,config,null);OwnerRevocations.queue(context,config,null);
            request(0).respond(ok(),200,null);check(OwnerRevocations.allPending(context,config));OwnerRevocations.flush(context,config,null);
            check("/v1/device/revoke".equals(request(1).path));request(1).respond(ok(),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("late old all response cannot confirm a new all intent",()->{
            OwnerRevocations.queue(context,config,null);OwnerRevocations.flush(context,config,null);
            OwnerRevocations.confirmed(context,config,null);OwnerRevocations.queue(context,config,null);
            request(0).respond(ok(),200,null);check(OwnerRevocations.allPending(context,config));
            OwnerRevocations.flush(context,config,null);request(1).respond(ok(),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("duplicate response cannot clear a later retry or call completion twice",()->{
            OwnerRevocations.queue(context,config,A);final int[] done={0};OwnerRevocations.flush(context,config,()->done[0]++);
            request(0).respond(null,0,"unknown");OwnerRevocations.flush(context,config,()->done[0]++);request(0).respond(ok(),200,null);
            check(pending().equals(Set.of(A))&&done[0]==1);request(1).respond(ok(),200,null);request(1).respond(ok(),200,null);check(done[0]==2&&pending().isEmpty());
        });
        test("device identity changes isolate targets and late callbacks",()->{
            EnrollmentStore.Config old=config;OwnerRevocations.queue(context,old,A);OwnerRevocations.flush(context,old,null);
            config=new EnrollmentStore.Config(old.url,"synthetic-secret-B");FakeDisk.config=config;
            check(pending().isEmpty()&&!OwnerRevocations.allPending(context,config));OwnerRevocations.queue(context,config,B);
            request(0).respond(ok(),200,null);check(OwnerRevocations.claimsPending(context,old).equals(Set.of(A))&&pending().equals(Set.of(B)));
            OwnerRevocations.flush(context,config,null);check(request(1).token.equals(config.token)&&B.equals(request(1).body.getString("claim_id")));
            request(1).respond(ok(),200,null);check(pending().isEmpty()&&OwnerRevocations.claimsPending(context,old).equals(Set.of(A)));
        });
        test("stale enrollment cannot start network requests",()->{
            OwnerRevocations.queue(context,config,A);FakeDisk.config=new EnrollmentStore.Config("https://other.invalid/d/22222222222222222222222222222222",config.token);
            failure(()->OwnerRevocations.flush(context,config,null));check(RelayApi.requests.isEmpty()&&pending().equals(Set.of(A)));
        });
        test("confirmed only clears the matching target",()->{
            OwnerRevocations.queue(context,config,A);OwnerRevocations.queue(context,config,B);OwnerRevocations.confirmed(context,config,null);check(pending().equals(Set.of(A,B)));
            OwnerRevocations.confirmed(context,config,A);check(pending().equals(Set.of(B)));OwnerRevocations.queue(context,config,null);
            OwnerRevocations.confirmed(context,config,B);check(OwnerRevocations.allPending(context,config));OwnerRevocations.confirmed(context,config,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("queue commit failure is reported and preserves prior pending targets",()->{
            OwnerRevocations.queue(context,config,A);FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;
            failure(()->OwnerRevocations.queue(context,config,null));check(pending().equals(Set.of(A))&&!OwnerRevocations.allPending(context,config));
            reboot();check(pending().equals(Set.of(A)));OwnerRevocations.queue(context,config,null);reboot();check(OwnerRevocations.allPending(context,config));
        });
        test("failed clearing commit keeps all-stop barrier visible and durable",()->{
            OwnerRevocations.queue(context,config,null);OwnerRevocations.flush(context,config,null);FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;
            request(0).respond(ok(),200,null);check(OwnerRevocations.allPending(context,config));reboot();check(OwnerRevocations.allPending(context,config));
            OwnerRevocations.flush(context,config,null);request(0).respond(ok(),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("request-start exception releases in-flight state but retains durable target",()->{
            OwnerRevocations.queue(context,config,A);RelayApi.failStart=true;final int[] done={0};failure(()->OwnerRevocations.flush(context,config,()->done[0]++));
            check(done[0]==1&&pending().equals(Set.of(A)));RelayApi.failStart=false;OwnerRevocations.flush(context,config,null);request(0).respond(ok(),200,null);check(pending().isEmpty());
        });
        test("unreadable enrollment response cannot clear target and later retry remains possible",()->{
            OwnerRevocations.queue(context,config,A);OwnerRevocations.flush(context,config,null);EnrollmentStore.failRead=true;request(0).respond(ok(),200,null);
            check(pending().equals(Set.of(A)));EnrollmentStore.failRead=false;OwnerRevocations.flush(context,config,null);request(1).respond(ok(),200,null);check(pending().isEmpty());
        });
        test("invalid claim and corrupted stored intent fail closed before network",()->{
            failure(()->OwnerRevocations.queue(context,config,"invalid"));OwnerRevocations.queue(context,config,A);
            String key=FakeDisk.preferences.disk.keySet().iterator().next();FakeDisk.preferences.edit().putString(key,"future|unknown").commit();
            failure(()->OwnerRevocations.allPending(context,config));failure(()->OwnerRevocations.flush(context,config,null));check(RelayApi.requests.isEmpty());
        });
        test("multi all stop persists exact zero generation before sending its only request field",()->{
            OwnerRevocations.queue(context,config,A);OwnerRevocations.queueAll(context,config,0);
            check(OwnerRevocations.allPending(context,config)&&pending().isEmpty()&&RelayApi.requests.isEmpty());reboot();
            OwnerRevocations.flush(context,config,null);check("/v1/device/revoke".equals(request(0).path));
            check(request(0).body.fields.size()==1&&ServiceProtocol.integer(request(0).body,"expected_generation")==0);
            request(0).respond(ok().put("generation",1),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("multi all retries preserve generation across unknown response and process restart",()->{
            OwnerRevocations.queueAll(context,config,12);var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);OwnerRevocations.flush(context,config,null);
            request(0).respond(null,0,"unknown");check(OwnerRevocations.allPending(context,config));reboot();
            OwnerRevocations.queueAll(context,config,12);check(saved.equals(FakeDisk.preferences.disk));
            failure(()->OwnerRevocations.queueAll(context,config,18));failure(()->OwnerRevocations.queue(context,config,null));
            OwnerRevocations.flush(context,config,null);check(ServiceProtocol.integer(request(0).body,"expected_generation")==12);
            request(0).respond(ok().put("generation",13),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("multi confirmation requires strict valid integer generation greater than its expected generation",()->{
            OwnerRevocations.queueAll(context,config,8);
            Object[] invalid={null,-1L,0L,7L,8L,9.0,"9",true,9007199254740992L};
            for(Object generation:invalid){
                OwnerRevocations.flush(context,config,null);JSONObject response=ok();if(generation!=null)response.put("generation",generation);
                request(RelayApi.requests.size()-1).respond(response,200,null);check(OwnerRevocations.allPending(context,config));
            }
            OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(ok().put("generation",9),409,null);
            check(OwnerRevocations.allPending(context,config));failure(()->OwnerRevocations.confirmed(context,config,null));
            OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(ok().put("generation",9),200,null);
            check(!OwnerRevocations.allPending(context,config));
        });
        test("older no-op acknowledgment completes only old intent and never changes later local grants",()->{
            FakeDisk.record=RelayApi.json("later_grant",B,"generation",25);var grants=new java.util.HashMap<>(FakeDisk.record.fields);
            OwnerRevocations.queueAll(context,config,20);final int[] done={0};OwnerRevocations.flush(context,config,()->done[0]++);
            request(0).respond(ok().put("generation",25),200,null);
            check(!OwnerRevocations.allPending(context,config)&&grants.equals(FakeDisk.record.fields)&&done[0]==1);
        });
        test("late successful old generation callback cannot clear a newer all-stop intent",()->{
            OwnerRevocations.queueAll(context,config,2);OwnerRevocations.flush(context,config,null);request(0).respond(ok().put("generation",3),200,null);
            OwnerRevocations.queueAll(context,config,3);OwnerRevocations.flush(context,config,null);request(0).respond(ok().put("generation",100),200,null);
            check(OwnerRevocations.allPending(context,config));request(1).respond(ok().put("generation",3),200,null);check(OwnerRevocations.allPending(context,config));
            OwnerRevocations.flush(context,config,null);request(2).respond(ok().put("generation",4),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("failed multi clear keeps barrier and exact generation for durable retry",()->{
            OwnerRevocations.queueAll(context,config,41);OwnerRevocations.flush(context,config,null);FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;
            request(0).respond(ok().put("generation",42),200,null);check(OwnerRevocations.allPending(context,config));reboot();
            check(OwnerRevocations.allPending(context,config));OwnerRevocations.flush(context,config,null);
            check(ServiceProtocol.integer(request(0).body,"expected_generation")==41);request(0).respond(ok().put("generation",42),200,null);
            check(!OwnerRevocations.allPending(context,config));
        });
        test("failed generation intent commit retains old target and does not send HTTP",()->{
            OwnerRevocations.queue(context,config,A);FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;
            failure(()->OwnerRevocations.queueAll(context,config,9));check(pending().equals(Set.of(A))&&!OwnerRevocations.allPending(context,config));
            reboot();check(pending().equals(Set.of(A))&&RelayApi.requests.isEmpty());
        });
        test("single legacy persisted all intent remains bodyless and cannot silently become multi",()->{
            OwnerRevocations.queue(context,config,null);String key=FakeDisk.preferences.disk.keySet().iterator().next();
            String value=FakeDisk.preferences.getString(key,"");FakeDisk.preferences.edit().putString(key,"v1|"+value.substring(3)).commit();reboot();
            failure(()->OwnerRevocations.queueAll(context,config,2));check(OwnerRevocations.allPending(context,config));
            OwnerRevocations.flush(context,config,null);check(request(0).body==null);request(0).respond(ok(),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("invalid requested or persisted generation cannot create or dispatch a revoke",()->{
            failure(()->OwnerRevocations.queueAll(context,config,-1));failure(()->OwnerRevocations.queueAll(context,config,9007199254740992L));
            check(!OwnerRevocations.allPending(context,config));OwnerRevocations.queueAll(context,config,0);
            String key=FakeDisk.preferences.disk.keySet().iterator().next();
            for(String value:new String[]{"v2|*:"+A+":01","v2|*:"+A+":-1","v2|*:"+A+":9007199254740992","v1|*:"+A+":1","v2|"+A+":"+B+":1"}){
                FakeDisk.preferences.edit().putString(key,value).commit();failure(()->OwnerRevocations.flush(context,config,null));
            }
            check(RelayApi.requests.isEmpty());
        });
        test("unknown multi all stop is durable offline and supersedes single targets without storing a token",()->{
            OwnerRevocations.queue(context,config,A);OwnerRevocations.queueAllUnknown(context,config);
            check(OwnerRevocations.allPending(context,config)&&pending().isEmpty()&&RelayApi.requests.isEmpty());
            var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);OwnerRevocations.queueAllUnknown(context,config);check(saved.equals(FakeDisk.preferences.disk));
            for(Object value:FakeDisk.preferences.disk.values())check(!value.toString().contains(config.token));
            failure(()->OwnerRevocations.confirmed(context,config,null));failure(()->OwnerRevocations.queue(context,config,null));
            reboot();check(OwnerRevocations.allPending(context,config));OwnerRevocations.flush(context,config,null);
            check("GET".equals(request(0).method)&&"/v1/device/grants".equals(request(0).path)&&request(0).body==null);
        });
        test("unknown GET timeout or absent callback preserves barrier and exact unresolved intent through restart",()->{
            OwnerRevocations.queueAllUnknown(context,config);var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);final int[] done={0};
            OwnerRevocations.flush(context,config,()->done[0]++);request(0).respond(null,0,"timeout");
            check(done[0]==1&&OwnerRevocations.allPending(context,config)&&saved.equals(FakeDisk.preferences.disk));
            OwnerRevocations.flush(context,config,()->done[0]++);check("GET".equals(request(1).method));
            OwnerRevocations.flush(context,config,null);check(RelayApi.requests.size()==2);reboot();
            check(OwnerRevocations.allPending(context,config)&&saved.equals(FakeDisk.preferences.disk));OwnerRevocations.flush(context,config,null);
            check("GET".equals(request(0).method)&&request(0).body==null);
        });
        test("unknown GET fixes generation durably before a later flush sends its exact POST",()->{
            OwnerRevocations.queueAllUnknown(context,config);String before=FakeDisk.preferences.disk.values().iterator().next().toString();final int[] done={0};
            OwnerRevocations.flush(context,config,()->done[0]++);request(0).respond(multi(0),200,null);
            check(done[0]==1&&RelayApi.requests.size()==1&&OwnerRevocations.allPending(context,config));
            String after=FakeDisk.preferences.disk.values().iterator().next().toString();check(before.substring(0,before.lastIndexOf(':')).equals(after.substring(0,after.lastIndexOf(':'))));
            reboot();OwnerRevocations.flush(context,config,null);check("POST".equals(request(0).method)&&"/v1/device/revoke".equals(request(0).path));
            check(request(0).body.fields.size()==1&&ServiceProtocol.integer(request(0).body,"expected_generation")==0);
            request(0).respond(ok().put("generation",1),200,null);check(!OwnerRevocations.allPending(context,config));
        });
        test("generation discovery requires strict protocol multi mode and safe integer generation",()->{
            OwnerRevocations.queueAllUnknown(context,config);var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);
            JSONObject[] invalid={null,multi(1).put("protocol_version",3),multi(1).put("protocol_version","4"),multi(1).put("mode","single"),multi(1).put("mode",true),RelayApi.json("protocol_version",4,"generation",1),multi(-1),multi(1).put("generation",1.0),multi(1).put("generation","1"),multi(9007199254740992L)};
            for(JSONObject response:invalid){OwnerRevocations.flush(context,config,null);RelayApi.Request sent=request(RelayApi.requests.size()-1);check("GET".equals(sent.method));sent.respond(response,200,null);check(saved.equals(FakeDisk.preferences.disk)&&OwnerRevocations.allPending(context,config));}
            OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(multi(1),503,null);check(saved.equals(FakeDisk.preferences.disk));
            OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(multi(1),200,"unknown");check(saved.equals(FakeDisk.preferences.disk));
            OwnerRevocations.flush(context,config,null);request(RelayApi.requests.size()-1).respond(multi(9007199254740991L),200,null);
            OwnerRevocations.flush(context,config,null);check(ServiceProtocol.integer(request(RelayApi.requests.size()-1).body,"expected_generation")==9007199254740991L);
        });
        test("failed GET generation commit restores unknown barrier and never sends unfixed revoke",()->{
            OwnerRevocations.queueAllUnknown(context,config);var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);OwnerRevocations.flush(context,config,null);
            FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;request(0).respond(multi(7),200,null);
            check(OwnerRevocations.allPending(context,config)&&saved.equals(FakeDisk.preferences.disk)&&saved.equals(FakeDisk.preferences.memory)&&RelayApi.requests.size()==1);
            reboot();OwnerRevocations.flush(context,config,null);check("GET".equals(request(0).method));request(0).respond(multi(8),200,null);
            OwnerRevocations.flush(context,config,null);check("POST".equals(request(1).method)&&ServiceProtocol.integer(request(1).body,"expected_generation")==8);
        });
        test("unreadable enrollment during GET retains unknown intent until identity can be checked",()->{
            OwnerRevocations.queueAllUnknown(context,config);var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);OwnerRevocations.flush(context,config,null);
            EnrollmentStore.failRead=true;request(0).respond(multi(6),200,null);check(saved.equals(FakeDisk.preferences.disk)&&OwnerRevocations.allPending(context,config));
            failure(()->OwnerRevocations.flush(context,config,null));check(RelayApi.requests.size()==1);EnrollmentStore.failRead=false;
            OwnerRevocations.flush(context,config,null);check("GET".equals(request(1).method));request(1).respond(multi(6),200,null);
            OwnerRevocations.flush(context,config,null);check(ServiceProtocol.integer(request(2).body,"expected_generation")==6);
        });
        test("late GET for old enrollment cannot fix either old or new device intent",()->{
            EnrollmentStore.Config old=config;OwnerRevocations.queueAllUnknown(context,old);OwnerRevocations.flush(context,old,null);
            config=new EnrollmentStore.Config(old.url,"synthetic-secret-C");FakeDisk.config=config;OwnerRevocations.queueAllUnknown(context,config);
            var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);request(0).respond(multi(5),200,null);
            check(saved.equals(FakeDisk.preferences.disk)&&OwnerRevocations.allPending(context,old)&&OwnerRevocations.allPending(context,config));
            OwnerRevocations.flush(context,config,null);check(request(1).token.equals(config.token)&&"GET".equals(request(1).method));request(1).respond(multi(9),200,null);
            OwnerRevocations.flush(context,config,null);check(ServiceProtocol.integer(request(2).body,"expected_generation")==9);request(2).respond(ok().put("generation",10),200,null);
            check(!OwnerRevocations.allPending(context,config)&&OwnerRevocations.allPending(context,old));FakeDisk.config=old;OwnerRevocations.flush(context,old,null);check("GET".equals(request(3).method));
        });
        test("duplicate or abandoned GET cannot replace generation fixed by current attempt",()->{
            OwnerRevocations.queueAllUnknown(context,config);final int[] done={0};OwnerRevocations.flush(context,config,()->done[0]++);RelayApi.Request old=request(0);
            reboot();OwnerRevocations.flush(context,config,null);old.respond(multi(99),200,null);check(OwnerRevocations.allPending(context,config));
            request(0).respond(multi(4),200,null);request(0).respond(multi(100),200,null);old.respond(multi(101),200,null);
            OwnerRevocations.flush(context,config,null);check(ServiceProtocol.integer(request(1).body,"expected_generation")==4&&done[0]==1);
        });
        test("unknown requeue preserves already fixed generation and cannot downgrade to a new GET",()->{
            OwnerRevocations.queueAllUnknown(context,config);OwnerRevocations.flush(context,config,null);request(0).respond(multi(11),200,null);
            var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);OwnerRevocations.queueAllUnknown(context,config);check(saved.equals(FakeDisk.preferences.disk));
            OwnerRevocations.queueAll(context,config,11);failure(()->OwnerRevocations.queueAll(context,config,12));
            OwnerRevocations.flush(context,config,null);check("POST".equals(request(1).method)&&ServiceProtocol.integer(request(1).body,"expected_generation")==11);
        });
        test("unknown intent fixed once protects later grant when successful all-revoke response was lost",()->{
            OwnerRevocations.queueAllUnknown(context,config);OwnerRevocations.flush(context,config,null);request(0).respond(multi(5),200,null);
            OwnerRevocations.flush(context,config,null);check(ServiceProtocol.integer(request(1).body,"expected_generation")==5);
            // Synthetic relay applied generation 5, advanced to 6, and later received a new grant.
            long remoteGeneration=6;Set<String> remoteGrants=new java.util.HashSet<>(Set.of(B));
            request(1).respond(null,0,"response lost");FakeDisk.record=RelayApi.json("later_grant",B,"generation",remoteGeneration);var local=new java.util.HashMap<>(FakeDisk.record.fields);
            reboot();OwnerRevocations.queueAllUnknown(context,config);OwnerRevocations.flush(context,config,null);RelayApi.Request retry=request(0);
            check("POST".equals(retry.method)&&ServiceProtocol.integer(retry.body,"expected_generation")==5);
            long expected=ServiceProtocol.integer(retry.body,"expected_generation");if(expected==remoteGeneration){remoteGrants.clear();remoteGeneration++;}
            retry.respond(ok().put("generation",remoteGeneration),200,null);
            check(remoteGrants.equals(Set.of(B))&&local.equals(FakeDisk.record.fields)&&!OwnerRevocations.allPending(context,config));
        });
        test("unknown queue write failure preserves existing targets and legacy all intent cannot silently become multi",()->{
            OwnerRevocations.queue(context,config,A);FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;failure(()->OwnerRevocations.queueAllUnknown(context,config));
            check(pending().equals(Set.of(A))&&!OwnerRevocations.allPending(context,config));reboot();check(pending().equals(Set.of(A)));
            OwnerRevocations.queue(context,config,null);var saved=new java.util.HashMap<>(FakeDisk.preferences.disk);failure(()->OwnerRevocations.queueAllUnknown(context,config));check(saved.equals(FakeDisk.preferences.disk));
            OwnerRevocations.flush(context,config,null);check("POST".equals(request(0).method)&&request(0).body==null);
        });
        System.out.println("TOTAL "+passed);
    }
}
