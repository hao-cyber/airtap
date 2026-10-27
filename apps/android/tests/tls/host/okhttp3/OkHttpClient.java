package okhttp3;
import javax.net.ssl.*;import java.util.*;import java.io.*;
public final class OkHttpClient {
 public interface Rejector {Object intercept(Object chain)throws IOException;}
 public static final class Builder {
  public List<?> specs;public Rejector interceptor;
  public Builder sslSocketFactory(SSLSocketFactory factory,X509TrustManager manager){return this;}
  public Builder connectionSpecs(List<?> values){specs=values;return this;}
  public Builder addInterceptor(Rejector value){interceptor=value;return this;}
 }
}
