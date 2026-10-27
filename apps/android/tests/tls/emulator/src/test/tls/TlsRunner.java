package test.tls;
import android.app.Instrumentation;import android.os.Bundle;import java.lang.reflect.Method;import java.security.MessageDigest;import java.security.cert.X509Certificate;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;import javax.net.ssl.*;import java.net.Socket;import java.security.cert.*;import java.io.*;import okhttp3.*;import org.json.*;
/** Makes only public, credential-free requests; never reads Airtap pairing or app preferences. */
public final class TlsRunner extends Instrumentation {
 final JSONArray checks=new JSONArray(),requests=new JSONArray();boolean offlineOnly;
 @Override public void onCreate(Bundle b){super.onCreate(b);offlineOnly="true".equals(b.getString("offline_only"));start();}
 void check(String name,boolean ok)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",ok));if(!ok)throw new AssertionError(name);}
 static String digest(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
 static JSONArray failures(Throwable t)throws Exception{JSONArray a=new JSONArray();for(int i=0;t!=null&&i<10;i++,t=t.getCause())a.put(new JSONObject().put("class",t.getClass().getName()).put("message",String.valueOf(t.getMessage()).split("\n",2)[0]));return a;}
 JSONObject request(OkHttpClient client,String url,String mode)throws Exception{
  JSONObject result=new JSONObject().put("url",url).put("mode",mode);long start=System.nanoTime();
  CountDownLatch finished=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
  Call call=client.newCall(new Request.Builder().url(url).header("User-Agent","Airtap-TLS-Validation/1").get().build());
  call.enqueue(new Callback(){public void onFailure(Call c,java.io.IOException e){error.set(e);finished.countDown();}public void onResponse(Call c,Response r){try(r){result.put("accepted",true).put("http_status",r.code());}catch(Throwable e){error.set(e);}finally{finished.countDown();}}});
  if(!finished.await(20,TimeUnit.SECONDS)){call.cancel();error.set(new java.io.IOException("probe timeout"));}
  if(error.get()!=null){Throwable e=error.get();result.put("accepted",false).put("tls_rejected",e instanceof SSLException).put("failures",failures(e));}
  result.put("elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));requests.put(result);return result;
 }
 public static final class PathProvider extends X509ExtendedTrustManager {
  List<X509Certificate> verified;boolean fail;public PathProvider(List<X509Certificate> path){verified=path;}
  public List<X509Certificate> checkServerTrusted(X509Certificate[] c,String auth,String host)throws CertificateException{if(fail)throw new CertificateException("synthetic platform rejection");return verified;}
  public boolean isSameTrustConfiguration(String a,String b){return a.equals(b);}
  public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("hostless synthetic provider");}
  public void checkServerTrusted(X509Certificate[] c,String a,Socket socket){}public void checkServerTrusted(X509Certificate[] c,String a,SSLEngine engine){}
  public void checkClientTrusted(X509Certificate[] c,String a){}public void checkClientTrusted(X509Certificate[] c,String a,Socket socket){}public void checkClientTrusted(X509Certificate[] c,String a,SSLEngine engine){}
  public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
 }
 X509Certificate certificate(String pem)throws Exception{return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}
 JSONArray asset(String name)throws Exception{try(InputStream in=getContext().getAssets().open(name)){return new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}
 void nativeOffline()throws Exception{
  Method deny=Class.forName("io.airtap.preview.CompromisedKeys").getDeclaredMethod("check",List.class);deny.setAccessible(true);JSONArray metadata=asset("impacted-intermediates.json");Set<String> keys=new HashSet<>();X509Certificate bad=null;
  for(int i=0;i<metadata.length();i++){JSONObject row=metadata.getJSONObject(i);X509Certificate cert=certificate(row.getString("pem"));String spki=digest(cert.getPublicKey().getEncoded());keys.add(spki);check("native certificate and SPKI metadata agree "+i,digest(cert.getEncoded()).equalsIgnoreCase(row.getString("sha256"))&&spki.equalsIgnoreCase(row.getString("spki_sha256")));
   boolean rejected=false;try{deny.invoke(null,List.of(cert));}catch(java.lang.reflect.InvocationTargetException e){rejected=e.getCause() instanceof CertificateException&&e.getCause().getMessage().contains("compromised intermediate");}check("actual Android policy rejects public certificate "+i,rejected);bad=cert;}
  check("native certificate fixture contains all eight keys",keys.size()==8);X509Certificate clean=certificate(asset("roots.json").getJSONObject(0).getString("pem"));deny.invoke(null,List.of(clean));check("unaffected root is not rejected by additive policy",true);
  Class<?> policy=Class.forName("io.airtap.preview.TlsPolicy$DenylistTrustManager");java.lang.reflect.Constructor<?> ctor=policy.getDeclaredConstructor(X509ExtendedTrustManager.class);ctor.setAccessible(true);PathProvider provider=new PathProvider(List.of(clean,bad));Object wrapper=ctor.newInstance(provider);Method host=policy.getMethod("checkServerTrusted",X509Certificate[].class,String.class,String.class);
  boolean cachedRejected=false;try{host.invoke(wrapper,new X509Certificate[]{clean},"EC","valid.example");}catch(java.lang.reflect.InvocationTargetException e){cachedRejected=e.getCause() instanceof CertificateException&&e.getCause().getMessage().contains("compromised intermediate");}check("real Android extensions return cached intermediate and wrapper rejects it",cachedRejected);
  provider.verified=List.of(clean);check("real Android extensions preserve clean verified path",((List<?>)host.invoke(wrapper,new X509Certificate[]{clean},"EC","valid.example")).size()==1);provider.fail=true;
  boolean trustFailure=false;try{host.invoke(wrapper,new X509Certificate[]{clean},"EC","valid.example");}catch(java.lang.reflect.InvocationTargetException e){trustFailure=e.getCause() instanceof CertificateException&&e.getCause().getMessage().contains("synthetic platform rejection");}check("real Android extensions propagate underlying trust rejection",trustFailure);
 }
 @Override public void onStart(){Bundle result=new Bundle();OkHttpClient base=null,protectedClient=null;try{
  nativeOffline();
  Method unavailable=Class.forName("io.airtap.preview.TlsPolicy").getDeclaredMethod("unavailableClientBuilder");unavailable.setAccessible(true);OkHttpClient deniedClient=((OkHttpClient.Builder)unavailable.invoke(null)).callTimeout(3,TimeUnit.SECONDS).build();
  for(String scheme:new String[]{"https","http"}){JSONObject denied=request(deniedClient,scheme+"://airtap-tls-probe.invalid/","forced_policy_unavailable");check("unavailable policy fails before DNS and network: "+scheme,!denied.getBoolean("accepted")&&denied.toString().contains("Airtap TLS policy unavailable")&&!denied.toString().contains("UnknownHostException"));}
  CountDownLatch deniedDone=new CountDownLatch(1);AtomicReference<Throwable> deniedFailure=new AtomicReference<>();WebSocket deniedWs=deniedClient.newWebSocket(new Request.Builder().url("wss://airtap-tls-probe.invalid/").build(),new WebSocketListener(){@Override public void onFailure(WebSocket ws,Throwable t,Response r){deniedFailure.set(t);deniedDone.countDown();}});
  check("unavailable policy rejects WebSocket before DNS/network",deniedDone.await(5,TimeUnit.SECONDS)&&deniedFailure.get()!=null&&"Airtap TLS policy unavailable".equals(deniedFailure.get().getMessage()));deniedWs.cancel();deniedClient.connectionPool().evictAll();deniedClient.dispatcher().executorService().shutdown();
  if(offlineOnly){result.putString("report",new JSONObject().put("complete",true).put("checks",checks).put("requests",requests).put("android_sdk",android.os.Build.VERSION.SDK_INT).put("android_release",android.os.Build.VERSION.RELEASE).put("security_patch",android.os.Build.VERSION.SECURITY_PATCH).put("credentials_sent",false).put("offline_only",true).toString());finish(-1,result);return;}
  base=new OkHttpClient.Builder().followRedirects(false).callTimeout(12,TimeUnit.SECONDS).build();
  Method method=Class.forName("io.airtap.preview.TlsPolicy").getDeclaredMethod("newClientBuilder");method.setAccessible(true);
  protectedClient=((OkHttpClient.Builder)method.invoke(null)).followRedirects(false).callTimeout(12,TimeUnit.SECONDS).build();
  for(String url:new String[]{"https://airtap.memtrix.dev/updates/android.json","https://example.com/"}){JSONObject r=request(protectedClient,url,"airtap_policy");check("normal certificate succeeds: "+url,r.getBoolean("accepted")&&r.getInt("http_status")==200);}
  String[] prefixes={"dv-g3b","dv-g3b-aaa","dv-g3b-noct","dv-g3b-aaa-noct","dv-g3c","dv-g3c-aaa","ev-g3b","ev-g3b-aaa","ev-g3c","ev-g3c-aaa","ov-g3b","ov-g3b-aaa","ov-g3c","ov-g3c-aaa"};
  for(String prefix:prefixes){String url="https://"+prefix+"-emudhra.we-have-a-problem.com/";
   JSONObject before=request(base,url,"android_default");check("baseline produced a TLS result: "+prefix,before.getBoolean("accepted")||before.optBoolean("tls_rejected"));
   JSONObject after=request(protectedClient,url,"airtap_policy");check("affected path explicitly rejected: "+prefix,!after.getBoolean("accepted")&&after.optBoolean("tls_rejected"));
   if(before.getBoolean("accepted"))check("new policy caused rejection: "+prefix,after.toString().contains("compromised intermediate key rejected"));
  }
  for(String host:new String[]{"wrong.host.badssl.com","expired.badssl.com","self-signed.badssl.com"}){JSONObject r=request(protectedClient,"https://"+host+"/","airtap_policy");check("existing system/hostname validation preserved: "+host,!r.getBoolean("accepted")&&r.optBoolean("tls_rejected")&&!r.toString().contains("compromised intermediate key rejected"));}
  CountDownLatch latch=new CountDownLatch(1);AtomicReference<Throwable> wsFailure=new AtomicReference<>();
  WebSocket ws=protectedClient.newWebSocket(new Request.Builder().url("wss://dv-g3b-aaa-noct-emudhra.we-have-a-problem.com/").build(),new WebSocketListener(){@Override public void onFailure(WebSocket s,Throwable t,Response r){wsFailure.set(t);latch.countDown();}@Override public void onOpen(WebSocket s,Response r){s.cancel();latch.countDown();}});
  boolean completed=latch.await(15,TimeUnit.SECONDS);ws.cancel();check("WebSocket handshake uses the same TLS policy",completed&&wsFailure.get() instanceof SSLException);requests.put(new JSONObject().put("mode","airtap_policy_websocket").put("failures",failures(wsFailure.get())));
  result.putString("report",new JSONObject().put("complete",true).put("checks",checks).put("requests",requests).put("android_sdk",android.os.Build.VERSION.SDK_INT).put("android_release",android.os.Build.VERSION.RELEASE).put("security_patch",android.os.Build.VERSION.SECURITY_PATCH).put("credentials_sent",false).toString());finish(-1,result);
 }catch(Throwable t){result.putString("error",android.util.Log.getStackTraceString(t));result.putString("report",new JSONObject(java.util.Map.of("checks",checks,"requests",requests)).toString());finish(1,result);}finally{for(OkHttpClient c:new OkHttpClient[]{base,protectedClient})if(c!=null){c.connectionPool().evictAll();c.dispatcher().executorService().shutdown();}}}
}
