package games.moisoni.google_iab.internal;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class PurchaseLedgerTest {
    private PurchaseLedger.Entry<String> row(String token, String product, String type, int state, String value) {
        return new PurchaseLedger.Entry<>(token, product, type, state, value);
    }
    @Test public void pendingBecomesPurchasedWithoutDuplicates() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        ledger.upsert(row("token", "coin", "inapp", 2, "pending"));
        ledger.upsert(row("token", "coin", "inapp", 1, "completed"));
        ledger.upsert(row("token", "coin", "inapp", 1, "completed"));
        assertEquals(Collections.singletonList("completed"), ledger.values());
    }
    @Test public void latePendingCannotDowngradePurchased() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        ledger.upsert(row("t", "p", "inapp", 1, "complete"));
        ledger.upsert(row("t", "p", "inapp", 2, "late pending"));
        assertEquals("complete", ledger.values().get(0));
    }
    @Test public void livePurchaseSurvivesOlderEmptySnapshot() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        long started = ledger.version();
        ledger.upsert(row("t", "p", "inapp", 1, "live"));
        ledger.replaceType("inapp", Collections.emptyList(), started);
        assertEquals(Collections.singletonList("live"), ledger.values());
    }
    @Test public void lateSnapshotDoesNotOverwriteLiveCompletion() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        ledger.upsert(row("t", "p", "inapp", 2, "pending"));
        long started = ledger.version();
        ledger.upsert(row("t", "p", "inapp", 1, "completed"));
        ledger.replaceType("inapp", Collections.singletonList(row("t", "p", "inapp", 2, "stale")), started);
        assertEquals(Collections.singletonList("completed"), ledger.values());
    }
    @Test public void consumedTokenCannotReturnFromLateSnapshotOrCallback() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        ledger.upsert(row("t", "p", "inapp", 1, "purchased"));
        long started = ledger.version();
        ledger.consume("t");
        ledger.replaceType("inapp", Collections.singletonList(row("t", "p", "inapp", 1, "late")), started);
        ledger.upsert(row("t", "p", "inapp", 1, "late callback"));
        assertTrue(ledger.values().isEmpty());
    }
    @Test public void replacingOneTypeDoesNotRemoveOtherType() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        ledger.upsert(row("a", "coins", "inapp", 1, "coins"));
        ledger.upsert(row("b", "plus", "subs", 1, "plus"));
        ledger.replaceType("inapp", Collections.emptyList(), ledger.version());
        assertEquals(Collections.singletonList("plus"), ledger.values());
    }
    @Test public void sameTokenCanContainDifferentProducts() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        ledger.upsert(row("t", "a", "subs", 1, "a"));
        ledger.upsert(row("t", "b", "subs", 1, "b"));
        assertEquals(Arrays.asList("a", "b"), ledger.values());
        ledger.consume("t"); assertTrue(ledger.values().isEmpty());
    }
    @Test public void snapshotsAreDefensive() {
        PurchaseLedger<String> ledger = new PurchaseLedger<>();
        ledger.upsert(row("t", "a", "inapp", 1, "a"));
        ledger.values().clear(); ledger.entries().clear();
        assertEquals(1, ledger.values().size());
    }
}
