package test.pages;
import android.app.*;import android.os.*;import android.content.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.function.*;import org.json.*;
public final class PageRunner extends Instrumentation {
 AtomicBoolean allowed=new AtomicBoolean(true),current=new AtomicBoolean(true);Object ui;Method execute;Class<?> replyClass;final JSONArray checks=new JSONArray();boolean metadataOnly;
 @Override public void onCreate(Bundle b){super.onCreate(b);metadataOnly="true".equals(b.getString("metadata_only"));start();}
 void check(String name,boolean okay)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",okay));if(!okay)throw new AssertionError(name);}
 JSONObject q(String action)throws Exception{return new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("action",action);}
 JSONObject request(JSONObject q)throws Exception{
  CountDownLatch latch=new CountDownLatch(1);AtomicReference<JSONObject> out=new AtomicReference<>();AtomicReference<Throwable> err=new AtomicReference<>();
  Object reply=java.lang.reflect.Proxy.newProxyInstance(replyClass.getClassLoader(),new Class<?>[]{replyClass},(p,m,a)->{if(m.getName().equals("success")){out.set((JSONObject)a[0]);latch.countDown();}else if(m.getName().equals("error")){out.set(new JSONObject().put("error",a[0]));latch.countDown();}return null;});
  runOnMainSync(()->{try{execute.invoke(ui,q,reply,(BooleanSupplier)current::get);}catch(Throwable e){err.set(e);latch.countDown();}});
  if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("command timed out");if(err.get()!=null)throw new RuntimeException(err.get());return out.get();
 }
 JSONObject target(String action,JSONObject tree)throws Exception{return q(action).put("package",tree.getString("package")).put("snapshot_id",tree.getString("snapshot_id"));}
 JSONObject tree()throws Exception{return request(q("read_tree").put("package","io.airtap.fixture.pages"));}
 void launch(int count,String variant)throws Exception{getContext().startActivity(new Intent().setClassName("io.airtap.fixture.pages","test.pages.PageActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("count",count).putExtra("variant",variant));Thread.sleep(1200);}
 JSONObject range(JSONObject page)throws Exception{JSONArray rows=page.getJSONArray("nodes");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).has("range"))return rows.getJSONObject(i);throw new AssertionError("range missing");}
 void metadataCheck(Bundle result)throws Exception{
  launch(150,"");JSONObject first=tree();check("native metadata snapshot captures more than one page",!first.has("error")&&first.getInt("total_nodes")>128);
  JSONObject page=request(target("read_tree_page",first).put("offset",128).put("limit",128));check("unchanged native metadata preserves continuation",!page.has("error"));JSONObject before=range(page);check("native range starts at10",before.getJSONObject("range").getInt("current")==10);
  getContext().sendBroadcast(new Intent("io.airtap.fixture.pages.MUTATE").setPackage("io.airtap.fixture.pages").putExtra("range",true));Thread.sleep(300);
  JSONObject denied=request(target("read_tree_page",first).put("offset",128).put("limit",128));check("range-only mutation rejects original cached page",denied.optString("error").equals("fresh_snapshot_required"));
  first=tree();page=request(target("read_tree_page",first).put("offset",128).put("limit",128));JSONObject after=range(page);check("fresh native snapshot exposes updated range11",after.getJSONObject("range").getInt("current")==11);
  before.remove("range");after.remove("range");check("range-only fixture kept every other exported node field unchanged",before.toString().equals(after.toString()));
  result.putString("report",new JSONObject().put("checks",checks).put("complete",true).put("scope","native range metadata regression only").toString());finish(-1,result);
 }
 @Override public void onStart(){Bundle result=new Bundle();try{
  Class<?> preview=Class.forName("io.airtap.preview.PreviewService");Field instance=preview.getDeclaredField("instance");instance.setAccessible(true);Object service=null;
  for(int i=0;i<150&&service==null;i++){service=instance.get(null);if(service==null)Thread.sleep(200);}if(service==null)throw new AssertionError("no service");
  Class<?> type=Class.forName("io.airtap.preview.CompanionUi");Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);ui=constructor.newInstance(service,(BooleanSupplier)allowed::get);replyClass=Class.forName("io.airtap.preview.CompanionUi$Reply");execute=type.getDeclaredMethod("execute",JSONObject.class,replyClass,BooleanSupplier.class);execute.setAccessible(true);
  if(metadataOnly){metadataCheck(result);return;}
  launch(300,"");JSONObject first=tree();check("actual Android full snapshot captures >128 visible nodes",!first.has("error")&&first.getInt("total_nodes")>128);String sid=first.getString("snapshot_id");int total=first.getInt("total_nodes"),offset=0;JSONArray all=new JSONArray();JSONObject page=first;long ttl=first.getLong("expires_in_ms");
  while(true){JSONArray rows=page.getJSONArray("nodes");for(int i=0;i<rows.length();i++)all.put(rows.getJSONObject(i));if(page.isNull("next_offset"))break;offset=page.getInt("next_offset");page=request(target("read_tree_page",first).put("offset",offset).put("limit",128));check("actual Android continuation offset"+offset,!page.has("error")&&sid.equals(page.getString("snapshot_id"))&&page.getLong("expires_in_ms")<=ttl);ttl=page.getLong("expires_in_ms");}
  Set<String> ids=new HashSet<>();JSONObject longNode=null,secret=null;for(int i=0;i<all.length();i++){JSONObject n=all.getJSONObject(i);ids.add(n.getString("node_id"));if(n.optInt("text_length_utf16")>5000)longNode=n;if(n.optBoolean("password"))secret=n;}
  check("all pages cover exactly total nodes without duplicate IDs",ids.size()==total&&all.length()==total);check("password metadata omits content length and hidden strings",secret!=null&&!secret.has("text_length_utf16")&&!secret.toString().contains("PRIVATE_PASSWORD"));
  if(longNode==null){result.putString("nodes",all.toString());}
  check("long field advertises truncation",longNode!=null&&longNode.getBoolean("text_truncated"));StringBuilder text=new StringBuilder();int position=0;
  while(true){JSONObject chunk=request(target("read_node_text",first).put("node_id",longNode.getString("node_id")).put("field","text").put("offset",position).put("length",4096));if(chunk.has("error"))throw new AssertionError(chunk.toString());text.append(chunk.getString("text"));if(chunk.isNull("next_offset_utf16"))break;position=chunk.getInt("next_offset_utf16");}
  check("actual Android long Unicode text reassembles all12000 UTF16 units",PageActivity.LONG.contentEquals(text));
  JSONObject denied=request(target("read_node_text",first).put("node_id",secret.getString("node_id")).put("field","text").put("offset",0).put("length",4096));check("actual Android password read rejected with no text or length",denied.optString("error").equals("sensitive_input")&&denied.length()==1);
  first=tree();allowed.set(false);denied=request(target("read_tree_page",first).put("offset",128).put("limit",128));check("revoked authorization blocks cached page",denied.optString("error").equals("permission_denied"));allowed.set(true);
  first=tree();current.set(false);denied=request(target("read_tree_page",first).put("offset",128).put("limit",128));check("stopped transport blocks cached page",denied.optString("error").equals("permission_denied"));current.set(true);
  first=tree();getContext().sendBroadcast(new Intent("io.airtap.fixture.pages.MUTATE").setPackage("io.airtap.fixture.pages"));Thread.sleep(500);
  denied=request(target("read_node_text",first).put("node_id",longNode.getString("node_id")).put("field","text").put("offset",4096).put("length",4096));check("same-window text suffix mutation rejects original chunk",denied.optString("error").equals("stale_node"));
  first=tree();getContext().sendBroadcast(new Intent("io.airtap.fixture.pages.MUTATE").setPackage("io.airtap.fixture.pages"));Thread.sleep(500);
  denied=request(target("read_tree_page",first).put("offset",128).put("limit",128));check("same-window content mutation rejects cached page",denied.optString("error").equals("fresh_snapshot_required"));
  first=tree();Thread.sleep(30100);denied=request(target("read_tree_page",first).put("offset",128).put("limit",128));check("original30s expiry rejects continuation without renewal",denied.optString("error").equals("fresh_snapshot_required"));
  first=tree();launch(300,"changed");denied=request(target("read_tree_page",first).put("offset",128).put("limit",128));check("changed native tree rejects prior page",denied.has("error"));
  launch(2050,"");denied=tree();check("over2048 native nodes fail explicitly not partial success",denied.optString("error").equals("inspection_limit"));
  result.putString("report",new JSONObject().put("checks",checks).put("complete",true).put("total_nodes",total).put("long_utf16",text.length()).toString());finish(-1,result);
 }catch(Throwable e){result.putString("report",new JSONObject().toString());result.putString("error",android.util.Log.getStackTraceString(e));result.putString("checks",checks.toString());finish(1,result);}}
}
