package io.airtap.preview;
import java.nio.file.*;import java.net.*;import java.io.*;import java.security.*;import java.security.cert.*;import javax.net.ssl.*;import java.util.*;import java.lang.reflect.*;
public final class TlsPolicyTest {
 static int checks;static X509Certificate clean,bad;
 interface Throwing {void run()throws Exception;}
 static void check(String label,boolean yes){if(!yes)throw new AssertionError(label);checks++;}
 static void refused(String label,String reason,Throwing action)throws Exception{try{action.run();throw new AssertionError(label+" accepted");}catch(CertificateException e){check(label,e.getMessage().contains(reason));}}
 static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static X509Certificate read(String path)throws Exception{try(InputStream in=Files.newInputStream(Path.of(path))){return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}}
 static final class Platform extends X509ExtendedTrustManager implements android.net.http.X509TrustManagerExtensions.VerifiedPath {
  List<X509Certificate> verified;boolean fail;int sockets,engines,paths,clients;String hostname;
  void verify()throws CertificateException{if(fail)throw new CertificateException("platform rejected");}
  public List<X509Certificate> path(X509Certificate[] c,String a,String h)throws CertificateException{paths++;hostname=h;verify();return verified;}
  public void checkServerTrusted(X509Certificate[] c,String a,Socket s)throws CertificateException{sockets++;verify();}
  public void checkServerTrusted(X509Certificate[] c,String a,SSLEngine s)throws CertificateException{engines++;verify();}
  public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{throw new AssertionError("hostless platform path must not be used");}
  public void checkClientTrusted(X509Certificate[] c,String a){clients++;}
  public void checkClientTrusted(X509Certificate[] c,String a,Socket s){clients++;}
  public void checkClientTrusted(X509Certificate[] c,String a,SSLEngine s){clients++;}
  public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[]{clean};}
 }
 static SSLSession session(){return (SSLSession)java.lang.reflect.Proxy.newProxyInstance(TlsPolicyTest.class.getClassLoader(),new Class[]{SSLSession.class},(p,m,a)->m.getName().equals("getPeerHost")?"valid.example":null);}
 static final class SocketContext extends SSLSocket {
  public SSLSession getHandshakeSession(){return session();}public SSLSession getSession(){return session();}
  public String[] getSupportedCipherSuites(){return new String[0];}public String[] getEnabledCipherSuites(){return new String[0];}public void setEnabledCipherSuites(String[] x){}
  public String[] getSupportedProtocols(){return new String[0];}public String[] getEnabledProtocols(){return new String[0];}public void setEnabledProtocols(String[] x){}
  public void addHandshakeCompletedListener(HandshakeCompletedListener l){}public void removeHandshakeCompletedListener(HandshakeCompletedListener l){}public void startHandshake(){}
  public void setUseClientMode(boolean x){}public boolean getUseClientMode(){return true;}public void setNeedClientAuth(boolean x){}public boolean getNeedClientAuth(){return false;}public void setWantClientAuth(boolean x){}public boolean getWantClientAuth(){return false;}public void setEnableSessionCreation(boolean x){}public boolean getEnableSessionCreation(){return true;}
 }
 public static void main(String[] args)throws Exception{
  Set<String> observed=new HashSet<>();
  for(String line:Files.readAllLines(Path.of(args[0]))){String[] row=line.split("\\t");X509Certificate c=read(row[0]);check("fixture DER digest",hash(c.getEncoded()).equals(row[1]));check("fixture SPKI digest",hash(c.getPublicKey().getEncoded()).equals(row[2]));observed.add(row[2]);refused("actual compromised certificate including cross-sign","compromised",()->CompromisedKeys.check(List.of(c)));bad=c;}
  check("exactly all eight affected keys",observed.size()==8&&observed.equals(CompromisedKeys.SHA256));
  clean=read(args[1]);CompromisedKeys.check(List.of(clean));check("uncompromised root remains allowed by additional policy",true);
  refused("empty verified chain","unavailable",()->CompromisedKeys.check(List.of()));refused("null verified chain","unavailable",()->CompromisedKeys.check(null));
  Platform platform=new Platform();platform.verified=List.of(clean);TlsPolicy.DenylistTrustManager manager=new TlsPolicy.DenylistTrustManager(platform);X509Certificate[] peer={clean};
  check("clean verified chain returned",manager.checkServerTrusted(peer,"EC","valid.example").equals(List.of(clean)));
  platform.verified=List.of(clean,bad);refused("cached intermediate absent from presented chain rejected","compromised",()->manager.checkServerTrusted(peer,"EC","valid.example"));
  platform.verified=List.of(clean);refused("affected presented certificate cannot be hidden by alternative path","compromised",()->manager.checkServerTrusted(new X509Certificate[]{bad},"EC","valid.example"));
  platform.fail=true;refused("system trust failure retained","platform rejected",()->manager.checkServerTrusted(peer,"EC","valid.example"));int before=platform.paths;
  refused("socket context system failure occurs before path rebuilding","platform rejected",()->manager.checkServerTrusted(peer,"EC",new SocketContext()));check("socket failure did not skip platform",platform.sockets==1&&platform.paths==before);
  platform.fail=false;manager.checkServerTrusted(peer,"EC",new SocketContext());check("socket context delegates and verifies host-aware actual path",platform.sockets==2&&platform.paths==before+1&&platform.hostname.equals("valid.example"));
  platform.verified=List.of(clean,bad);refused("socket rejects omitted cached compromised intermediate","compromised",()->manager.checkServerTrusted(peer,"EC",new SocketContext()));
  platform.fail=true;SSLEngine engine=SSLContext.getDefault().createSSLEngine("valid.example",443);refused("engine context system failure retained","platform rejected",()->manager.checkServerTrusted(peer,"EC",engine));check("engine delegates system validation",platform.engines==1);
  platform.fail=false;refused("engine missing handshake context fails closed","hostname context",()->manager.checkServerTrusted(peer,"EC",engine));
  refused("plain socket rejected","socket context",()->manager.checkServerTrusted(peer,"EC",new Socket()));
  refused("hostless overload rejected","hostname context",()->manager.checkServerTrusted(peer,"EC"));refused("empty hostname rejected","hostname context",()->manager.checkServerTrusted(peer,"EC",""));
  manager.checkClientTrusted(peer,"EC");manager.checkClientTrusted(peer,"EC",new Socket());manager.checkClientTrusted(peer,"EC",engine);check("client checks and anchors unchanged",platform.clients==3&&Arrays.equals(manager.getAcceptedIssuers(),peer));
  check("host-aware extension stays reflectively public",Modifier.isPublic(manager.getClass().getMethod("checkServerTrusted",X509Certificate[].class,String.class,String.class).getModifiers()));
  check("same trust configuration delegated",manager.isSameTrustConfiguration("a","a")&&!manager.isSameTrustConfiguration("a","b"));
  // Host stub deliberately cannot wrap the JDK manager: initialization must keep UI usable
  // while returning a builder with no transport and an unconditional pre-network failure.
  okhttp3.OkHttpClient.Builder fallback=TlsPolicy.newClientBuilder();check("initialization failure leaves zero connection specs",fallback.specs!=null&&fallback.specs.isEmpty());
  boolean denied=false;try{fallback.interceptor.intercept(null);}catch(IOException failure){denied=failure.getMessage().equals("Airtap TLS policy unavailable");}check("initialization failure denies every request before network",denied);
  check("security error classification follows exception types only",TlsPolicy.isSecurityFailure(new IOException("wrapper",new CertificateException("private details")))&&TlsPolicy.isSecurityFailure(new SSLHandshakeException("private details"))&&TlsPolicy.isSecurityFailure(new SSLPeerUnverifiedException("private hostname"))&&!TlsPolicy.isSecurityFailure(new IOException("TLS compromised intermediate key rejected"))&&!TlsPolicy.isSecurityFailure(new java.net.SocketTimeoutException()));
  try{fallback.interceptor.intercept(null);}catch(IOException failure){check("fixed unavailable exception is recognizable",TlsPolicy.isSecurityFailure(failure));}
  check("owner-facing message does not expose error text or recommend re-pairing",TlsPolicy.USER_MESSAGE.equals("无法验证服务的安全连接，请检查 Airtap 或系统更新"));
  System.out.println("TLS host checks passed: "+checks);
 }
}
