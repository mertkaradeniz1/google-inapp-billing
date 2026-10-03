package games.moisoni.google_iab.internal;

/** Pure decision rules, independent of an Android device. */
public final class BillingRules {
    private BillingRules() {}

    /** Legacy SubscriptionUpdateParams values to the new product-level values. */
    public static int productReplacementMode(int legacyMode) {
        switch (legacyMode) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 5: return 4; // CHARGE_FULL_PRICE
            case 6: return 5; // DEFERRED (6 means KEEP_EXISTING in the new API!)
            default: throw new IllegalArgumentException("Unsupported legacy replacement mode: " + legacyMode);
        }
    }
    public static boolean isTransient(int responseCode) {
        return responseCode == -1 || responseCode == -3 || responseCode == 2
                || responseCode == 6 || responseCode == 12;
    }
    public static long retryDelay(int attempt) { return Math.min(1000L << Math.min(attempt, 10), 30000L); }
}
