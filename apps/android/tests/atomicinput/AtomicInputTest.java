package io.airtap.preview;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import org.json.JSONObject;
final class AtomicInputTest {
    interface Operation { void run() throws Exception; }
    static int count;
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    static void test(String name, Operation operation) throws Exception { operation.run(); count++; System.out.println("PASS " + name); }
    static void refuses(String code, Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("expected " + code); }
        catch (ScopedUi.Refused error) {
            require(code.equals(error.getMessage()));
            if (java.util.Set.of("focused_editor_required", "editor_identity_unavailable", "editor_target_changed",
                    "editor_session_changed", "fresh_editor_snapshot_required").contains(code)) {
                String next = AtomicInput.nextStep(code);
                require(next != null && next.length() <= 500 && next.contains("not dispatched") && next.contains("fresh tree"));
                if (code.equals("editor_identity_unavailable")) {
                    require(next.contains("only advertised node actions") && next.contains("editor_action default")
                            && next.contains("when advertised") && next.contains("Do not bypass identity checks"));
                } else if (code.equals("editor_target_changed")) {
                    require(next.contains("Do not reuse the old node") && next.contains("bypass the target check"));
                } else if (code.equals("focused_editor_required")) {
                    require(next.contains("focus or tap") && next.contains("do not blindly retry"));
                } else {
                    require(next.contains("Do not reuse the old snapshot") && next.contains("unknown outcome"));
                }
            }
        }
    }
    static JSONObject q(String action) { return new JSONObject().put("action", action); }
    static GuardedInputMethod bind(AccessibilityService service, AccessibilityNodeInfo root) {
        service.root=root;var original=service.input;var method=new GuardedInputMethod(service);
        if(original!=null){method.started=original.started;method.info=original.info;method.connection=original.connection;}
        service.input=method;method.onStartInput(method.info,false);return method;
    }
    static void perform(AccessibilityService service, AccessibilityNodeInfo root, AccessibilityNodeInfo node, JSONObject request) throws Exception {
        if(service.input!=null && !(service.input instanceof GuardedInputMethod))bind(service,root);
        AtomicInput.perform(service,root,node,request,AtomicInput.capture(service,root,node),()->true);
    }
    public static void main(String[] args) throws Exception {
        test("tree byte overflow after popping the final node reports truncated", () -> {
            var queue = new java.util.ArrayDeque<Integer>(java.util.List.of(30000, 30000));
            var budget = new TreeReadBudget(); int emitted = 0;
            while (!queue.isEmpty()) { int size = queue.removeFirst(); if (!budget.include(size)) break; emitted++; }
            require(queue.isEmpty() && emitted == 1 && budget.truncated(!queue.isEmpty()));
        });
        test("tree exactly at the byte budget with no pending nodes remains complete", () -> {
            var queue = new java.util.ArrayDeque<Integer>(java.util.List.of(30000, 26000));
            var budget = new TreeReadBudget(); int emitted = 0;
            while (!queue.isEmpty()) { int size = queue.removeFirst(); if (!budget.include(size)) break; emitted++; }
            require(queue.isEmpty() && emitted == 2 && !budget.truncated(!queue.isEmpty()));
        });
        test("tree with queued nodes remains truncated without byte overflow", () -> {
            var queue = new java.util.ArrayDeque<Integer>(java.util.List.of(100, 200));
            var budget = new TreeReadBudget(); require(budget.include(queue.removeFirst()));
            // Another traversal limit stopped the read before its byte budget was exhausted.
            require(!queue.isEmpty() && budget.truncated(!queue.isEmpty()));
        });
        test("tree oversized first item is never emitted even with an empty remaining queue", () -> {
            var queue = new java.util.ArrayDeque<Integer>(java.util.List.of(TreeReadBudget.MAX_BYTES + 1));
            var budget = new TreeReadBudget(); require(!budget.include(queue.removeFirst()));
            require(queue.isEmpty() && budget.truncated(false));
        });
        test("snapshot fingerprint detects equal-length edits beyond the display prefix", () -> {
            String first="a".repeat(220)+"x", second="a".repeat(220)+"y";
            require(ObservedText.clip(first,200).equals(ObservedText.clip(second,200))&&first.length()==second.length());
            require(!ObservedText.localFingerprint(first,false).equals(ObservedText.localFingerprint(second,false)));
            require(ObservedText.localFingerprint(first,false).equals(ObservedText.localFingerprint(new StringBuilder(first),false)));
        });
        test("snapshot fingerprint hashes exact UTF16 including surrogate boundaries and chunk tails", () -> {
            String first="a".repeat(2047)+"\uD83E\uDEF6z", second="a".repeat(2047)+"\uD83E\uDEF7z";
            require(!ObservedText.localFingerprint(first,false).equals(ObservedText.localFingerprint(second,false)));
            require(!ObservedText.localFingerprint("\uD800",false).equals(ObservedText.localFingerprint("\uD801",false)));
            require(ObservedText.localFingerprint(null,false).equals(ObservedText.localFingerprint("",false)));
        });
        test("masked snapshot text is not inspected hashed or measured", () -> {
            CharSequence inaccessible=new CharSequence(){
                public int length(){throw new AssertionError();}
                public char charAt(int index){throw new AssertionError();}
                public CharSequence subSequence(int start,int end){throw new AssertionError();}
                public String toString(){throw new AssertionError();}
            };
            require(ObservedText.localFingerprint(inaccessible,true).equals(""));
        });
        test("observed text exposes full UTF16 length without splitting a boundary emoji", () -> {
            String raw="a".repeat(199)+"🫶b";var out=new JSONObject();ObservedText.put(out,"text",raw,false);
            require(out.getString("text").equals("a".repeat(199)));
            require(out.get("text_length_utf16").equals(202)&&out.get("text_truncated").equals(true));
            require(ObservedText.clip("🫶",1).equals("")&&ObservedText.clip("🫶",2).equals("🫶"));
        });
        test("observed empty and untruncated fields have precise metadata", () -> {
            var out=new JSONObject();ObservedText.put(out,"text",null,false);ObservedText.put(out,"description","中文",false);
            require(out.getString("text").equals("")&&out.get("text_length_utf16").equals(0)&&out.get("text_truncated").equals(false));
            require(out.getString("description").equals("中文")&&out.get("description_length_utf16").equals(2)&&out.get("description_truncated").equals(false));
        });
        test("masked text descriptions and hints reveal neither content nor original length", () -> {
            for(String name:new String[]{"text","description","hint_text"}){
                var out=new JSONObject();ObservedText.put(out,name,"private value".repeat(40),true);
                require(out.getString(name).equals("")&&out.fields.size()==1);
            }
        });
        test("identified editor hint reports declared standard action without dispatch", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            var hint=AtomicInput.editorActionHint(service,node,node,AtomicInput.capture(service,node,node));
            require(hint.getString("default_route").equals("input_connection")&&hint.getString("default_action").equals("search"));
            require(method.connection.calls==0&&node.calls==0);
        });
        test("native enter is not mislabeled DONE from the editor option", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();service.input.info.imeOptions=6;
            node.actions.add(AccessibilityAction.ACTION_IME_ENTER);bind(service,node);
            var hint=AtomicInput.editorActionHint(service,node,node,AtomicInput.capture(service,node,node));
            require(hint.getString("default_route").equals("accessibility_node")&&hint.getString("default_action").equals("native_enter")&&hint.getString("ime_option").equals("done"));
        });
        test("custom unknown and absent editor actions keep their actual classification", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            method.info.actionId=1;
            require(AtomicInput.editorActionHint(service,node,node,AtomicInput.capture(service,node,node)).getString("default_action").equals("custom"));
            method.info.actionId=0;
            for(int action:new int[]{0,1,99}){
                method.info.imeOptions=action;
                require(AtomicInput.editorActionHint(service,node,node,AtomicInput.capture(service,node,node)).getString("default_action").equals(action==0?"unspecified":action==1?"none":"unknown"));
            }
        });
        test("unbound or superseded editor sessions expose no inferred action hint", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            var ticket=AtomicInput.capture(service,node,node);method.onStartInput(method.info,true);
            require(AtomicInput.editorActionHint(service,node,node,ticket)==null);
            node.viewId=null;require(AtomicInput.editorActionHint(service,node,node,AtomicInput.capture(service,node,node))==null);
            require(method.connection.calls==0);
        });
        test("advertised native enter stays discoverable without borrowing unverified IME metadata", () -> {
            for (boolean missingIdentity : new boolean[]{true,false}) {
                var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
                node.actions.add(AccessibilityAction.ACTION_IME_ENTER);
                var ticket=AtomicInput.capture(service,node,node);
                if(missingIdentity) { node.viewId=null;ticket=AtomicInput.capture(service,node,node); }
                else method.onStartInput(method.info,true);
                var hint=AtomicInput.editorActionHint(service,node,node,ticket);
                require(hint.getString("default_route").equals("accessibility_node")&&hint.getString("default_action").equals("native_enter"));
                require(Boolean.TRUE.equals(hint.get("requires_result_observation"))&&hint.fields.size()==3);
                require(method.connection.calls==0&&node.calls==0);
                var original=ticket;
                refuses(missingIdentity?"editor_identity_unavailable":"editor_session_changed",
                        ()->AtomicInput.perform(service,node,node,q("editor_action").put("editor_action","search"),original,()->true));
                require(method.connection.calls==0&&node.calls==0);
            }
        });
        test("Unicode commit preserves Chinese emoji RTL and newline without a clipboard read", () -> {
            var service=new AccessibilityService(); var node=new AccessibilityNodeInfo();
            String value="中文 🫶 שלום\nnext";
            perform(service,node,node,q("insert_text").put("text",value));
            require(value.equals(service.input.connection.text));require(service.input.connection.calls==1);
        });
        test("stale focus, another window, inactive IME and another package never receive editor input", () -> {
            for(int state=0;state<6;state++) {
                var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var root=new AccessibilityNodeInfo();root.focus=node;
                switch(state){case 0:node.focused=false;break;case 1:root.focus=new AccessibilityNodeInfo();break;case 2:root.window=8;break;case 3:service.input.started=false;break;case 4:service.input.info.packageName="com.other.app";break;case 5:node.editable=false;break;}
                refuses("focused_editor_required",()->perform(service,root,node,q("insert_text").put("text","x")));
                require(service.input.connection.calls==0);
            }
        });
        test("selection uses exact UTF16 offsets and advertised node path without IME", () -> {
            var service=new AccessibilityService();service.input=null;var node=new AccessibilityNodeInfo();
            node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION));
            perform(service,node,node,q("set_selection").put("start",1).put("end",3));
            require(node.calls==1&&node.arguments.values.get("start")==1&&node.arguments.values.get("end")==3);
        });
        test("select-all selects exact UTF16 text on an unbound node without an IME connection", () -> {
            var service=new AccessibilityService();service.input=null;var node=new AccessibilityNodeInfo();node.viewId=null;
            node.text="中🫶e\u0301";node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION));
            var actions=AtomicInput.actions(node,false);require(actions.length()==2&&actions.optString(1).equals("select_all"));
            perform(service,node,node,q("select_all"));
            require(node.calls==1&&node.operation==AccessibilityNodeInfo.ACTION_SET_SELECTION);
            require(node.arguments.values.get("start")==0&&node.arguments.values.get("end")==5);
        });
        test("select-all handles empty editors and ignores displayed hint length", () -> {
            for(int state=0;state<3;state++){
                var service=new AccessibilityService();service.input=null;var node=new AccessibilityNodeInfo();
                node.text=state==0?"":state==1?"Enter a value":null;node.showingHint=state!=0;
                node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION));
                perform(service,node,node,q("select_all"));require(node.calls==1&&node.arguments.values.get("end")==0);
            }
        });
        test("unavailable node select-all keeps existing guarded editor fallback", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();
            node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION));
            require(AtomicInput.actions(node,false).length()==1); // Null text is not a known empty value.
            perform(service,node,node,q("select_all"));require(node.calls==0&&service.input.connection.action==android.R.id.selectAll);
            node.text="Known text";node.actions.clear();require(AtomicInput.actions(node,false).length()==0);
            node.viewId=null;refuses("editor_identity_unavailable",()->perform(service,node,node,q("select_all")));
            require(node.calls==0&&service.input.connection.calls==1);
        });
        test("node select-all rejection is not retried and transport invalidation fences dispatch", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();node.text="fixture";node.accept=false;
            node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION));
            refuses("action_rejected",()->perform(service,node,node,q("select_all")));
            require(node.calls==1&&service.input.connection.calls==0);
            refuses("permission_denied",()->AtomicInput.perform(service,node,node,q("select_all"),null,()->false));
            require(node.calls==1&&service.input.connection.calls==0);
        });
        test("node rejection is not retried through the editor", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();node.accept=false;
            node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE));
            refuses("action_rejected",()->perform(service,node,node,q("paste")));
            require(node.calls==1&&service.input.connection.calls==0);
        });
        test("select-all/copy/cut/paste route to the focused editor without fetching text", () -> {
            int[] expected={android.R.id.selectAll,android.R.id.copy,android.R.id.cut,android.R.id.paste};
            String[] actions={"select_all","copy","cut","paste"};
            for(int i=0;i<actions.length;i++) {var service=new AccessibilityService();var node=new AccessibilityNodeInfo();perform(service,node,node,q(actions[i]));require(service.input.connection.calls==1&&service.input.connection.action==expected[i]);}
        });
        test("invalid numeric/Unicode values never reach an input connection", () -> {
            JSONObject[] cases={q("set_selection").put("start",true).put("end",0),q("set_selection").put("start",0.5).put("end",0),q("set_selection").put("start",-1).put("end",0),q("delete_text").put("before",0).put("after",0),q("delete_text").put("before",2001).put("after",0),q("insert_text").put("text","\ud800"),q("editor_action").put("editor_action","arbitrary")};
            for(JSONObject request:cases){var service=new AccessibilityService();var node=new AccessibilityNodeInfo();refuses("invalid_request",()->perform(service,node,node,request));require(service.input.connection.calls==0&&node.calls==0);}
        });
        test("late editor selection callbacks block writes until a fresh matching observation", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            var old=AtomicInput.capture(service,node,node);node.selectionStart=node.selectionEnd=25;
            require(AtomicInput.capture(service,node,node)==null);
            refuses("editor_selection_pending",()->AtomicInput.perform(service,node,node,q("insert_text").put("text","x"),old,()->true));
            require(method.connection.calls==0);
            method.onUpdateSelection(0,0,25,25,-1,-1);
            perform(service,node,node,q("insert_text").put("text","x"));require(method.connection.calls==1);
        });
        test("unknown composition never dispatches an unproven relative deletion", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            refuses("editor_composition_unknown",()->perform(service,node,node,q("delete_text").put("before",3).put("after",0)));
            require(method.connection.calls==0);
        });
        test("composition-expanded before and after deletes are rejected without mutation", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            node.selectionStart=node.selectionEnd=25;method.onUpdateSelection(0,0,25,25,17,25);
            refuses("editor_composition_active",()->perform(service,node,node,q("delete_text").put("before",3).put("after",0)));
            require(method.connection.calls==0);
            node.selectionStart=node.selectionEnd=17;method.onUpdateSelection(25,25,17,17,17,25);
            refuses("editor_composition_active",()->perform(service,node,node,q("delete_text").put("before",0).put("after",3)));
            require(method.connection.calls==0);
        });
        test("noncollapsed selection keeps independent before and after semantics", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            node.selectionStart=5;node.selectionEnd=9;method.onUpdateSelection(0,0,5,9,3,12);
            refuses("editor_composition_active",()->perform(service,node,node,q("delete_text").put("before",2).put("after",2)));
            require(method.connection.calls==0);
            method.onUpdateSelection(5,9,5,9,6,8);perform(service,node,node,q("delete_text").put("before",2).put("after",2));
            require(method.connection.calls==1&&method.connection.before==2&&method.connection.after==2);
            require(AtomicInput.nextStep("editor_composition_active").contains("Keep any originally selected content"));
            require(AtomicInput.nextStep("editor_composition_active").length()<=500&&AtomicInput.nextStep("editor_selection_pending").length()<=500);
        });
        test("composition-only changes invalidate earlier editor tickets", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            method.onUpdateSelection(0,0,0,0,-1,-1);var ticket=AtomicInput.capture(service,node,node);
            method.onUpdateSelection(0,0,0,0,0,3);
            refuses("editor_session_changed",()->AtomicInput.perform(service,node,node,q("insert_text").put("text","x"),ticket,()->true));require(method.connection.calls==0);
        });
        test("transport stop still fences a deletion after selection and composition validation", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            method.onUpdateSelection(0,0,0,0,-1,-1);
            refuses("permission_denied",()->AtomicInput.perform(service,node,node,q("delete_text").put("before",1).put("after",0),AtomicInput.capture(service,node,node),()->false));
            require(method.connection.calls==0);
        });
        test("delete dispatch preserves before/after limits", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);method.onUpdateSelection(0,0,0,0,-1,-1);perform(service,node,node,q("delete_text").put("before",2).put("after",1));require(service.input.connection.calls==1&&service.input.connection.before==2&&service.input.connection.after==1);
        });
        test("default editor action uses actual app action and rejects absent action", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();service.input.info.actionId=73;
            perform(service,node,node,q("editor_action").put("editor_action","default"));require(service.input.connection.action==73);
            service.input.info.actionId=0;service.input.info.imeOptions=1;
            refuses("editor_action_unavailable",()->perform(service,node,node,q("editor_action").put("editor_action","default")));require(service.input.connection.calls==1);
        });
        test("default IME action supports an advertised node without an input connection", () -> {
            var service=new AccessibilityService();service.input=null;var node=new AccessibilityNodeInfo();
            node.actions.add(AccessibilityAction.ACTION_IME_ENTER);
            var hint=AtomicInput.editorActionHint(service,node,node,null);
            require(hint.getString("default_route").equals("accessibility_node")&&hint.getString("default_action").equals("native_enter"));
            require(Boolean.TRUE.equals(hint.get("requires_result_observation"))&&hint.fields.size()==3&&node.calls==0);
            perform(service,node,node,q("editor_action").put("editor_action","default"));
            require(node.calls==1&&node.operation==AccessibilityAction.ACTION_IME_ENTER.getId());
        });
        test("custom editor action ID may coincide with a standard absent-action value", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();service.input.info.actionId=1;
            perform(service,node,node,q("editor_action").put("editor_action","default"));require(service.input.connection.calls==1&&service.input.connection.action==1);
        });
        test("same-package focus B never receives writes through a still-bound A connection", () -> {
            var service=new AccessibilityService();var root=new AccessibilityNodeInfo();var a=new AccessibilityNodeInfo();var b=new AccessibilityNodeInfo();b.viewId="com.example.app:id/second";root.focus=a;
            var method=bind(service,root);var ticketA=AtomicInput.capture(service,root,a);require(ticketA!=null);
            root.focus=b;a.focused=false;
            require(AtomicInput.capture(service,root,b)==null);
            refuses("editor_target_changed",()->AtomicInput.perform(service,root,b,q("insert_text").put("text","must not reach A"),ticketA,()->true));
            require(method.connection.calls==0);
            method.info=new android.view.inputmethod.EditorInfo();method.info.fieldId=200;method.onStartInput(method.info,false);
            var ticketB=AtomicInput.capture(service,root,b);require(ticketB!=null);
            AtomicInput.perform(service,root,b,q("insert_text").put("text","B only"),ticketB,()->true);require(method.connection.calls==1&&method.connection.text.equals("B only"));
        });
        test("late A start callback when B already has focus does not bind B to A", () -> {
            var service=new AccessibilityService();var b=new AccessibilityNodeInfo();b.viewId="com.example.app:id/second";
            var method=bind(service,b);require(AtomicInput.capture(service,b,b)==null);
            refuses("editor_target_changed",()->AtomicInput.perform(service,b,b,q("insert_text").put("text","unsafe"),null,()->true));require(method.connection.calls==0);
        });
        test("input restart and cursor update invalidate a previously captured editor ticket", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);
            var old=AtomicInput.capture(service,node,node);method.onStartInput(method.info,true);
            refuses("fresh_editor_snapshot_required",()->AtomicInput.perform(service,node,node,q("insert_text").put("text","x"),null,()->true));require(method.connection.calls==0);
            refuses("editor_session_changed",()->AtomicInput.perform(service,node,node,q("insert_text").put("text","x"),old,()->true));
            var refreshed=AtomicInput.capture(service,node,node);method.onUpdateSelection(0,0,1,1,-1,-1);node.selectionStart=node.selectionEnd=1;
            refuses("editor_session_changed",()->AtomicInput.perform(service,node,node,q("delete_text").put("before",1).put("after",0),refreshed,()->true));require(method.connection.calls==0);
        });
        test("finished session, missing resource identity and another window never receive editor writes", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);var ticket=AtomicInput.capture(service,node,node);method.onFinishInput();
            refuses("focused_editor_required",()->AtomicInput.perform(service,node,node,q("paste"),ticket,()->true));require(method.connection.calls==0);
            method.onStartInput(method.info,false);node.viewId=null;
            refuses("editor_identity_unavailable",()->AtomicInput.perform(service,node,node,q("insert_text").put("text","x"),ticket,()->true));require(method.connection.calls==0);
            node.viewId="com.example.app:id/first";method.onStartInput(method.info,false);var current=AtomicInput.capture(service,node,node);node.window=8;
            refuses("editor_target_changed",()->AtomicInput.perform(service,node,node,q("insert_text").put("text","x"),current,()->true));require(method.connection.calls==0);
        });
        test("same resource ID on a different node cannot reuse an old editor session", () -> {
            var service=new AccessibilityService();var root=new AccessibilityNodeInfo();var a=new AccessibilityNodeInfo();var b=new AccessibilityNodeInfo();root.focus=a;
            var method=bind(service,root);var ticket=AtomicInput.capture(service,root,a);root.focus=b;
            refuses("editor_target_changed",()->AtomicInput.perform(service,root,b,q("insert_text").put("text","x"),ticket,()->true));require(method.connection.calls==0);
        });
        test("new session anchors a unique resource field before focus cache catches up", () -> {
            var service=new AccessibilityService();var root=new AccessibilityNodeInfo();root.editable=false;var a=new AccessibilityNodeInfo();var b=new AccessibilityNodeInfo();b.viewId="com.example.app:id/second";b.focused=false;root.focus=a;root.searchResults=java.util.List.of(b);
            service.input.info.fieldId=200;var method=bind(service,root);
            require(AtomicInput.capture(service,root,b)==null);require(method.connection.calls==0);
            a.focused=false;b.focused=true;root.focus=b;
            var ticket=AtomicInput.capture(service,root,b);require(ticket!=null);
            AtomicInput.perform(service,root,b,q("insert_text").put("text","secondary after focus"),ticket,()->true);require(method.connection.calls==1);
        });
        test("deferred unique anchor cannot drift to a rebuilt field with the same resource ID", () -> {
            var service=new AccessibilityService();var root=new AccessibilityNodeInfo();root.editable=false;root.focus=null;
            var original=new AccessibilityNodeInfo();original.viewId="com.example.app:id/second";original.focused=false;root.searchResults=java.util.List.of(original);service.input.info.fieldId=200;var method=bind(service,root);
            var rebuilt=new AccessibilityNodeInfo();rebuilt.viewId=original.viewId;root.focus=rebuilt;root.searchResults=java.util.List.of(rebuilt);
            require(AtomicInput.capture(service,root,rebuilt)==null);refuses("editor_target_changed",()->AtomicInput.perform(service,root,rebuilt,q("insert_text").put("text","must not write"),null,()->true));require(method.connection.calls==0);
        });
        test("ambiguous or absent start candidates never bind later just because focus becomes available", () -> {
            for(int state=0;state<3;state++){
                var service=new AccessibilityService();var root=new AccessibilityNodeInfo();root.editable=false;root.focus=null;var a=new AccessibilityNodeInfo();var b=new AccessibilityNodeInfo();a.viewId=b.viewId="com.example.app:id/second";
                root.searchResults=state==0?java.util.List.of(a,b):state==1?java.util.List.of():java.util.List.of(a);if(state==2)a.window=99;
                service.input.info.fieldId=200;var method=bind(service,root);root.focus=b;root.searchResults=java.util.List.of(b);
                require(AtomicInput.capture(service,root,b)==null);refuses("editor_identity_unavailable",()->AtomicInput.perform(service,root,b,q("insert_text").put("text","must not write"),null,()->true));require(method.connection.calls==0);
            }
        });
        test("a deferred anchor is invalidated by a new session or finish before focus confirmation", () -> {
            var service=new AccessibilityService();var root=new AccessibilityNodeInfo();root.editable=false;root.focus=null;var b=new AccessibilityNodeInfo();b.viewId="com.example.app:id/second";root.searchResults=java.util.List.of(b);service.input.info.fieldId=200;var method=bind(service,root);
            method.onFinishInput();root.focus=b;require(AtomicInput.capture(service,root,b)==null);
            method.info=new android.view.inputmethod.EditorInfo();method.onStartInput(method.info,false);
            refuses("editor_target_changed",()->AtomicInput.perform(service,root,b,q("insert_text").put("text","must not write"),null,()->true));require(method.connection.calls==0);
        });
        test("node-targeted paste remains available without an identifiable IME field", () -> {
            var service=new AccessibilityService();var node=new AccessibilityNodeInfo();node.viewId=null;node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE));var method=bind(service,node);
            require(AtomicInput.capture(service,node,node)==null);
            AtomicInput.perform(service,node,node,q("paste"),null,()->true);require(node.calls==1&&method.connection.calls==0);
        });
        test("transport close fences ordinary node and advertised input actions before main cleanup", () -> {
            var open=new java.util.concurrent.atomic.AtomicBoolean(true);boolean authorized=true;var node=new AccessibilityNodeInfo();
            open.set(false); // WebSocket transport thread; main-thread authorized cleanup has not run.
            require(authorized);refuses("permission_denied",()->CommandFence.node(open::get,node,123,null));require(node.calls==0);
            var service=new AccessibilityService();node.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE));
            refuses("permission_denied",()->AtomicInput.perform(service,node,node,q("paste"),null,open::get));require(node.calls==0&&service.input.connection.calls==0);
            open.set(true);require(CommandFence.node(open::get,node,123,null));require(node.calls==1);
        });
        test("transport invalidation during editor lookup is rechecked before any IME dispatch", () -> {
            String[] actions={"insert_text","delete_text","set_selection","select_all","copy","cut","paste","editor_action"};
            for(String action:actions){
                var service=new AccessibilityService();var node=new AccessibilityNodeInfo();var method=bind(service,node);var ticket=AtomicInput.capture(service,node,node);
                var open=new java.util.concurrent.atomic.AtomicBoolean(true);boolean authorized=true;
                method.connectionReadHook=()->open.set(false); // Invalidation after outer admission, while editor is queried.
                var request=q(action).put("text","never").put("before",1).put("after",0).put("start",0).put("end",0).put("editor_action","done");
                refuses("permission_denied",()->AtomicInput.perform(service,node,node,request,ticket,open::get));
                require(authorized&&!open.get()&&method.connection.calls==0&&node.calls==0);
            }
        });
        test("system action availability is checked at dispatch and rejections remain visible", () -> {
            var service=new AccessibilityService();require(SystemActions.available(service).length()==0);
            refuses("system_action_unavailable",()->SystemActions.perform(service,"dpad_center",()->true));require(service.calls==0);
            service.actions.add(new AccessibilityAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER));
            require(SystemActions.available(service).optString(0).equals("dpad_center"));SystemActions.perform(service,"dpad_center",()->true);require(service.calls==1);
            service.accept=false;refuses("action_rejected",()->SystemActions.perform(service,"dpad_center",()->true));require(service.calls==2);
        });
        test("disconnect during Android system-action discovery prevents final dispatch", () -> {
            var service=new AccessibilityService();var current=new java.util.concurrent.atomic.AtomicBoolean(true);
            service.actions.add(new AccessibilityAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER));
            service.actionsReadHook=()->current.set(false);
            refuses("permission_denied",()->SystemActions.perform(service,"dpad_center",current::get));
            require(service.calls==0);
            current.set(true);SystemActions.perform(service,"dpad_center",current::get);require(service.calls==1);
        });
        test("disconnect fences launching and global navigation before delayed main-thread cleanup", () -> {
            var service=new AccessibilityService();var current=new java.util.concurrent.atomic.AtomicBoolean(false);
            var intent=new android.content.Intent();
            refuses("permission_denied",()->CommandFence.activity(current::get,service,intent));
            refuses("permission_denied",()->CommandFence.global(current::get,service,77));
            require(service.calls==0&&service.activityCalls==0);
            current.set(true);CommandFence.activity(current::get,service,intent);
            require(service.activityCalls==1&&service.lastIntent==intent);
            require(CommandFence.global(current::get,service,77)&&service.calls==1&&service.last==77);
        });
        System.out.println("TOTAL " + count);
    }
}
