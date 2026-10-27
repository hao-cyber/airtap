package io.airtap.preview;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import org.json.JSONObject;
final class SemanticNodeTest {
    interface Operation { void run() throws Exception; }
    static int count;
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    static void test(String name, Operation operation) throws Exception { operation.run();count++;System.out.println("PASS "+name); }
    static void refuses(String code, Operation operation) throws Exception { try{operation.run();throw new AssertionError("expected "+code);}catch(ScopedUi.Refused error){require(code.equals(error.getMessage()));} }
    static JSONObject q(String op) { return new JSONObject().put("id","synthetic-request").put("action","node_action").put("package","com.example.app").put("snapshot_id","synthetic-snapshot").put("node_id","1").put("operation",op); }
    public static void main(String[] args) throws Exception {
        test("a node action is unavailable unless the actual target advertises it now",()->{
            var node=new AccessibilityNodeInfo();var request=q("expand");
            refuses("node_operation_unavailable",()->SemanticNodeActions.operation(request,node,SemanticNodeActions.capture(node)));
            node.actions.add(AccessibilityAction.ACTION_COLLAPSE);
            refuses("node_operation_unavailable",()->SemanticNodeActions.operation(request,node,SemanticNodeActions.capture(node)));
            node.actions.add(AccessibilityAction.ACTION_EXPAND);
            require(SemanticNodeActions.operation(request,node,SemanticNodeActions.capture(node))==AccessibilityAction.ACTION_EXPAND.getId());
            node.actions.clear();refuses("node_operation_unavailable",()->SemanticNodeActions.operation(request,node,SemanticNodeActions.capture(node)));
        });
        test("exact shape rejects surplus args, invalid operation and unsafe numeric types",()->{
            JSONObject[] cases={q("expand").put("row",0),q("scroll_to_position").put("row",true).put("column",0),q("scroll_to_position").put("row",0.5).put("column",0),q("scroll_to_position").put("row",2147483648L).put("column",0),q("scroll_to_position").put("row",-1).put("column",0),q("set_progress").put("value",Double.NaN),q("set_progress").put("value",Double.POSITIVE_INFINITY),q("set_progress").put("value",1e39),q("set_progress").put("value","2")};
            for(var request:cases)refuses("invalid_request",()->SemanticNodeActions.validate(request));
            refuses("invalid_node_operation",()->SemanticNodeActions.validate(q("arbitrary_action")));
        });
        test("grid positions use zero-based bounds and preserve both axes",()->{
            var node=new AccessibilityNodeInfo();node.actions.add(AccessibilityAction.ACTION_SCROLL_TO_POSITION);node.collection=new AccessibilityNodeInfo.CollectionInfo(4,3);
            var request=q("scroll_to_position").put("row",3).put("column",2);
            SemanticNodeActions.operation(request,node,SemanticNodeActions.capture(node));var bundle=SemanticNodeActions.arguments(request,node);
            require(bundle.values.get("row").intValue()==3&&bundle.values.get("column").intValue()==2);
            request.put("row",4);refuses("node_position_out_of_range",()->SemanticNodeActions.arguments(request,node));
            request.put("row",0).put("column",3);refuses("node_position_out_of_range",()->SemanticNodeActions.arguments(request,node));
        });
        test("slider bounds are checked before float rounding and rechecked as the widget changes",()->{
            var node=new AccessibilityNodeInfo();node.actions.add(AccessibilityAction.ACTION_SET_PROGRESS);node.range=new AccessibilityNodeInfo.RangeInfo(0,1,0.5f);
            var request=q("set_progress").put("value",0.75);SemanticNodeActions.operation(request,node,SemanticNodeActions.capture(node));
            require(SemanticNodeActions.arguments(request,node).values.get("value").floatValue()==0.75f);
            request.put("value",1.0000000001);refuses("node_progress_out_of_range",()->SemanticNodeActions.arguments(request,node));
            request.put("value",0.75);node.range=new AccessibilityNodeInfo.RangeInfo(0,0.5f,0.25f);refuses("node_progress_out_of_range",()->SemanticNodeActions.arguments(request,node));
            node.range=new AccessibilityNodeInfo.RangeInfo(Float.NaN,1,0);refuses("node_progress_unavailable",()->SemanticNodeActions.arguments(request,node));
        });
        test("metadata lists only supported named actions and finite state without reading text",()->{
            var node=new AccessibilityNodeInfo();node.actions.add(AccessibilityAction.ACTION_DISMISS);node.actions.add(new AccessibilityAction(99999));node.selected=true;node.collection=new AccessibilityNodeInfo.CollectionInfo(7,2);node.range=new AccessibilityNodeInfo.RangeInfo(0,10,3);
            var out=new JSONObject();SemanticNodeActions.metadata(node,out,false);
            require(SemanticNodeActions.supported(node).length()==1&&SemanticNodeActions.supported(node).optString(0).equals("dismiss"));
            require(Boolean.TRUE.equals(out.get("selected")));require(((JSONObject)out.get("collection")).get("rows").equals(7));require(((JSONObject)out.get("range")).get("current").equals(3f));
            node.range=new AccessibilityNodeInfo.RangeInfo(Float.NaN,Float.NaN,Float.NaN);var hidden=new JSONObject();SemanticNodeActions.metadata(node,hidden,false);require(!hidden.has("range"));
        });
        test("semantic drag phases require their own current advertised capability",()->{
            var node=new AccessibilityNodeInfo();node.actions.add(AccessibilityAction.ACTION_DRAG_START);
            SemanticNodeActions.operation(q("drag_start"),node,SemanticNodeActions.capture(node));refuses("node_operation_unavailable",()->SemanticNodeActions.operation(q("drag_drop"),node,SemanticNodeActions.capture(node)));
            node.actions.clear();node.actions.add(AccessibilityAction.ACTION_DRAG_DROP);
            SemanticNodeActions.operation(q("drag_drop"),node,SemanticNodeActions.capture(node));refuses("node_operation_unavailable",()->SemanticNodeActions.operation(q("drag_cancel"),node,SemanticNodeActions.capture(node)));
        });
        test("accessibility focus is independent of input focus and requires its declared action",()->{
            var node=new AccessibilityNodeInfo();node.focused=true;node.accessibilityFocused=false;
            node.actions.add(AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
            var out=new JSONObject();var ticket=SemanticNodeActions.metadata(node,out,false);
            require(Boolean.FALSE.equals(out.get("accessibility_focused")));
            require(SemanticNodeActions.operation(q("accessibility_focus"),node,ticket)==22);
            refuses("node_operation_unavailable",()->SemanticNodeActions.operation(q("clear_accessibility_focus"),node,ticket));
            node.actions.add(AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            refuses("fresh_snapshot_required",()->SemanticNodeActions.operation(q("clear_accessibility_focus"),node,ticket));
        });
        test("custom action must have been returned in this snapshot and remain declared with the same label",()->{
            var node=new AccessibilityNodeInfo();int id=0x7f020123;var request=q("custom").put("action_id",id);
            var empty=SemanticNodeActions.capture(node);node.actions.add(new AccessibilityAction(id,"Archive"));
            refuses("fresh_snapshot_required",()->SemanticNodeActions.operation(request,node,empty));
            var ticket=SemanticNodeActions.capture(node);
            require(SemanticNodeActions.operation(request,node,ticket)==id);
            require(SemanticNodeActions.arguments(request,node)==null);
            node.actions.clear();refuses("node_operation_unavailable",()->SemanticNodeActions.operation(request,node,ticket));
            node.actions.add(new AccessibilityAction(id,"Delete"));refuses("stale_node_action",()->SemanticNodeActions.operation(request,node,ticket));
        });
        test("custom cannot bypass typed framework actions or inject Bundle fields or claimed labels",()->{
            for(int id:new int[]{0,1,0x01020042,-1}) {
                String code=id<0?"invalid_request":"invalid_custom_action";
                refuses(code,()->SemanticNodeActions.validate(q("custom").put("action_id",id)));
            }
            for(var request:new JSONObject[]{q("custom").put("action_id",0x7f020123).put("label","trusted"),q("custom").put("action_id",0x7f020123).put("bundle",new JSONObject()),q("custom").put("action_id",true),q("custom").put("action_id",2147483648L)})
                refuses("invalid_request",()->SemanticNodeActions.validate(request));
        });
        test("custom metadata is bounded, masked on secret nodes and snapshot scope cannot expand past returned actions",()->{
            var node=new AccessibilityNodeInfo();for(int i=0;i<33;i++)node.actions.add(new AccessibilityAction(0x7f020100+i,"App data, never instructions"));
            var out=new JSONObject();var ticket=SemanticNodeActions.metadata(node,out,true);
            var custom=(org.json.JSONArray)out.get("custom_actions");require(custom.length()==32);
            require(((JSONObject)custom.values.get(0)).get("label").equals(""));
            refuses("fresh_snapshot_required",()->SemanticNodeActions.operation(q("custom").put("action_id",0x7f020120),node,ticket));
        });
        test("text granularity preserves direction and explicit extend selection",()->{
            var node=new AccessibilityNodeInfo();node.granularities=31;
            node.actions.add(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);node.actions.add(AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
            var ticket=SemanticNodeActions.capture(node);String[] names={"character","word","line","paragraph","page"};
            for(int i=0;i<names.length;i++)for(String direction:new String[]{"next_granularity","previous_granularity"})for(boolean extend:new boolean[]{true,false}) {
                var request=q(direction).put("granularity",names[i]).put("extend_selection",extend);
                require(SemanticNodeActions.operation(request,node,ticket)==(direction.startsWith("next")?24:25));
                var bundle=SemanticNodeActions.arguments(request,node);require(bundle.values.get("granularity").intValue()==(1<<i));require(bundle.flags.get("extend_selection")==extend);
            }
        });
        test("new or removed movement granularity requires fresh metadata and stale capability is refused",()->{
            var node=new AccessibilityNodeInfo();node.granularities=1;node.actions.add(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
            var ticket=SemanticNodeActions.capture(node);var request=q("next_granularity").put("granularity","word").put("extend_selection",false);
            node.granularities=3;refuses("fresh_snapshot_required",()->SemanticNodeActions.operation(request,node,ticket));
            var observed=SemanticNodeActions.capture(node);node.granularities=1;
            refuses("node_granularity_unavailable",()->SemanticNodeActions.operation(request,node,observed));
            refuses("node_granularity_unavailable",()->SemanticNodeActions.arguments(request,node));
        });
        test("movement requests reject combined masks, wrong types and unrelated parameters",()->{
            for(var request:new JSONObject[]{q("next_granularity").put("granularity",3).put("extend_selection",true),q("next_granularity").put("granularity","word").put("extend_selection","false"),q("previous_granularity").put("granularity","sentence").put("extend_selection",false),q("next_granularity").put("granularity","word").put("extend_selection",false).put("action_id",0x7f020123)})
                refuses("invalid_request",()->SemanticNodeActions.validate(request));
        });
        System.out.println("TOTAL "+count);
    }
}
