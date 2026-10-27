#!/usr/bin/env python3
"""Compile production transport-terminal/current/disconnected methods with the real recovery state machine.

The transport fake deliberately delays onClosed and main-thread delivery. No Android
device, credentials, network or deployment is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/io/airtap/preview'
service = (SOURCE / 'PreviewService.java').read_text()


def method(signature):
    start = service.index(signature)
    brace = service.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (service[end] == '{') - (service[end] == '}')
        end += 1
    return service[start:end]


methods = '\n'.join(method(signature) for signature in (
    'private boolean valid()', 'private boolean executionAllowed()', 'private boolean current(', 'private void disconnected('))
callbacks = '\n'.join(method(signature) for signature in (
    '@Override public void onClosing(', '@Override public void onClosed(', '@Override public void onFailure('))
marker = next(line for line in service.splitlines() if 'AtomicBoolean transportOpen =' in line)
begin = service.index('        final java.util.concurrent.atomic.AtomicBoolean connectionOpen =')
end = service.index('        socket = client.newWebSocket(', begin)
connection_marker = service[begin:end]

probe = '''package io.airtap.preview;
import java.util.*;
final class PreviewService {
  private WebSocket socket;
  private boolean authorized = true, connected = true,notificationBlocked;
  private long activeCommand = 1;
  private int generation = 1, retry;
  private String state, lastDiagnostic;private RecoveryStatus recoveryStatus;
  private final Random jitter = new Random(0);
  private final Main main = new Main();
  private final Ui ui = new Ui();
  private final Host host = new Host();
  private final ScreenPreparation recovery = new ScreenPreparation(host);
  int stopped, reconnects, executed;
  ''' + marker + '\n' + methods + '''
  // Hooks record the existing service side effects; closure routing above is production code.
  private void stop(String message) { stopped++; authorized = false; activeCommand = 0; generation++; recovery.cancel(); }
  private void scheduleConnect(int run, long delay) { reconnects++; }
  Callback connect(WebSocket ws, int run) {
    socket = ws; generation = run; authorized = true; connected = true; activeCommand++;
''' + connection_marker + '''
    return new Callback() {
''' + callbacks + '''
    };
  }
  void prepare(WebSocket ws, int run) {
    long command = activeCommand;
    recovery.prepare(() -> current(ws, run) && activeCommand == command, new ScreenPreparation.Completion() {
      public void ready(boolean recovered) { executed++; }
      public void error(String code) { throw new AssertionError("Unexpected completion: " + code); }
    });
  }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    PreviewService hidden = new PreviewService(); WebSocket hiddenSocket = new WebSocket();
    hidden.connect(hiddenSocket,1);hidden.prepare(hiddenSocket,1);String hiddenToken=hidden.host.token;
    SessionService.permitted=false;
    check(!hidden.current(hiddenSocket,1)&&!hidden.recovery.permit(hiddenToken),"missing notification immediately fences recovery before watchdog");
    hidden.host.screen=ScreenPreparation.State.READY;hidden.host.advance(10000);
    check(hidden.executed==0&&hidden.authorized&&hidden.stopped==0,"notification loss blocks work without revoking consent");
    SessionService.permitted=true;check(!hidden.current(hiddenSocket,1),"permission return cannot reopen old socket before verified recovery");
    System.out.println("PASS notification_loss_fences_delayed_activity_before_watchdog");

    PreviewService revoked = new PreviewService(); WebSocket a = new WebSocket();
    Callback closeA = revoked.connect(a, 1); revoked.prepare(a, 1); String tokenA = revoked.host.token;
    closeA.onClosing(a, 1008, "revoked");
    check(!revoked.current(a, 1), "received close must invalidate current before main-thread delivery");
    check(!revoked.recovery.permit(tokenA), "late Activity may not wake after close frame");
    revoked.host.screen = ScreenPreparation.State.READY; revoked.host.advance(10000);
    check(revoked.executed == 0, "recovery may not execute while close handshake is pending");
    revoked.main.drain();
    check(revoked.stopped == 1 && !revoked.authorized && revoked.reconnects == 0, "1008 must stop without reconnecting");
    closeA.onClosed(a, 1008, "revoked"); revoked.main.drain();
    check(revoked.stopped == 1 && revoked.executed == 0, "late onClosed may not execute or stop twice");
    System.out.println("PASS revoke_close_fences_delayed_activity_and_completion");

    PreviewService normal = new PreviewService(); WebSocket b = new WebSocket();
    Callback closeB = normal.connect(b, 1); normal.prepare(b, 1);
    closeB.onClosing(b, 1001, "going away");
    check(!normal.current(b, 1), "ordinary close immediately invalidates pending task");
    normal.host.screen = ScreenPreparation.State.READY; normal.host.advance(10000); normal.main.drain();
    check(normal.executed == 0 && normal.activeCommand == 0, "ordinary close cancels preparation without replay");
    check(normal.authorized && normal.stopped == 0 && normal.reconnects == 1, "ordinary close retains pairing and schedules recovery once");
    closeB.onClosed(b, 1001, "going away"); normal.main.drain();
    check(normal.reconnects == 1, "duplicate close completion does not reconnect twice");
    System.out.println("PASS ordinary_close_cancels_before_handshake_finishes");

    PreviewService replacement = new PreviewService(); WebSocket old = new WebSocket(), latest = new WebSocket();
    Callback closeOld = replacement.connect(old, 1), closeLatest = replacement.connect(latest, 2);
    closeOld.onClosing(old, 1008, "late revoked"); replacement.main.drain();
    check(replacement.current(latest, 2) && replacement.stopped == 0, "old listener cannot close new connection");
    replacement.prepare(latest, 2); String latestToken = replacement.host.token;
    closeLatest.onClosing(latest, 1008, "revoked"); closeOld.onClosing(old, 1000, "late again");
    check(!replacement.current(latest, 2) && !replacement.recovery.permit(latestToken), "late old listener cannot reopen closed replacement");
    replacement.host.screen = ScreenPreparation.State.READY; replacement.host.advance(10000); replacement.main.drain();
    check(replacement.stopped == 1 && replacement.executed == 0, "replacement stays revoked");
    System.out.println("PASS stale_socket_callbacks_do_not_change_replacement_gate");

    PreviewService abrupt = new PreviewService(); WebSocket c = new WebSocket();
    Callback closeC = abrupt.connect(c, 1); abrupt.prepare(c, 1); String tokenC = abrupt.host.token;
    closeC.onClosed(c, 1008, "revoked");
    check(!abrupt.current(c, 1) && !abrupt.recovery.permit(tokenC), "onClosed without prior onClosing immediately fences Activity");
    abrupt.host.screen = ScreenPreparation.State.READY; abrupt.host.advance(10000); abrupt.main.drain();
    check(abrupt.executed == 0 && abrupt.stopped == 1, "direct terminal close cannot resume pending operation");
    System.out.println("PASS direct_onClosed_fences_pending_recovery_before_main_delivery");

    PreviewService failed = new PreviewService(); WebSocket d = new WebSocket();
    Callback failedD = failed.connect(d, 1); failed.prepare(d, 1); String tokenD = failed.host.token;
    failedD.onFailure(d, new java.io.IOException("synthetic", new android.system.ErrnoException()), new Response(503));
    check(!failed.current(d, 1) && !failed.recovery.permit(tokenD), "transport failure immediately fences pending Activity");
    failed.host.screen = ScreenPreparation.State.READY; failed.host.advance(10000); failed.main.drain();
    check(failed.executed == 0 && failed.authorized && failed.reconnects == 1 && failed.activeCommand == 0, "ordinary failure retains pairing without replay");
    check(failed.lastDiagnostic.contains("HTTP=503") && failed.lastDiagnostic.contains("errno="), "production failure callback still records bounded diagnostics");
    System.out.println("PASS onFailure_fences_pending_recovery_before_main_delivery");

    PreviewService replaced = new PreviewService(); WebSocket e = new WebSocket(), f = new WebSocket();
    Callback oldFailure = replaced.connect(e, 1), newFailure = replaced.connect(f, 2);
    oldFailure.onFailure(e, new java.io.IOException("old"), new Response(401)); replaced.main.drain();
    check(replaced.current(f, 2) && replaced.stopped == 0, "stale transport failure cannot affect replacement");
    replaced.prepare(f, 2); String tokenF = replaced.host.token;
    newFailure.onFailure(f, new java.io.IOException("current"), new Response(401));
    oldFailure.onClosed(e, 1000, "old");
    check(!replaced.current(f, 2) && !replaced.recovery.permit(tokenF), "current failure remains closed despite stale listener completion");
    replaced.host.screen = ScreenPreparation.State.READY; replaced.host.advance(10000); replaced.main.drain();
    check(replaced.executed == 0 && replaced.stopped == 1 && replaced.reconnects == 0, "terminal failure stops exactly the current grant");
    System.out.println("PASS stale_onFailure_cannot_close_or_reopen_replacement");
  }
  interface Callback { void onClosing(WebSocket ws, int code, String reason); void onClosed(WebSocket ws, int code, String reason); void onFailure(WebSocket ws, Throwable failure, Response response); }
  static final class WebSocket { void close(int code, String reason) {} }
  static final class Response { final int status; Response(int status) { this.status = status; } int code() { return status; } }
  static final class Main { final ArrayDeque<Runnable> callbacks = new ArrayDeque<>(); void post(Runnable callback) { callbacks.add(callback); } void drain() { while (!callbacks.isEmpty()) callbacks.remove().run(); } }
  static final class Ui { void clear() {} void cancelGestures() {} }
  static final class Host implements ScreenPreparation.Platform {
    record Scheduled(long at, Runnable callback) {}
    final List<Scheduled> callbacks = new ArrayList<>();
    ScreenPreparation.State screen = ScreenPreparation.State.SCREEN_OFF;
    long time; String token;
    public ScreenPreparation.State state() { return screen; }
    public long now() { return time; }
    public void later(Runnable callback, long delay) { callbacks.add(new Scheduled(time + delay, callback)); }
    public void remove(Runnable callback) { callbacks.removeIf(item -> item.callback() == callback); }
    public void launch(String value) { token = value; }
    public void close(String value) {}
    public boolean recoveryWindowGone(String value) { return true; }
    public void clearObservations() {}
    void advance(long duration) { long end = time + duration; while (true) { callbacks.sort(Comparator.comparingLong(Scheduled::at)); if (callbacks.isEmpty() || callbacks.get(0).at() > end) break; Scheduled item = callbacks.remove(0); time = item.at(); item.callback().run(); } time = end; }
  }
}
// This fixture exercises ordinary transport failures; TLS classification is covered by test_tls_policy.py.
final class TlsPolicy {static final String USER_MESSAGE="synthetic security failure";static boolean isSecurityFailure(Throwable failure){return false;}}
final class SessionService {static boolean ready=true,permitted=true;static boolean notificationsAllowed(PreviewService service){return permitted;}static boolean readyFor(PreviewService service,int generation){return ready;}}
final class PreviewDiagnostics {
  enum Event { socket_closing, socket_closed, disconnected }
  enum Reason { none }
  static Reason closeReason(String reason) { return Reason.none; }
  static void event(Object... values) {}
  static void failure(Object... values) {}
}
'''

parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path)
args = parser.parse_args()
jdk = Path(os.environ['JAVA_HOME']) / 'bin'
with tempfile.TemporaryDirectory(prefix='airtap-connection-close-') as directory:
    path = Path(directory)
    (path / 'PreviewService.java').write_text(probe)
    errno = path / 'android/system/ErrnoException.java'
    errno.parent.mkdir(parents=True)
    errno.write_text('package android.system; public class ErrnoException extends Exception { public int errno = 104; }')
    subprocess.run([str(jdk / 'javac'), '--release', '17', '-d', directory, str(path / 'PreviewService.java'),
                    str(SOURCE / 'ScreenPreparation.java'), str(SOURCE / 'RecoveryStatus.java'), str(errno)], check=True)
    result = subprocess.run([str(jdk / 'java'), '-cp', directory, 'io.airtap.preview.PreviewService'],
                            capture_output=True, text=True, check=True)
print(result.stdout, end='')
checks = [line[5:] for line in result.stdout.splitlines() if line.startswith('PASS ')]
assert len(checks) == 7
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'passed': len(checks), 'total': 7, 'checks': checks,
        'scope': 'Host JVM. Exact production callback/current/disconnected methods, real ScreenPreparation; fake transport delays close completion and main-thread delivery. Not device validation.',
        'source_sha256': {name: hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
                          for name in ('PreviewService.java', 'ScreenPreparation.java')}}, indent=2) + '\n')
