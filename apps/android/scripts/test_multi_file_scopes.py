#!/usr/bin/env python3
"""Run production scope mutations with host preferences and a synthetic URI permission registry."""
import argparse
import datetime
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / 'app/src/main/java/io/airtap/preview'
SIGNATURES = [
    'private static SharedPreferences prefs(Context c, String owner)',
    'private static Set<String> scopedOwners(Context c)',
    'private static SharedPreferences prefs(Context c)',
    'static long epoch(Context c)',
    'static long scopeEpoch(Context c,String owner)',
    'static String chosenName(Context c, String owner)',
    'static void revoke(Context c)',
    'static void revoke(Context c, String owner)',
    'private static void releaseUnused(Context c, Uri uri, int flags)',
    'static void retain(Context c, Set<String> valid)',
    'static void grant(Context c, String owner, Uri uri, int flags) throws Exception',
    'private static int permissionFlags(Context c, Uri tree)',
    'static boolean quiescent()',
    'private static void exact(JSONObject q, String... names)',
    'private static String text(JSONObject q, String key) throws Exception',
    'private static long integer(JSONObject q, String key, long min, long max) throws Exception',
    'static void validate(JSONObject q) throws Exception',
    'static void onMain(Runnable check)',
    'static void execute(Context context, String owner, JSONObject q, java.util.function.BooleanSupplier shareAuthority, Runnable checkOnMain, CompanionUi.Reply reply) throws Exception',
]
FIELDS = ['MAIN', 'WORKSPACE_IO', 'IN_FLIGHT']


def method(source, signature):
    """Extract an unchanged method, balancing braces outside Java comments and strings."""
    marker = '    ' + signature + ' {'
    if source.count(marker) != 1:
        raise ValueError('Production method must exist exactly once: ' + signature)
    start = source.index(marker)
    opening = start + len(marker) - 1
    depth, mode, escaped = 0, 'code', False
    i = opening
    while i < len(source):
        char, pair = source[i], source[i:i + 2]
        if mode == 'line':
            if char == '\n':
                mode = 'code'
        elif mode == 'block':
            if pair == '*/':
                mode = 'code'
                i += 1
        elif mode in ('"', "'"):
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == mode:
                mode = 'code'
        elif pair in ('//', '/*'):
            mode = 'line' if pair == '//' else 'block'
            i += 1
        elif char in ('"', "'"):
            mode = char
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
        i += 1
    raise ValueError('Unclosed production method: ' + signature)


FAKES = {
    'android/content/Context.java': '''package android.content;
public abstract class Context { public Context getApplicationContext(){return this;} public abstract SharedPreferences getSharedPreferences(String name,int mode); public abstract ContentResolver getContentResolver(); }
''',
    'android/content/SharedPreferences.java': '''package android.content; import java.util.Set;
public interface SharedPreferences {
 String getString(String key,String fallback); Set<String> getStringSet(String key,Set<String> fallback); long getLong(String key,long fallback); Editor edit();
 interface Editor { Editor putString(String key,String value); Editor putStringSet(String key,Set<String> value); Editor putLong(String key,long value); Editor remove(String key); boolean commit(); }
}
''',
    'android/content/ContentResolver.java': '''package android.content; import android.net.Uri; import java.util.List;
public abstract class ContentResolver { public abstract void takePersistableUriPermission(Uri uri,int flags); public abstract void releasePersistableUriPermission(Uri uri,int flags); public abstract List<UriPermission> getPersistedUriPermissions(); }
''',
    'android/content/Intent.java': '''package android.content;
public final class Intent { public static final int FLAG_GRANT_READ_URI_PERMISSION=1,FLAG_GRANT_WRITE_URI_PERMISSION=2; }
''',
    'android/content/UriPermission.java': '''package android.content; import android.net.Uri;
public final class UriPermission { private final Uri uri; private final int flags; public UriPermission(Uri u,int f){uri=u;flags=f;} public Uri getUri(){return uri;} public boolean isReadPermission(){return (flags&1)!=0;} public boolean isWritePermission(){return (flags&2)!=0;} }
''',
    'android/net/Uri.java': '''package android.net;
public final class Uri { private final String value; private Uri(String v){value=v;} public static Uri parse(String v){return new Uri(v);} public String getScheme(){return java.net.URI.create(value).getScheme();} public String toString(){return value;} public boolean equals(Object o){return o instanceof Uri u&&value.equals(u.value);} public int hashCode(){return value.hashCode();} }
''',
    'android/provider/DocumentsContract.java': '''package android.provider; import android.net.Uri;
public final class DocumentsContract { public static boolean isTreeUri(Uri uri){return java.net.URI.create(uri.toString()).getPath().startsWith("/tree/");} }
''',
    'io/airtap/preview/EnrollmentStore.java': '''package io.airtap.preview; import android.content.Context;
final class EnrollmentStore { record Config(){} EnrollmentStore(Context c){} Config read(){return new Config();} }
''',
    'io/airtap/preview/TrustedGrant.java': '''package io.airtap.preview; import android.content.Context; import java.util.Set;
final class TrustedGrant { static Set<String> valid=Set.of(); TrustedGrant(Context c){} Set<String> validClaims(EnrollmentStore.Config config){return Set.copyOf(valid);} org.json.JSONObject grant(EnrollmentStore.Config config,String owner){return valid.contains(owner)?new org.json.JSONObject():null;} }
''',
    'io/airtap/preview/WorkspaceShares.java': '''package io.airtap.preview; import android.content.Context; import java.util.*;
final class WorkspaceShares { static final List<String> revoked=new ArrayList<>(); static void revokeOwner(Context c,String owner){revoked.add(owner);} static void revokeAll(Context c){revoked.add("all");} }
''',
    'android/os/CancellationSignal.java': '''package android.os;
public final class CancellationSignal { private volatile boolean canceled; public void cancel(){canceled=true;} public boolean isCanceled(){return canceled;} }
''',
    'android/os/Looper.java': '''package android.os;
public final class Looper { private static final Looper MAIN=new Looper(); private static final Thread OWNER=Thread.currentThread(); public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return Thread.currentThread()==OWNER?MAIN:null;} }
''',
    'android/os/Handler.java': '''package android.os; import java.util.*;
public final class Handler { private static final List<Runnable> delayed=new ArrayList<>(); public static long lastDelay;
 public Handler(Looper looper){} public boolean post(Runnable task){task.run();return true;}
 public boolean postDelayed(Runnable task,long delay){synchronized(delayed){delayed.add(task);lastDelay=delay;}return true;}
 public void removeCallbacks(Runnable task){synchronized(delayed){delayed.remove(task);}}
 public static void fireTimeouts(){List<Runnable> tasks;synchronized(delayed){tasks=new ArrayList<>(delayed);delayed.clear();}for(Runnable task:tasks)task.run();}
 public static int pending(){synchronized(delayed){return delayed.size();}}
}
''',
    'org/json/JSONObject.java': '''package org.json; import java.util.*;
public final class JSONObject { private final Map<String,Object> values=new LinkedHashMap<>(); public JSONObject put(String key,Object value){values.put(key,value);return this;} public Object get(String key){if(!values.containsKey(key))throw new IllegalArgumentException("missing "+key);return values.get(key);} public boolean has(String key){return values.containsKey(key);} public int length(){return values.size();} }
''',
    'io/airtap/preview/AppCatalog.java': '''package io.airtap.preview;
final class AppCatalog { static boolean allowed(String name){return false;} }
''',
    'io/airtap/preview/ScopedUi.java': '''package io.airtap.preview;
final class ScopedUi { static final class Refused extends RuntimeException { final String code; Refused(String code){super(code);this.code=code;} } }
''',
    'io/airtap/preview/CompanionUi.java': '''package io.airtap.preview; import org.json.JSONObject;
final class CompanionUi { interface Reply {void success(JSONObject result);void error(String code);} }
''',
    'io/airtap/preview/ControlledExecutor.java': '''package io.airtap.preview; import java.util.*; import java.util.concurrent.*;
final class ControlledExecutor implements Executor { static final Queue<Runnable> queued=new ArrayDeque<>(); static boolean reject;
 public void execute(Runnable task){if(reject)throw new RejectedExecutionException("synthetic busy");queued.add(task);}
 static Thread startNext(){Runnable task=queued.remove();Thread thread=new Thread(task,"synthetic-file-provider");thread.start();return thread;}
}
''',
    'io/airtap/preview/HostIo.java': '''package io.airtap.preview; import android.os.CancellationSignal; import java.util.concurrent.*; import org.json.JSONObject;
final class HostIo { static volatile CountDownLatch entered,release; static volatile CancellationSignal signal; static volatile int effects; static volatile boolean block;
 static void reset(){entered=new CountDownLatch(1);release=new CountDownLatch(1);signal=null;effects=0;block=false;}
 static JSONObject perform(String owner,CancellationSignal cancellation)throws Exception{effects++;signal=cancellation;entered.countDown();if(block&&!release.await(5,TimeUnit.SECONDS))throw new AssertionError("provider latch timed out");return new JSONObject().put("owner",owner);}
}
''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    source_path = PRODUCTION / 'FileScopes.java'
    source = source_path.read_text()
    extracted = {signature: method(source, signature) for signature in SIGNATURES}
    fields = {}
    for name in FIELDS:
        matches = [line for line in source.splitlines() if ' ' + name + ' = ' in line]
        if len(matches) != 1 or not matches[0].endswith(';'):
            raise ValueError('Expected one single-line production field: ' + name)
        fields[name] = matches[0]
    # Keep the production run entry fence; substitute only its provider/filesystem work.
    run_signature = 'private static JSONObject run(Context c, String owner, JSONObject q, Runnable fence, CancellationSignal signal, java.util.function.BooleanSupplier shareAuthority) throws Exception'
    run_source = method(source, run_signature)
    entry = run_source.split('{', 1)[1].split(';', 1)[0].strip() + ';'
    if entry != 'fence.run();':
        raise ValueError('Re-evaluate fixture: production run entry fence changed')
    sources = [PRODUCTION / 'FileTransferStore.java', ROOT / 'tests/files/MultiFileScopesTest.java']
    hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in [*sources, Path(__file__)]}
    hashes[str(source_path.relative_to(ROOT))] = hashlib.sha256(source.encode()).hexdigest()
    jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin'
    with tempfile.TemporaryDirectory(prefix='airtap-multi-file-scopes-') as temporary:
        temporary = Path(temporary)
        generated = dict(FAKES)
        generated['io/airtap/preview/FileScopes.java'] = (
            'package io.airtap.preview; import android.content.*; import android.net.Uri; '
            'import android.provider.DocumentsContract; import android.os.*; import java.io.*; '
            'import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.AtomicBoolean; '
            'import org.json.JSONObject;\nfinal class FileScopes {\n'
            + '\n'.join(fields.values()) + '\nprivate static final Executor IO = new ControlledExecutor();\n'
            + '\n'.join(extracted.values()) + '\n' + run_signature + ' { ' + entry
            + ' return HostIo.perform(owner, signal); }\n}\n')
        files = []
        for name, text in generated.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            files.append(path)
        subprocess.run([str(jdk / 'javac'), '--release', '17', '-d', str(temporary),
                        *map(str, sources), *map(str, files)], check=True, timeout=30)
        run = subprocess.run([str(jdk / 'java'), '-cp', str(temporary),
                              'io.airtap.preview.MultiFileScopesTest'], text=True,
                             capture_output=True, timeout=30)
    print(run.stdout, end='')
    if run.returncode:
        print(run.stderr, end='')
        raise SystemExit(run.returncode)
    checks = [line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')]
    assert run.stdout.splitlines()[-1] == 'TOTAL ' + str(len(checks))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({
            'status': 'passed',
            'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'passed': len(checks), 'checks': checks, 'source_sha256': hashes,
            'extracted_method_sha256': {name: hashlib.sha256(text.encode()).hexdigest()
                                        for name, text in extracted.items()},
            'extracted_fields': fields,
            'run_entry_fence': entry,
            'limits': [
                'The listed production FileScopes methods are extracted verbatim; production FileTransferStore compiles unchanged.',
                'Host preferences model named stores, atomic in-memory editor commits, independent persisted snapshots, and failed disk writes. They are not Android SharedPreferences.',
                'ContentResolver uses a synthetic shared per-URI permission registry, not a real DocumentsProvider or native persisted permission.',
                'Fixed-owner execute, onMain, quiescent, workspace lock and in-flight counter are production code. Executor submission and 12000ms timer firing are controlled, and Handler.post runs inline.',
                'Enrollment and valid claim sets are fixtures. The production run entry fence precedes synthetic latched provider work; full run, main-thread affinity, and real provider cancellation are not exercised.',
                'No Android device, emulator, real provider, picker, or physical power-loss durability was tested.',
            ],
        }, indent=2) + '\n')


if __name__ == '__main__':
    main()
