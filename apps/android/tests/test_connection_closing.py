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
import re
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
    'RecoveryStatus recoveryStatus()', 'boolean isConnected()', 'private boolean valid()', 'private boolean executionAllowed()', 'private boolean current(',
    'private void connect(', 'private void awaitHandshake(', 'private void cancelHandshake(',
    'private void disconnected(', 'private void scheduleConnect(', 'private record NetworkPath(', 'private NetworkPath networkPath(', 'private void observeNetwork(', 'private void resetForNetwork(',
    'void stop(', 'private void suspend('))
marker = next(line for line in service.splitlines() if 'AtomicBoolean transportOpen =' in line)
deadline = next(line for line in service.splitlines() if 'static final long HANDSHAKE_TIMEOUT_MS =' in line)

probe = '''package io.airtap.preview;
import java.util.*;
import java.nio.charset.StandardCharsets;
final class PreviewService {
  private WebSocket socket;
  private boolean authorized = true, connected,notificationBlocked,restoring,multiMode,multiHello,grantRefreshInFlight;
  private long activeCommand = 1;
  private int generation = 1, retry,connections,attempts,notificationFailures;
  private String state, lastDiagnostic,sessionFence,restoringFence,url="wss://synthetic.invalid",token="synthetic";private RecoveryStatus recoveryStatus;
  private Runnable reconnectTask,handshakeTask,helloTask;
  private ConnectivityManager.NetworkCallback networkCallback;
  private Network currentNetwork;
  private NetworkPath currentNetworkPath;
  private final ConnectivityManager network=new ConnectivityManager();
  private final Client client=new Client();
  private final Random jitter = new Random(0);
  private final Main main = new Main();
  private final Ui ui = new Ui();
  private final Host host = new Host();
  private final ScreenPreparation recovery = new ScreenPreparation(host);
  Object config;boolean pendingMigration,multiRecord;int stopped, reconnects, executed,migrationRestores;
  ''' + marker + '\n' + deadline + '\n' + methods + '''
  <T>T getSystemService(Class<T> type){return type.cast(network);}
  private void restore(){if(!pendingMigration&&!multiRecord)throw new AssertionError("unexpected migration restore in single fixture");migrationRestores++;}
  private void storageUnavailable(){throw new AssertionError("unexpected storage failure in single fixture");}
  // Single-mode fixture: multi dispatch must never silently become a no-op.
  private void multiMessage(WebSocket ws,int run,String text){throw new AssertionError("unexpected multi dispatch in single fixture");}
  private void scheduleRestore(int run,long delay){throw new AssertionError("unexpected multi restore in single fixture");}
  private void disconnectTask(){if(multiMode)throw new AssertionError("unexpected multi task in single fixture");}
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
    PreviewService vpnRecovery=new PreviewService();
    vpnRecovery.network.capabilities.transports=(1<<NetworkCapabilities.TRANSPORT_VPN)|(1<<NetworkCapabilities.TRANSPORT_WIFI);
    vpnRecovery.observeNetwork(1);vpnRecovery.retry=5;vpnRecovery.scheduleConnect(1,30000);
    ConnectivityManager.NetworkCallback vpnCallback=vpnRecovery.networkCallback;
    vpnCallback.onCapabilitiesChanged(vpnRecovery.network.active,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_VPN,true,true));vpnRecovery.main.drain();
    check(vpnRecovery.retry==5,"losing the VPN underlay must not reset failure backoff");
    check(vpnRecovery.reconnects==1,"losing an underlay must not accelerate an existing retry");
    vpnCallback.onCapabilitiesChanged(vpnRecovery.network.active,new NetworkCapabilities((1<<NetworkCapabilities.TRANSPORT_VPN)|(1<<NetworkCapabilities.TRANSPORT_WIFI),true,true));vpnRecovery.main.drain();
    check(vpnRecovery.retry==0,"a newly available VPN underlay must reset the old offline failure backoff");
    vpnRecovery.main.advance(250);check(vpnRecovery.attempts==1,"new available path gets one prompt attempt");
    WebSocket postRecovery=vpnRecovery.socket;postRecovery.listener.onFailure(postRecovery,new java.io.IOException("synthetic TLS EOF after network return"),null);vpnRecovery.main.drain();
    check(vpnRecovery.retry==1,"a failure on the new path starts at the first retry tier");
    vpnRecovery.main.advance(1250);check(vpnRecovery.attempts==2,"first new-path failure retries within 1250ms rather than old 30s tier");
    System.out.println("PASS vpn_underlay_recovery_resets_old_failure_backoff");

    PreviewService duplicatePath=new PreviewService();duplicatePath.observeNetwork(1);duplicatePath.retry=5;duplicatePath.scheduleConnect(1,30000);
    Runnable originalRetry=duplicatePath.reconnectTask;
    for(int i=0;i<100;i++)duplicatePath.networkCallback.onCapabilitiesChanged(duplicatePath.network.active,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_WIFI,true,true));
    duplicatePath.main.advance(1000);
    check(duplicatePath.retry==5&&duplicatePath.reconnectTask==originalRetry&&duplicatePath.reconnects==1&&duplicatePath.attempts==0,
        "identical route callbacks must neither reset backoff, postpone its timer, nor create fast retries");
    duplicatePath.main.advance(29000);check(duplicatePath.attempts==1,"unchanged path retains its originally scheduled bounded retry");
    System.out.println("PASS repeated_network_capabilities_do_not_accelerate_or_postpone_backoff");

    PreviewService fewerUnderlays=new PreviewService();int vpnAndCell=(1<<NetworkCapabilities.TRANSPORT_VPN)|(1<<NetworkCapabilities.TRANSPORT_CELLULAR);
    fewerUnderlays.network.capabilities.transports=vpnAndCell|(1<<NetworkCapabilities.TRANSPORT_WIFI);fewerUnderlays.observeNetwork(1);fewerUnderlays.retry=5;fewerUnderlays.scheduleConnect(1,30000);
    for(int i=0;i<100;i++)fewerUnderlays.networkCallback.onCapabilitiesChanged(fewerUnderlays.network.active,new NetworkCapabilities(vpnAndCell,true,true));fewerUnderlays.main.drain();
    check(fewerUnderlays.retry==0&&fewerUnderlays.reconnects==2,"a changed combination with a remaining validated cellular path resets once");
    fewerUnderlays.main.advance(250);check(fewerUnderlays.attempts==1,"remaining usable underlay permits one prompt attempt");
    WebSocket cellularAttempt=fewerUnderlays.socket;cellularAttempt.listener.onFailure(cellularAttempt,new java.io.IOException("synthetic cellular failure"),null);fewerUnderlays.main.drain();
    Runnable sameBackoff=fewerUnderlays.reconnectTask;
    for(int i=0;i<100;i++)fewerUnderlays.networkCallback.onCapabilitiesChanged(fewerUnderlays.network.active,new NetworkCapabilities(vpnAndCell,true,true));fewerUnderlays.main.drain();
    check(fewerUnderlays.retry==1&&fewerUnderlays.reconnectTask==sameBackoff&&fewerUnderlays.reconnects==3,"repeated remaining-underlay callbacks preserve the new failure backoff");
    System.out.println("PASS partial_underlay_switch_resets_once_when_another_transport_remains");

    for(boolean validationLoss:new boolean[]{false,true}){
      PreviewService pathReturn=new PreviewService();pathReturn.observeNetwork(1);pathReturn.retry=5;pathReturn.scheduleConnect(1,30000);
      NetworkCapabilities unavailable=new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_WIFI,validationLoss,!validationLoss);
      pathReturn.networkCallback.onCapabilitiesChanged(pathReturn.network.active,unavailable);pathReturn.main.drain();
      check(pathReturn.retry==5&&pathReturn.reconnects==1,"losing internet or validation cannot reset or accelerate failure backoff");
      for(int i=0;i<100;i++)pathReturn.networkCallback.onCapabilitiesChanged(pathReturn.network.active,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_WIFI,true,true));
      pathReturn.main.drain();check(pathReturn.retry==0&&pathReturn.reconnects==2,"one usable-path edge resets backoff once despite repeated callbacks");
      pathReturn.main.advance(250);check(pathReturn.attempts==1,"internet/validation return starts only one attempt");
    }
    System.out.println("PASS internet_and_validation_recovery_each_accelerate_once");

    for(boolean established:new boolean[]{false,true}){
      PreviewService inFlightPath=new PreviewService();inFlightPath.observeNetwork(1);inFlightPath.connect(1);WebSocket sameSocket=inFlightPath.socket;
      if(established){sameSocket.listener.onOpen(sameSocket,new Response(101));inFlightPath.main.drain();}
      Runnable sameDeadline=inFlightPath.handshakeTask;inFlightPath.retry=5;
      inFlightPath.networkCallback.onCapabilitiesChanged(inFlightPath.network.active,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_CELLULAR,true,true));inFlightPath.main.drain();
      check(inFlightPath.retry==0&&inFlightPath.socket==sameSocket&&inFlightPath.handshakeTask==sameDeadline&&sameSocket.cancels==0&&inFlightPath.reconnects==0,
          "a capability route improvement resets retry without cancelling a pending or healthy transport");
      sameSocket.listener.onFailure(sameSocket,new java.io.IOException("synthetic route settling"),null);inFlightPath.main.drain();
      check(inFlightPath.retry==1,"in-flight failure after a new route starts at the first tier");
      inFlightPath.main.advance(1250);check(inFlightPath.attempts==2&&inFlightPath.executed==0,"new-path retry neither duplicates transport nor replays work");
    }
    System.out.println("PASS route_change_keeps_inflight_and_healthy_socket_lifetime");

    PreviewService newDefault=new PreviewService();newDefault.observeNetwork(1);Network oldDefault=newDefault.network.active,newRoute=new Network();
    ConnectivityManager.NetworkCallback defaultCallback=newDefault.networkCallback;newDefault.retry=5;newDefault.scheduleConnect(1,30000);
    newDefault.network.active=newRoute;defaultCallback.onAvailable(newRoute);newDefault.main.drain();
    check(newDefault.retry==5&&newDefault.recoveryStatus()==RecoveryStatus.NETWORK_RECONNECTING,"availability alone is not a validated new path");
    defaultCallback.onCapabilitiesChanged(newRoute,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_WIFI,true,false));newDefault.main.drain();
    defaultCallback.onCapabilitiesChanged(oldDefault,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_CELLULAR,true,true));newDefault.main.drain();
    check(newDefault.retry==5,"unvalidated or stale-network callbacks cannot reset current backoff");
    defaultCallback.onCapabilitiesChanged(newRoute,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_WIFI,true,true));newDefault.main.drain();
    check(newDefault.retry==0,"new default path resets only after usable capabilities arrive");
    newDefault.main.advance(250);WebSocket currentRouteSocket=newDefault.socket;
    defaultCallback.onLost(oldDefault);defaultCallback.onAvailable(newRoute);newDefault.main.drain();
    check(newDefault.socket==currentRouteSocket&&currentRouteSocket.cancels==0&&newDefault.attempts==1,"late old loss and duplicate availability preserve new transport");
    System.out.println("PASS new_default_waits_for_usable_path_and_ignores_stale_network_callbacks");

    PreviewService staleNetwork=new PreviewService();staleNetwork.observeNetwork(1);ConnectivityManager.NetworkCallback staleCallback=staleNetwork.networkCallback;
    staleNetwork.generation=2;staleNetwork.retry=5;
    staleCallback.onCapabilitiesChanged(staleNetwork.network.active,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_CELLULAR,true,true));staleCallback.onAvailable(new Network());staleNetwork.main.advance(2000);
    check(staleNetwork.retry==5&&staleNetwork.attempts==0&&staleNetwork.reconnects==0,"old generation cannot reset or schedule network recovery");
    PreviewService stoppedNetwork=new PreviewService();stoppedNetwork.observeNetwork(1);ConnectivityManager.NetworkCallback stoppedCallback=stoppedNetwork.networkCallback;Network stoppedNetworkIdentity=stoppedNetwork.network.active;
    stoppedNetwork.stop("synthetic owner stop");stoppedCallback.onCapabilitiesChanged(stoppedNetworkIdentity,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_CELLULAR,true,true));stoppedCallback.onAvailable(new Network());stoppedNetwork.main.advance(2000);
    check(stoppedNetwork.attempts==0&&stoppedNetwork.reconnects==0&&!stoppedNetwork.authorized&&stoppedNetwork.recoveryStatus()==RecoveryStatus.WAITING_FOR_AI,"late network callbacks cannot resume owner-stopped control");
    PreviewService blockedNetwork=new PreviewService();blockedNetwork.observeNetwork(1);SessionService.permitted=false;
    blockedNetwork.networkCallback.onCapabilitiesChanged(blockedNetwork.network.active,new NetworkCapabilities(1<<NetworkCapabilities.TRANSPORT_CELLULAR,true,true));blockedNetwork.main.advance(2000);
    check(blockedNetwork.attempts==0&&blockedNetwork.socket==null&&blockedNetwork.notificationBlocked,"a fresh path cannot bypass notification execution gate");SessionService.permitted=true;
    System.out.println("PASS network_recovery_preserves_generation_stop_and_notification_gates");

    PreviewService visibleReset=new PreviewService();WebSocket visibleSocket=new WebSocket();visibleReset.connect(visibleSocket,1);
    check(visibleReset.recoveryStatus()==RecoveryStatus.CONNECTED,"fixture starts with actual open connection");visibleReset.resetForNetwork(1);
    check(!visibleReset.isConnected()&&visibleReset.recoveryStatus()==RecoveryStatus.NETWORK_RECONNECTING&&visibleSocket.cancels==1,"network reset immediately presents reconnecting before the next attempt");
    System.out.println("PASS network_reset_does_not_leave_cached_connected_state");

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

    for(boolean committed:new boolean[]{false,true}) {
      PreviewService migration=new PreviewService();WebSocket migratingSocket=new WebSocket();
      WebSocketListener listener=migration.connect(migratingSocket,1);
      migration.pendingMigration=!committed;migration.multiRecord=committed;
      listener.onClosed(migratingSocket,1008,"synthetic mode change");migration.main.drain();
      check(migration.stopped==0&&!migration.authorized&&migration.migrationRestores==1&&migration.reconnects==0,
          "migration close must reconcile persisted state, never stop original consent or reconnect with old hello");
      listener.onFailure(migratingSocket,new java.io.IOException("late"),new Response(401));migration.main.drain();
      check(migration.stopped==0&&migration.migrationRestores==1,"late terminal callback cannot stop migration");
    }
    System.out.println("PASS pending_or_committed_migration_close_reconciles_without_old_hello_or_revoke");

    PreviewService normal = new PreviewService(); WebSocket b = new WebSocket();
    WebSocketListener closeB = normal.connect(b, 1); normal.prepare(b, 1);
    closeB.onClosing(b, 1001, "going away");
    check(!normal.current(b, 1), "ordinary close immediately invalidates pending task");
    normal.host.screen = ScreenPreparation.State.READY; normal.host.advance(10000); normal.main.drain();
    check(normal.executed == 0 && normal.activeCommand == 0, "ordinary close cancels preparation without replay");
    check(normal.authorized && normal.stopped == 0 && normal.reconnects == 1, "ordinary close retains pairing and schedules recovery once");
    check(!normal.isConnected()&&normal.recoveryStatus()==RecoveryStatus.NETWORK_RECONNECTING,"disconnected UI must not remain ready");
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
    check(!ownerStop.isConnected()&&ownerStop.recoveryStatus()==RecoveryStatus.WAITING_FOR_AI,"stopped UI stays waiting after late callbacks");
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

    PreviewService multiOpening=new PreviewService();multiOpening.multiMode=true;multiOpening.connect(1);WebSocket awaitingHello=multiOpening.socket;
    awaitingHello.listener.onOpen(awaitingHello,new Response(101));multiOpening.main.drain();
    check(multiOpening.connected&&!multiOpening.multiHello&&!multiOpening.isConnected(),"open multi transport alone is not an acknowledged connection");
    check(multiOpening.recoveryStatus()==RecoveryStatus.VERIFYING_CONNECTION&&multiOpening.state.equals("正在核实已保存的 AI 连接"),"multi UI waits for protocol acknowledgement");
    check(multiOpening.helloTask!=null&&awaitingHello.sends==1,"multi handshake and existing deadline remain in place");
    System.out.println("PASS multi_socket_open_waits_for_hello_ack_before_presenting_connected");

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
  static final class NetworkCapabilities {
    static final int NET_CAPABILITY_VALIDATED=1,NET_CAPABILITY_INTERNET=2;
    static final int TRANSPORT_CELLULAR=0,TRANSPORT_WIFI=1,TRANSPORT_BLUETOOTH=2,TRANSPORT_ETHERNET=3,TRANSPORT_VPN=4,TRANSPORT_WIFI_AWARE=5,TRANSPORT_LOWPAN=6,TRANSPORT_USB=8;
    boolean validated=true,internet=true;int transports=1<<TRANSPORT_WIFI;
    NetworkCapabilities(){}NetworkCapabilities(int transports,boolean internet,boolean validated){this.transports=transports;this.internet=internet;this.validated=validated;}
    boolean hasCapability(int capability){return capability==NET_CAPABILITY_VALIDATED?validated:capability==NET_CAPABILITY_INTERNET&&internet;}
    boolean hasTransport(int transport){return (transports&(1<<transport))!=0;}
  }
  static final class ConnectivityManager {
    static class NetworkCallback{public void onAvailable(Network network){}public void onLost(Network network){}public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){}}
    Network active=new Network();NetworkCapabilities capabilities=new NetworkCapabilities();NetworkCallback callback;
    Network getActiveNetwork(){return active;}NetworkCapabilities getNetworkCapabilities(Network network){return capabilities;}
    void registerDefaultNetworkCallback(NetworkCallback callback){this.callback=callback;}void unregisterNetworkCallback(NetworkCallback callback){if(this.callback==callback)this.callback=null;}
  }
  static final class Ui { ScreenPreparation.State screenState(){return ScreenPreparation.State.READY;} void clear() {} void cancelGestures() {} }
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
// Only the outbound hello string is synthetic; the matching ACK/state path runs in test_multi_ai_control.py.
final class MultiAiProtocol {static Object hello(){return "synthetic_multi_hello";}}
// This fixture exercises ordinary transport failures; TLS classification is covered by test_tls_policy.py.
final class TlsPolicy {static final String USER_MESSAGE="synthetic security failure";static boolean isSecurityFailure(Throwable failure){return false;}}
final class SessionService {static boolean ready=true,permitted=true;static boolean notificationsAllowed(PreviewService service){return permitted;}static boolean readyFor(PreviewService service,int generation){return ready;}static void end(PreviewService service){}static void paused(PreviewService service){}}
final class TrustedGrant {final PreviewService owner;TrustedGrant(PreviewService service){owner=service;}boolean multi(Object config){return owner.multiRecord;}Object migration(Object config){return owner.pendingMigration?new Object():null;}void stop(){owner.stopped++;}}
final class PreviewState {static boolean grantAvailable;}
final class WorkspaceShares {static void revokeAll(PreviewService service){}}
final class PreviewDiagnostics {
  enum Event { socket_closing,socket_closed,disconnected,connect_wait_network,connect_attempt,socket_cancel,socket_open,socket_close_requested,handshake_timeout,reconnect_scheduled,network_reset,network_observe,network_available,network_lost,network_capabilities }
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
screen_test = ROOT / 'tests/ScreenPreparationTest.java'
with tempfile.TemporaryDirectory(prefix='airtap-connection-close-') as directory:
    path = Path(directory)
    (path / 'PreviewService.java').write_text(probe)
    errno = path / 'android/system/ErrnoException.java'
    errno.parent.mkdir(parents=True)
    errno.write_text('package android.system; public class ErrnoException extends Exception { public int errno = 104; }')
    subprocess.run([str(jdk / 'javac'), '--release', '17', '-d', directory, str(path / 'PreviewService.java'),
                    str(SOURCE / 'ScreenPreparation.java'), str(SOURCE / 'RecoveryStatus.java'), str(errno), str(screen_test)], check=True)
    result = subprocess.run([str(jdk / 'java'), '-cp', directory, 'io.airtap.preview.PreviewService'],
                            capture_output=True, text=True, check=True)
    screen_result = subprocess.run([str(jdk / 'java'), '-cp', directory, 'io.airtap.preview.ScreenPreparationTest'],
                                   capture_output=True, text=True, timeout=30)
print(result.stdout, end='')
print(screen_result.stdout, end='')
if screen_result.returncode:
    print(screen_result.stderr, end='')
screen_result.check_returncode()
checks = [line[5:] for line in result.stdout.splitlines() if line.startswith('PASS ')]
assert len(checks) == 25
screen_summary = re.fullmatch(r'ScreenPreparation: ([1-9][0-9]*) scenarios passed\s*', screen_result.stdout)
assert screen_summary, 'Missing ScreenPreparation scenario result'
screen_scenarios = int(screen_summary.group(1))
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'passed': len(checks), 'total': len(checks), 'checks': checks,
        'scope': 'Host JVM. Exact production connect/callback/deadline/current/disconnected/backoff/stop/suspend/network-observer/path/reset methods, real ScreenPreparation; fake transport can withhold handshake callbacks and delay main-thread delivery. Public capability edges exercise VPN underlay loss/return, duplicate callbacks, validation/internet return, pending/healthy socket preservation, and stop/notification/generation gates. Single-mode lifecycle plus multi-mode onOpen presentation before hello acknowledgement; multi command/ACK handling is covered separately. Not device or real-network validation.',
        'screen_preparation': {'passed': screen_scenarios, 'total': screen_scenarios,
            'scope': 'Existing production ScreenPreparation state-machine scenarios with deterministic time and platform fakes; separate from the connection scenarios above. No Android window or screenshot callback validation.',
            'source_sha256': {'ScreenPreparation.java': hashlib.sha256((SOURCE / 'ScreenPreparation.java').read_bytes()).hexdigest(),
                              'ScreenPreparationTest.java': hashlib.sha256(screen_test.read_bytes()).hexdigest()}},
        'source_sha256': {name: hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
                          for name in ('PreviewService.java', 'ScreenPreparation.java')}}, indent=2) + '\n')
