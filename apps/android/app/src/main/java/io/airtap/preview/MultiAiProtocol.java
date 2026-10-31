package io.airtap.preview;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Strict transport identities. Remote listings never create local owner consent. */
final class MultiAiProtocol {
    record Envelope(String claim, String lease, Long epoch, JSONObject command) {}
    private MultiAiProtocol() {}
    static void exact(JSONObject value, String... names) {
        if (value.length() != names.length) throw new IllegalArgumentException("invalid_multi_shape");
        for (String name : names) if (!value.has(name)) throw new IllegalArgumentException("invalid_multi_shape");
    }
    static String hex(JSONObject value, String key) throws Exception {
        String result = value.getString(key); if (!result.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("invalid_multi_identity"); return result;
    }
    static long integer(JSONObject value, String key, boolean zero) {
        long n = ServiceProtocol.integer(value,key); if (n < (zero ? 0 : 1) || n > TaskLease.MAX_INTEGER) throw new IllegalArgumentException("invalid_multi_integer"); return n;
    }
    static JSONObject hello() { return RelayApi.json("type","hello","protocol_version",4,"capabilities",new JSONArray().put("multi_ai_v1").put("task_lease_v1")); }
    static void migrationDiscovery(JSONObject value) throws Exception {
        ServiceProtocol.discovery(value);
        JSONArray capabilities=value.getJSONArray("capabilities");
        if(capabilities.length()>64)throw new IllegalArgumentException();
        Set<String> names=new HashSet<>();
        for(int i=0;i<capabilities.length();i++){
            Object capability=capabilities.get(i);
            if(!(capability instanceof String)||!names.add((String)capability))throw new IllegalArgumentException();
        }
        if(!names.containsAll(Set.of("multi_ai_v1","task_lease_v1")))throw new IllegalArgumentException();
    }
    static void helloAck(JSONObject value) throws Exception {
        exact(value,"type","protocol_version","mode","connection_id"); ServiceProtocol.version(value);
        if (!"hello_ack".equals(value.getString("type")) || !"multi".equals(value.getString("mode"))) throw new IllegalArgumentException(); hex(value,"connection_id");
    }
    static TaskLease.Fence fence(JSONObject value) throws Exception {
        exact(value,"type","operation_id","lease_epoch","holder","ttl_ms","renewal_sequence");
        if (!"task_fence".equals(value.getString("type"))) throw new IllegalArgumentException();
        TaskLease.Holder holder = null;
        if (!value.isNull("holder")) { JSONObject h=value.getJSONObject("holder");exact(h,"claim_id","task_id","lease_id");holder=new TaskLease.Holder(hex(h,"claim_id"),hex(h,"task_id"),hex(h,"lease_id")); }
        return new TaskLease.Fence(hex(value,"operation_id"),integer(value,"lease_epoch",false),integer(value,"renewal_sequence",true),holder,integer(value,"ttl_ms",true));
    }
    static Envelope envelope(JSONObject value) throws Exception {
        exact(value,"type","claim_id","lease_id","lease_epoch","command");
        if (!"command".equals(value.getString("type")) || value.isNull("lease_id") != value.isNull("lease_epoch")) throw new IllegalArgumentException();
        return new Envelope(hex(value,"claim_id"),value.isNull("lease_id")?null:hex(value,"lease_id"),value.isNull("lease_epoch")?null:integer(value,"lease_epoch",false),value.getJSONObject("command"));
    }
    private static void name(JSONObject value) throws Exception {
        String name=value.getString("name");
        if(name.trim().isEmpty()||name.codePointCount(0,name.length())>80||name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE))throw new IllegalArgumentException();
    }
    static Set<String> claims(JSONObject value) throws Exception {
        if(!value.has("invitation"))throw new IllegalArgumentException();
        ServiceProtocol.version(value); if (!"multi".equals(value.getString("mode")) || integer(value,"max_grants",false)!=4) throw new IllegalArgumentException();
        integer(value,"generation",true);integer(value,"invitation_sequence",true);JSONArray grants=value.getJSONArray("grants");
        if(grants.length()>4)throw new IllegalArgumentException();Set<String> ids=new HashSet<>();
        for(int i=0;i<grants.length();i++) {
            JSONObject g=grants.getJSONObject(i);String claim=hex(g,"claim_id");name(g);
            if(!ids.add(claim)||!"approved".equals(g.getString("state"))||!ServiceProtocol.bool(g,"persistent")||!ServiceProtocol.bool(g,"all_apps")||!ServiceProtocol.bool(g,"allow_control")||g.getJSONArray("allowed_packages").length()!=0)throw new IllegalArgumentException();
            integer(g,"command_sequence",true);ServiceProtocol.bool(g,"lease_required");ServiceProtocol.bool(g,"lease_ready");
        }
        if(!value.isNull("invitation")) {
            JSONObject invitation=value.getJSONObject("invitation");
            exact(invitation,"invitation_id","invitation_sequence","state","pairing_expires_at","candidate");
            hex(invitation,"invitation_id");long seq=integer(invitation,"invitation_sequence",false);integer(invitation,"pairing_expires_at",false);
            if(seq>integer(value,"invitation_sequence",true))throw new IllegalArgumentException();
            boolean pending="pending".equals(invitation.getString("state"));
            if(!pending&&!"waiting".equals(invitation.getString("state"))||pending==invitation.isNull("candidate"))throw new IllegalArgumentException();
            if(pending){JSONObject candidate=invitation.getJSONObject("candidate");exact(candidate,"claim_id","name","verification_code");hex(candidate,"claim_id");name(candidate);if(!candidate.getString("verification_code").matches("[0-9]{6}"))throw new IllegalArgumentException();}
        }
        return Set.copyOf(ids);
    }
}
