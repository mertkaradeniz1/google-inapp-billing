package games.moisoni.google_iab.internal;
import org.junit.Test;
import static org.junit.Assert.*;
public class BillingRulesTest {
    @Test public void mapsLegacyDeferredAndFullPriceToDifferentNewValues() {
        assertEquals(5, BillingRules.productReplacementMode(6));
        assertEquals(4, BillingRules.productReplacementMode(5));
        assertEquals(1, BillingRules.productReplacementMode(1));
        assertEquals(2, BillingRules.productReplacementMode(2));
        assertEquals(3, BillingRules.productReplacementMode(3));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnknownMode() {
        BillingRules.productReplacementMode(0);
    }
    @Test public void retriesOnlyTransientErrors() {
        for (int code : new int[]{-1, -3, 2, 6, 12}) assertTrue(BillingRules.isTransient(code));
        for (int code : new int[]{-2, 1, 3, 4, 5, 7, 8}) assertFalse(BillingRules.isTransient(code));
    }
    @Test public void capsExponentialBackoff() {
        assertEquals(1000, BillingRules.retryDelay(0));
        assertEquals(2000, BillingRules.retryDelay(1));
        assertEquals(30000, BillingRules.retryDelay(40));
    }
}
