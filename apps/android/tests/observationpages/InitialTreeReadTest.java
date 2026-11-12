package io.airtap.preview;

/** Executes CompanionUi.read extracted unchanged, with explicit native/platform fakes. */
public final class InitialTreeReadTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void refused(InitialTreeHarness h, String code) throws Exception {
        try { h.readNow(); throw new AssertionError("unexpected capture"); }
        catch (ScopedUi.Refused error) { check(error.getMessage().equals(code)); }
        check(h.snapshot == null && h.pagedTree == null);
    }
    public static void main(String[] args) throws Exception {
        InitialTreeHarness changing = new InitialTreeHarness();
        var tree = changing.readNow();
        check(changing.captures == 1 && ((org.json.JSONObject)((org.json.JSONArray)tree.get("nodes")).get(0)).getString("text").equals("tick-1"));
        check(changing.snapshot != null && (Integer)tree.get("total_nodes") == 1);
        System.out.println("PASS first read returns a bounded changing-counter capture without demanding a second identical page");
        InitialTreeHarness window = new InitialTreeHarness(); window.changeWindow = true; refused(window,"window_changed");
        InitialTreeHarness revision = new InitialTreeHarness(); revision.invalidate = true; refused(revision,"window_changed");
        InitialTreeHarness revoked = new InitialTreeHarness(); revoked.revoke = true; refused(revoked,"permission_denied");
        System.out.println("PASS changed window invalidation and revoked authority discard the entire initial capture");
        InitialTreeHarness failed = new InitialTreeHarness(); failed.failScan = true; refused(failed,"inspection_incomplete");
        System.out.println("PASS incomplete scan never returns partial nodes or keeps a usable snapshot");
        System.out.println("TOTAL 3");
    }
}
