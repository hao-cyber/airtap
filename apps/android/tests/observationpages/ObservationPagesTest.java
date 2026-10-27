package io.airtap.preview;
import java.util.*;
import org.json.*;
public final class ObservationPagesTest {
    interface Case { void run() throws Exception; }
    static int count;
    static void test(String name, Case test) throws Exception { test.run(); count++; System.out.println("PASS " + name); }
    static void check(boolean yes) { if (!yes) throw new AssertionError(); }
    static void fails(String code, Case test) throws Exception { try { test.run(); throw new AssertionError("expected " + code); } catch (ScopedUi.Refused e) { check(code.equals(e.getMessage())); } }
    static final class Node {
        final int id; String text = "x"; boolean visible = true; List<Node> children = new ArrayList<>(); JSONObject metadata = new JSONObject();
        Node(int id) { this.id = id; }
        public boolean equals(Object value) { return value instanceof Node n && id == n.id; }
        public int hashCode() { return id; }
    }
    static PagedTree.Source<Node> source(boolean export) { return new PagedTree.Source<>() {
        public PagedTree.Description describe(Node n) {
            return new PagedTree.Description(ObservedText.localFingerprint(n.text, false), n.children.size(), n.visible,
                    n.visible ? new JSONObject().put("node_id", String.valueOf(n.id)).put("text", n.text).put("metadata", n.metadata) : null);
        }
        public Node child(Node n, int index) { return n.children.get(index); }
    }; }
    static PagedTree<Node> capture(Node n) { return PagedTree.capture(n, source(true), () -> true); }
    public static void main(String[] args) throws Exception {
        test("300 visible nodes span three exact stable pages with no omissions", () -> {
            Node root = new Node(1); for (int i=2;i<=300;i++) root.children.add(new Node(i));
            var tree = capture(root); Set<String> seen = new HashSet<>(); int offset = 0, pages = 0;
            while (true) { JSONObject page = tree.page(offset,128); check((Integer)page.get("total_nodes") == 300); JSONArray nodes = (JSONArray)page.get("nodes");
                for (Object item : nodes.values) check(seen.add(((JSONObject)item).getString("node_id")));
                pages++; if (page.get("next_offset") == JSONObject.NULL) break; offset=(Integer)page.get("next_offset"); }
            check(pages==3 && seen.size()==300); check(tree.same(PagedTree.capture(root, source(false), () -> true)));
        });
        test("byte budget produces a continuation even before128 nodes", () -> {
            Node root=new Node(1); root.text="a".repeat(20000); for (int i=2;i<=5;i++) {Node child=new Node(i);child.text=root.text;root.children.add(child);}
            var page=capture(root).page(0,128);check((Integer)page.get("returned_nodes")==2);check((Integer)page.get("next_offset")==2);
        });
        test("oversized single public item cannot create a nonadvancing page", () -> {
            Node root=new Node(1);root.text="a".repeat(56001); fails("inspection_limit",()->capture(root).page(0,128));
        });
        test("topology order native replacement and hidden-state changes invalidate", () -> {
            Node root=new Node(1),a=new Node(2),b=new Node(3);root.children.add(a);root.children.add(b);var tree=capture(root);
            Collections.reverse(root.children);check(!tree.same(capture(root)));Collections.reverse(root.children);
            root.children.set(0,new Node(4));check(!tree.same(capture(root)));root.children.set(0,a);
            a.visible=false;check(!tree.same(capture(root)));
        });
        test("changed text beyond200 UTF16 units invalidates original snapshot", () -> {
            Node root=new Node(1);root.text="x".repeat(600)+"original";var tree=capture(root);root.text="x".repeat(600)+"changed";check(!tree.same(capture(root)));
        });
        test("range-only change rejects old page without changing target text or native identity", () -> {
            Node root=new Node(1);root.metadata.put("range",new JSONObject().put("min",0).put("max",100).put("current",10).put("type",0));var tree=capture(root);
            root.metadata.put("range",new JSONObject().put("min",0).put("max",100).put("current",11).put("type",0));check(!tree.same(capture(root)));
        });
        test("custom action label-only change rejects old page", () -> {
            Node root=new Node(1);root.metadata.put("custom_actions",new JSONArray().put(new JSONObject().put("action_id",33554432).put("label","Open draft")));var tree=capture(root);
            root.metadata.put("custom_actions",new JSONArray().put(new JSONObject().put("action_id",33554432).put("label","Delete draft")));check(!tree.same(capture(root)));
        });
        test("advertised action set-only change rejects old page", () -> {
            Node root=new Node(1);root.metadata.put("node_actions",new JSONArray().put("expand"));var tree=capture(root);
            root.metadata.put("node_actions",new JSONArray().put("collapse"));check(!tree.same(capture(root)));
        });
        test("collection accessibility focus and newly exported metadata participate in consistency", () -> {
            for(String key:List.of("collection","accessibility_focused","future_exported_field")){
                Node root=new Node(1);root.metadata.put(key,"before");var tree=capture(root);root.metadata.put(key,"after");check(!tree.same(capture(root)));
            }
        });
        test("equivalent nested metadata keeps valid pages independent of object key order", () -> {
            Node root=new Node(1);root.metadata.put("range",new JSONObject().put("current",10).put("max",100));var tree=capture(root);
            root.metadata=new JSONObject().put("range",new JSONObject().put("max",100).put("current",10));check(tree.same(capture(root)));check((Integer)tree.page(0,128).get("returned_nodes")==1);
        });
        test("node scan and byte limits fail explicitly without partial totals", () -> {
            Node root=new Node(1);for(int i=2;i<=2049;i++)root.children.add(new Node(i));fails("inspection_limit",()->capture(root));
            root.children.clear();root.text="a".repeat(PagedTree.MAX_CAPTURE_BYTES);fails("inspection_limit",()->capture(root));
        });
        test("missing child cancellation and deadline stop snapshot publication", () -> {
            Node root=new Node(1);root.children.add(null);fails("inspection_incomplete",()->capture(root));
            root.children.clear();fails("inspection_incomplete",()->PagedTree.capture(root,source(true),()->false));
        });
        test("page range validation and exact end page", () -> {
            var tree=capture(new Node(1));fails("invalid_page",()->tree.page(-1,128));fails("invalid_page",()->tree.page(2,128));fails("invalid_page",()->tree.page(0,129));
            check(((JSONArray)tree.page(1,128).get("nodes")).length()==0);
        });
        test("long Unicode text reconstructs exactly with returned UTF16 offsets", () -> {
            String original=("文字🚀e\u0301").repeat(1800);StringBuilder got=new StringBuilder();int offset=0;
            while(true){JSONObject chunk=ObservedText.chunk(original,offset,4096);got.append(chunk.getString("text"));check((Integer)chunk.get("total_length_utf16")==original.length());
                if(chunk.get("next_offset_utf16")==JSONObject.NULL)break;int next=(Integer)chunk.get("next_offset_utf16");check(next>offset);offset=next;}
            check(original.contentEquals(got));
        });
        test("surrogate edge avoids split and rejects middle offset", () -> {
            String original="a🚀z";JSONObject first=ObservedText.chunk(original,0,2);check(first.getString("text").equals("a"));check((Integer)first.get("next_offset_utf16")==1);
            check(ObservedText.chunk(original,1,2).getString("text").equals("🚀"));fails("invalid_text_boundary",()->ObservedText.chunk(original,2,2));
        });
        test("empty exact-end and invalid text ranges", () -> {
            check((Boolean)ObservedText.chunk("",0,2).get("eof"));check((Boolean)ObservedText.chunk("abc",3,2).get("eof"));
            fails("invalid_text_range",()->ObservedText.chunk("abc",4,2));fails("invalid_text_range",()->ObservedText.chunk("abc",0,1));fails("invalid_text_range",()->ObservedText.chunk("abc",0,4097));
        });
        test("masked observations never inspect measure hash or return private value", () -> {
            CharSequence forbidden=new CharSequence(){public int length(){throw new AssertionError();}public char charAt(int i){throw new AssertionError();}public CharSequence subSequence(int a,int b){throw new AssertionError();}public String toString(){throw new AssertionError();}};
            JSONObject node=new JSONObject();ObservedText.put(node,"text",forbidden,true);check(node.getString("text").equals(""));check(node.fields.size()==1);check(ObservedText.localFingerprint(forbidden,true).equals(""));
        });
        System.out.println("TOTAL "+count);
    }
}
