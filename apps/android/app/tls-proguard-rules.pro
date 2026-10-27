# Android X509TrustManagerExtensions calls these public host-aware methods reflectively.
# Keep their declaring class and names for both debug and release shrinking.
-keep class io.airtap.preview.TlsPolicy$DenylistTrustManager { public *; }
