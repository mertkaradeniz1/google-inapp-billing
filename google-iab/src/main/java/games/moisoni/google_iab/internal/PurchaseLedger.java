package games.moisoni.google_iab.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Session ownership cache. It never replaces durable backend entitlement storage. */
public final class PurchaseLedger<T> {
    public static final class Entry<T> {
        public final String token, product, type;
        public final int state;
        public final T value;
        private long revision;
        public Entry(String token, String product, String type, int state, T value) {
            this.token = token; this.product = product; this.type = type;
            this.state = state; this.value = value;
        }
        public String key() { return token.length() + ":" + token + product; }
    }
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>();
    private final Set<String> consumedTokens = new HashSet<>();
    private long version;

    public synchronized long version() { return version; }

    public synchronized void upsert(Entry<T> incoming) {
        if (consumedTokens.contains(incoming.token)) return;
        Entry<T> old = entries.get(incoming.key());
        // A late PENDING callback cannot downgrade a completed transaction.
        if (old != null && old.state == 1 && incoming.state == 2) return;
        incoming.revision = ++version;
        entries.put(incoming.key(), incoming);
    }

    public synchronized void replaceType(String type, List<Entry<T>> snapshot, long startedVersion) {
        Set<String> present = new HashSet<>();
        for (Entry<T> entry : snapshot) present.add(entry.key());
        Iterator<Map.Entry<String, Entry<T>>> rows = entries.entrySet().iterator();
        while (rows.hasNext()) {
            Map.Entry<String, Entry<T>> row = rows.next();
            if (row.getValue().type.equals(type) && row.getValue().revision <= startedVersion
                    && !present.contains(row.getKey())) rows.remove();
        }
        for (Entry<T> entry : snapshot) {
            Entry<T> old = entries.get(entry.key());
            if (old == null || old.revision <= startedVersion) upsert(entry);
        }
    }

    public synchronized void consume(String token) {
        consumedTokens.add(token);
        removeEntries(token);
        version++;
    }

    public synchronized void removeToken(String token) {
        removeEntries(token);
        version++;
    }

    private void removeEntries(String token) {
        Iterator<Entry<T>> rows = entries.values().iterator();
        while (rows.hasNext()) if (rows.next().token.equals(token)) rows.remove();
    }

    public synchronized boolean isConsumed(String token) { return consumedTokens.contains(token); }
    public synchronized List<Entry<T>> entries() { return new ArrayList<>(entries.values()); }
    public synchronized List<T> values() {
        List<T> result = new ArrayList<>();
        for (Entry<T> entry : entries.values()) result.add(entry.value);
        return result;
    }
    public synchronized void clear() { entries.clear(); consumedTokens.clear(); version++; }
}
