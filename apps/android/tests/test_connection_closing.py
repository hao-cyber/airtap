#!/usr/bin/env python3
"""Compile production connection lifecycle methods with the real recovery state machine.

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
    'private boolean valid()', 'private boolean executionAllowed()', 'private boolean current(',
    'private void connect(', 'private void awaitHandshake(', 'private void cancelHandshake(',
    'private void disconnected(', 'private void scheduleConnect(', 'private void resetForNetwork(',
    'void stop(', 'private void suspend('))
marker = next(line for line in service.splitlines() if 'AtomicBoolean transportOpen =' in line)
deadline = next(line for line in service.splitlines() if 'static final long HANDSHAKE_TIMEOUT_MS =' in line)

probe = '''package io.airtap.preview;
import java.util.*;
import java.nio.charset.StandardCharsets;
final class PreviewService {
  private WebSocket socket;
  private boolean authorized = true, connected,notificationBlocked,restoring;
  private long activeCommand = 1;
  private int generation = 1, retry,connections,attempts,notificationFailures;
  private String state, lastDiagnostic,url="wss://synthetic.invalid",token="synthetic";private RecoveryStatus recoveryStatus;
  private Runnable reconnectTask,handshakeTask;
  private ConnectivityManager.NetworkCallback networkCallback;
  private Network currentNetwork;
  private final ConnectivityManager network=new ConnectivityManager();
  private final Client client=new Client();
  private final Random jitter = new Random(0);
  private final Main main = new Main();
  private final Ui ui = new Ui();
  private final Host host = new Host();
  private final ScreenPreparation recovery = new ScreenPreparation(host);
  int stopped, reconnects, executed;
  ''' + marker + '\n' + deadline + '\n' + methods + '''
  <T>T getSystemService(Class<T> type){return type.cast(network);}
  private void command(WebSocket ws,int run,String text){executed++;}
  // Existing closure cases begin with a transport that has completed its actual onOpen callback.
  WebSocketListener connect(WebSocket ws, int run) {
    socket=null; connected=false; generation=run; authorized=true; client.next=ws; activeCommand++;
    connect(run); ws.listener.onOpen(ws,new Response(101));main.drain();return ws.listener;
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
    WebSocketListener closeA = revoked.connect(a, 1); revoked.prepare(a, 1); String tokenA = revoked.host.token;
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
    WebSocketListener closeB = normal.connect(b, 1); normal.prepare(b, 1);
    closeB.onClosing(b, 1001, "going away");
    check(!normal.current(b, 1), "ordinary close immediately invalidates pending task");
    normal.host.screen = ScreenPreparation.State.READY; normal.host.advance(10000); normal.main.drain();
    check(normal.executed == 0 && normal.activeCommand == 0, "ordinary close cancels preparation without replay");
    check(normal.authorized && normal.stopped == 0 && normal.reconnects == 1, "ordinary close retains pairing and schedules recovery once");
    closeB.onClosed(b, 1001, "going away"); normal.main.drain();
    check(normal.reconnects == 1, "duplicate close completion does not reconnect twice");
    System.out.println("PASS ordinary_close_cancels_before_handshake_finishes");

    PreviewService replacement = new PreviewService(); WebSocket old = new WebSocket(), latest = new WebSocket();
    WebSocketListener closeOld = replacement.connect(old, 1), closeLatest = replacement.connect(latest, 2);
    closeOld.onClosing(old, 1008, "late revoked"); replacement.main.drain();
    check(replacement.current(latest, 2) && replacement.stopped == 0, "old listener cannot close new connection");
    replacement.prepare(latest, 2); String latestToken = replacement.host.token;
    closeLatest.onClosing(latest, 1008, "revoked"); closeOld.onClosing(old, 1000, "late again");
    check(!replacement.current(latest, 2) && !replacement.recovery.permit(latestToken), "late old listener cannot reopen closed replacement");
    replacement.host.screen = ScreenPreparation.State.READY; replacement.host.advance(10000); replacement.main.drain();
    check(replacement.stopped == 1 && replacement.executed == 0, "replacement stays revoked");
    System.out.println("PASS stale_socket_callbacks_do_not_change_replacement_gate");

    PreviewService abrupt = new PreviewService(); WebSocket c = new WebSocket();
    WebSocketListener closeC = abrupt.connect(c, 1); abrupt.prepare(c, 1); String tokenC = abrupt.host.token;
    closeC.onClosed(c, 1008, "revoked");
    check(!abrupt.current(c, 1) && !abrupt.recovery.permit(tokenC), "onClosed without prior onClosing immediately fences Activity");
    abrupt.host.screen = ScreenPreparation.State.READY; abrupt.host.advance(10000); abrupt.main.drain();
    check(abrupt.executed == 0 && abrupt.stopped == 1, "direct terminal close cannot resume pending operation");
    System.out.println("PASS direct_onClosed_fences_pending_recovery_before_main_delivery");

    PreviewService failed = new PreviewService(); WebSocket d = new WebSocket();
    WebSocketListener failedD = failed.connect(d, 1); failed.prepare(d, 1); String tokenD = failed.host.token;
    failedD.onFailure(d, new java.io.IOException("synthetic", new android.system.ErrnoException()), new Response(503));
    check(!failed.current(d, 1) && !failed.recovery.permit(tokenD), "transport failure immediately fences pending Activity");
    failed.host.screen = ScreenPreparation.State.READY; failed.host.advance(10000); failed.main.drain();
    check(failed.executed == 0 && failed.authorized && failed.reconnects == 1 && failed.activeCommand == 0, "ordinary failure retains pairing without replay");
    check(failed.lastDiagnostic.contains("HTTP=503") && failed.lastDiagnostic.contains("errno="), "production failure callback still records bounded diagnostics");
    System.out.println("PASS onFailure_fences_pending_recovery_before_main_delivery");

    PreviewService replaced = new PreviewService(); WebSocket e = new WebSocket(), f = new WebSocket();
    WebSocketListener oldFailure = replaced.connect(e, 1), newFailure = replaced.connect(f, 2);
    oldFailure.onFailure(e, new java.io.IOException("old"), new Response(401)); replaced.main.drain();
    check(replaced.current(f, 2) && replaced.stopped == 0, "stale transport failure cannot affect replacement");
    replaced.prepare(f, 2); String tokenF = replaced.host.token;
    newFailure.onFailure(f, new java.io.IOException("current"), new Response(401));
    oldFailure.onClosed(e, 1000, "old");
    check(!replaced.current(f, 2) && !replaced.recovery.permit(tokenF), "current failure remains closed despite stale listener completion");
    replaced.host.screen = ScreenPreparation.State.READY; replaced.host.advance(10000); replaced.main.drain();
    check(replaced.executed == 0 && replaced.stopped == 1 && replaced.reconnects == 0, "terminal failure stops exactly the current grant");
    System.out.println("PASS stale_onFailure_cannot_close_or_reopen_replacement");

    PreviewService stalled=new PreviewService();stalled.connect(1);WebSocket stalledSocket=stalled.socket;
    stalled.main.advance(HANDSHAKE_TIMEOUT_MS-1);
    check(stalled.socket==stalledSocket&&stalledSocket.cancels==0&&stalled.attempts==1,"handshake remains pending within deadline");
    stalled.main.advance(1);
    check(stalled.socket==null&&stalledSocket.cancels==1&&stalled.authorized&&!stalled.connected,
        "missing DNS/TLS/upgrade callbacks cannot hold socket indefinitely or remove pairing");
    check(stalled.reconnects==1&&stalled.retry==1&&stalled.activeCommand==0&&stalled.executed==0,
        "timeout uses ordinary retry and cancels work without replay");
    check(stalled.lastDiagnostic.contains("handshake_timeout"),"timeout has bounded diagnostic");
    stalled.main.advance(1250);
    check(stalled.attempts==2&&stalled.socket!=stalledSocket&&stalled.retry==1,"retry starts without a network change and keeps backoff");
    stalled.main.advance(HANDSHAKE_TIMEOUT_MS);
    check(stalled.reconnects==2&&stalled.retry==2,"repeated stalled handshake increases existing backoff");
    System.out.println("PASS stalled_handshake_times_out_and_retries_without_network_event");

    PreviewService opened=new PreviewService();opened.connect(1);WebSocket openSocket=opened.socket;Runnable openDeadline=opened.handshakeTask;
    opened.main.advance(HANDSHAKE_TIMEOUT_MS-1);openSocket.listener.onOpen(openSocket,new Response(101));opened.main.drain();
    check(opened.connected&&opened.connections==1&&opened.handshakeTask==null&&opened.retry==0&&openSocket.sends==1,
        "successful onOpen cancels deadline, resets retry and sends hello once");
    opened.main.advance(120000);openDeadline.run();
    check(opened.socket==openSocket&&openSocket.cancels==0&&opened.reconnects==0,"open connection is not closed by a stale deadline");
    System.out.println("PASS successful_handshake_cancels_deadline");

    PreviewService late=new PreviewService();late.connect(1);WebSocket expired=late.socket;Runnable expiredDeadline=late.handshakeTask;
    late.main.advance(HANDSHAKE_TIMEOUT_MS+1250);WebSocket renewed=late.socket;Runnable renewedDeadline=late.handshakeTask;
    check(renewed!=expired&&renewed!=null,"timeout must start replacement in same generation");
    expired.listener.onOpen(expired,new Response(101));expired.listener.onFailure(expired,new java.io.IOException("late"),new Response(401));
    late.main.drain();expiredDeadline.run();
    check(late.socket==renewed&&late.handshakeTask==renewedDeadline&&!late.connected&&late.stopped==0,
        "late open/failure/timer cannot disturb same-generation replacement or cancel its deadline");
    renewed.listener.onOpen(renewed,new Response(101));late.main.drain();
    check(late.current(renewed,1)&&late.connections==1&&late.retry==0&&expired.sends==0,
        "expired transport cannot send hello or revive execution; replacement opens normally");
    System.out.println("PASS late_handshake_callbacks_cannot_revive_or_close_replacement");

    PreviewService ownerStop=new PreviewService();ownerStop.connect(1);WebSocket stoppedSocket=ownerStop.socket;Runnable stoppedDeadline=ownerStop.handshakeTask;
    ownerStop.stop("synthetic owner stop");stoppedDeadline.run();stoppedSocket.listener.onOpen(stoppedSocket,new Response(101));
    stoppedSocket.listener.onFailure(stoppedSocket,new java.io.IOException("late"),new Response(503));ownerStop.main.advance(120000);
    check(!ownerStop.authorized&&ownerStop.socket==null&&ownerStop.handshakeTask==null&&ownerStop.reconnects==0&&ownerStop.attempts==1&&ownerStop.connections==0,
        "owner stop removes deadline and fences any already queued open/failure/timeout");
    check(ownerStop.stopped==1&&stoppedSocket.sends==0,"stopped transport never sends hello");
    System.out.println("PASS owner_stop_fences_pending_handshake_and_all_late_callbacks");

    for(int deniedStatus:new int[]{401,403,410,1008}){
      PreviewService denied=new PreviewService();denied.connect(1);WebSocket deniedSocket=denied.socket;Runnable deniedDeadline=denied.handshakeTask;
      if(deniedStatus==1008)deniedSocket.listener.onClosing(deniedSocket,deniedStatus,"revoked");
      else deniedSocket.listener.onFailure(deniedSocket,new java.io.IOException("denied"),new Response(deniedStatus));
      denied.main.drain();deniedDeadline.run();deniedSocket.listener.onOpen(deniedSocket,new Response(101));denied.main.advance(120000);
      check(!denied.authorized&&denied.stopped==1&&denied.reconnects==0&&denied.socket==null&&deniedSocket.sends==0,
          "terminal authorization failure cannot become timeout retry: "+deniedStatus);
    }
    System.out.println("PASS revoke_and_authentication_failures_cancel_handshake_without_retry");

    PreviewService changed=new PreviewService();changed.connect(1);WebSocket oldNetworkSocket=changed.socket;Runnable oldNetworkDeadline=changed.handshakeTask;
    changed.resetForNetwork(1);check(changed.handshakeTask==null&&oldNetworkSocket.cancels==1,"network reset cancels deadline and transport");
    changed.main.advance(500);WebSocket newNetworkSocket=changed.socket;Runnable newNetworkDeadline=changed.handshakeTask;
    oldNetworkDeadline.run();oldNetworkSocket.listener.onFailure(oldNetworkSocket,new java.io.IOException("old network"),null);changed.main.drain();
    check(changed.socket==newNetworkSocket&&changed.handshakeTask==newNetworkDeadline&&changed.attempts==2,
        "network replacement keeps its own deadline despite old timer/failure");
    newNetworkSocket.listener.onOpen(newNetworkSocket,new Response(101));changed.main.advance(120000);
    check(changed.connected&&newNetworkSocket.cancels==0,"new network handshake can finish normally");
    System.out.println("PASS network_change_cancels_only_old_handshake_deadline");

    PreviewService earlyFailure=new PreviewService();earlyFailure.connect(1);WebSocket earlySocket=earlyFailure.socket;Runnable earlyDeadline=earlyFailure.handshakeTask;
    earlySocket.listener.onFailure(earlySocket,new java.io.IOException("synthetic"),new Response(503));earlyFailure.main.drain();earlyDeadline.run();
    check(earlyFailure.handshakeTask==null&&earlyFailure.authorized&&earlyFailure.reconnects==1&&earlyFailure.retry==1,
        "ordinary failed handshake cancels deadline and schedules one retry");
    earlyFailure.main.advance(1250);check(earlyFailure.attempts==2,"failure retry starts once");
    System.out.println("PASS failed_handshake_cancels_deadline_without_duplicate_retry");

    PreviewService offline=new PreviewService();offline.network.capabilities.validated=false;offline.connect(1);offline.main.advance(6000);
    check(offline.attempts==0&&offline.handshakeTask==null&&offline.socket==null,"no handshake timer before validated network exists");
    offline.network.capabilities.validated=true;offline.main.advance(3000);
    check(offline.attempts==1&&offline.handshakeTask!=null,"validated network starts a bounded handshake");
    System.out.println("PASS handshake_deadline_starts_only_for_actual_connection_attempt");
  }
  static class WebSocketListener {
    public void onOpen(WebSocket ws,Response response){}public void onMessage(WebSocket ws,String text){}public void onMessage(WebSocket ws,ByteString bytes){}
    public void onClosing(WebSocket ws,int code,String reason){}public void onClosed(WebSocket ws,int code,String reason){}
    public void onFailure(WebSocket ws,Throwable failure,Response response){}
  }
  static final class WebSocket {WebSocketListener listener;int cancels,sends;void close(int code,String reason){}void cancel(){cancels++;}void send(String value){sends++;}}
  static final class Client {WebSocket next;WebSocket newWebSocket(Request request,WebSocketListener listener){WebSocket ws=next==null?new WebSocket():next;next=null;ws.listener=listener;return ws;}}
  static final class Request {static final class Builder {Builder url(String value){return this;}Builder header(String name,String value){return this;}Request build(){return new Request();}}}
  static final class ByteString {}
  static final class Response { final int status; Response(int status) { this.status = status; } int code() { return status; } }
  static final class Main {
    record Scheduled(long at,long serial,Runnable run){}long time,serial;
    final PriorityQueue<Scheduled> queue=new PriorityQueue<>(Comparator.comparingLong(Scheduled::at).thenComparingLong(Scheduled::serial));
    void post(Runnable callback){postDelayed(callback,0);}void postDelayed(Runnable callback,long delay){queue.add(new Scheduled(time+delay,serial++,callback));}
    void removeCallbacks(Runnable callback){queue.removeIf(item->item.run()==callback);}void removeCallbacksAndMessages(Object unused){queue.clear();}
    void drain(){advance(0);}void advance(long duration){long end=time+duration;while(!queue.isEmpty()&&queue.peek().at()<=end){Scheduled item=queue.remove();time=item.at();item.run().run();}time=end;}
  }
  static final class Network {}
  static final class NetworkCapabilities {static final int NET_CAPABILITY_VALIDATED=1;boolean validated=true;boolean hasCapability(int capability){return validated;}}
  static final class ConnectivityManager {
    static final class NetworkCallback{}final Network active=new Network();final NetworkCapabilities capabilities=new NetworkCapabilities();
    Network getActiveNetwork(){return active;}NetworkCapabilities getNetworkCapabilities(Network network){return capabilities;}void unregisterNetworkCallback(NetworkCallback callback){}
  }
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
final class SessionService {static boolean ready=true,permitted=true;static boolean notificationsAllowed(PreviewService service){return permitted;}static boolean readyFor(PreviewService service,int generation){return ready;}static void end(PreviewService service){}}
final class TrustedGrant {final PreviewService owner;TrustedGrant(PreviewService service){owner=service;}void stop(){owner.stopped++;}}
final class PreviewState {static boolean grantAvailable;}
final class WorkspaceShares {static void revokeAll(PreviewService service){}}
final class PreviewDiagnostics {
  enum Event { socket_closing,socket_closed,disconnected,connect_wait_network,connect_attempt,socket_cancel,socket_open,socket_close_requested,handshake_timeout,reconnect_scheduled,network_reset }
  enum Reason { none,stale_callback,request_too_large,text_requests_only,timeout,network_change }
  static Reason closeReason(String reason) { return Reason.none; }
  static void event(Object... values) {if(values[1]==Event.reconnect_scheduled)((PreviewService)values[0]).reconnects++;}
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
assert len(checks) == 15
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'passed': len(checks), 'total': 15, 'checks': checks,
        'scope': 'Host JVM. Exact production connect/callback/deadline/current/disconnected/backoff/stop/suspend/network-reset methods, real ScreenPreparation; fake transport can withhold handshake callbacks and delay main-thread delivery. Not device or real-network validation.',
        'source_sha256': {name: hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
                          for name in ('PreviewService.java', 'ScreenPreparation.java')}}, indent=2) + '\n')
