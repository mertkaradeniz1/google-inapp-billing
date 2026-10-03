package games.moisoni.google_iab;

import static com.android.billingclient.api.BillingClient.BillingResponseCode.*;
import static com.android.billingclient.api.BillingClient.ProductType.INAPP;
import static com.android.billingclient.api.BillingClient.ProductType.SUBS;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.android.billingclient.api.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import games.moisoni.google_iab.enums.*;
import games.moisoni.google_iab.internal.BillingRules;
import games.moisoni.google_iab.internal.PurchaseLedger;
import games.moisoni.google_iab.listeners.BillingEventListener;
import games.moisoni.google_iab.models.*;

/**
 * Google Play Billing 9.0 connector. Create, configure and call mutating methods on the main
 * thread. Listener callbacks run on that thread. Getter methods return defensive snapshots.
 * Backend verification and durable, token-idempotent entitlement delivery belong to the app.
 * Auto-finalization is opt-in and suitable only when delivery can safely precede finalization.
 */
public class BillingConnector implements DefaultLifecycleObserver {
    private static final int LOCAL_ERROR = 99;
    private static final int MAX_ATTEMPTS = 3;
    private static final String TAG = "BillingConnector";
    private final Context context;
    private final String base64Key;
    private final Handler handler;
    private final BillingClient billingClient;
    private final Lifecycle lifecycle;
    private volatile BillingEventListener listener;
    private volatile boolean released;
    private volatile boolean fetchedPurchasedProducts;
    private boolean connecting, reconnectScheduled, refreshing, refreshAgain;
    private boolean configurationLocked;
    private int reconnectAttempt;
    private long catalogGeneration;
    private boolean autoConsume, autoAcknowledge, logging;
    private List<String> consumableIds = Collections.emptyList();
    private List<String> nonConsumableIds = Collections.emptyList();
    private List<String> subscriptionIds = Collections.emptyList();
    private final Map<String, SkuProductType> configuredTypes = new LinkedHashMap<>();
    private final Map<String, ProductInfo> catalog = new LinkedHashMap<>();
    private final PurchaseLedger<PurchaseInfo> ledger = new PurchaseLedger<>();
    private final Set<String> deliveredStates = new HashSet<>();
    private final Set<String> deliveredPendingUpdates = new HashSet<>();
    private final Map<String, Map<String, String>> localMetadataByToken = new LinkedHashMap<>();
    private final Set<String> acknowledgedTokens = new HashSet<>();
    private final Set<String> finalizingTokens = new HashSet<>();
    private final Set<String> pollingTokens = new HashSet<>();
    private PurchaseParams defaultPurchaseParams;
    private Flow activeFlow;
    private final Map<String, String> deferredTargets = new LinkedHashMap<>();
    private final Runnable reconnectTask = () -> { reconnectScheduled = false; connect(); };

    private static final class Batch {
        int remaining;
        boolean successful = true;
        Batch(int count) { remaining = count; }
    }
    private static final class Flow {
        final String productId, oldProductId, oldToken;
        final int mode;
        final PurchaseParams params;
        Flow(String productId, String oldProductId, String oldToken, int mode, PurchaseParams params) {
            this.productId = productId; this.oldProductId = oldProductId; this.oldToken = oldToken;
            this.mode = mode; this.params = params;
        }
    }

    public BillingConnector(@NonNull Context context, String base64Key, @Nullable Lifecycle lifecycle) {
        this(context, base64Key, lifecycle, null, new Handler(Looper.getMainLooper()));
    }

    // Package-private injection seam used by JVM regression tests.
    BillingConnector(Context context, String base64Key, Lifecycle lifecycle, BillingClient client, Handler uiHandler) {
        requireMain();
        if (base64Key == null || base64Key.trim().isEmpty())
            throw new IllegalArgumentException("Play Console public license key is required");
        this.context = context.getApplicationContext();
        this.base64Key = base64Key;
        this.lifecycle = lifecycle;
        this.handler = uiHandler;
        billingClient = client != null ? client : BillingClient.newBuilder(this.context)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts().enablePrepaidPlans().build())
                .setListener((result, purchases) -> onMain(() -> onPurchasesUpdated(result, purchases)))
                .enableAutoServiceReconnection()
                .build();
        if (lifecycle != null) lifecycle.addObserver(this);
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("BillingConnector mutating methods must run on the main thread");
    }
    private void requireUsable() {
        requireMain();
        if (released) throw new IllegalStateException("BillingConnector has been released; create a new instance");
    }
    private void onMain(Runnable action) {
        if (released) return;
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else handler.post(() -> { if (!released) action.run(); });
    }
    private interface ListenerEvent { void accept(BillingEventListener listener); }
    private void emit(ListenerEvent event) {
        handler.post(() -> { BillingEventListener current = listener;
            if (!released && current != null) event.accept(current);
        });
    }
    private void error(ErrorType type, BillingResult result) {
        emit(l -> l.onBillingError(this, new BillingResponse(type, result)));
    }
    private void error(ErrorType type, String message) {
        emit(l -> l.onBillingError(this, new BillingResponse(type, message, LOCAL_ERROR)));
    }
    private void log(String message) { if (logging) Log.d(TAG, message); }

    public final void setBillingEventListener(@Nullable BillingEventListener listener) {
        requireUsable(); this.listener = listener;
    }
    private List<String> copyIds(List<String> ids) {
        if (ids == null) return Collections.emptyList();
        List<String> copy = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.trim().isEmpty() || !seen.add(id))
                throw new IllegalArgumentException("Product IDs must be nonempty and unique");
            copy.add(id);
        }
        return Collections.unmodifiableList(copy);
    }
    private void requireConfigurable() {
        requireUsable();
        if (configurationLocked)
            throw new IllegalStateException("Configure product IDs before connect()");
    }
    public final BillingConnector setConsumableIds(List<String> ids) {
        requireConfigurable(); consumableIds = copyIds(ids); return this;
    }
    public final BillingConnector setNonConsumableIds(List<String> ids) {
        requireConfigurable(); nonConsumableIds = copyIds(ids); return this;
    }
    public final BillingConnector setSubscriptionIds(List<String> ids) {
        requireConfigurable(); subscriptionIds = copyIds(ids); return this;
    }
    /** Opt-in only. Do not enable before an asynchronous backend grants durable entitlement. */
    public final BillingConnector autoConsume() { requireUsable(); autoConsume = true; return this; }
    public final BillingConnector autoAcknowledge() { requireUsable(); autoAcknowledge = true; return this; }
    public final BillingConnector enableLogging() { requireUsable(); logging = true; return this; }
    public final boolean isReady() { return !released && billingClient.isReady(); }

    private void configureTypes() {
        Map<String, SkuProductType> types = new LinkedHashMap<>();
        addTypes(types, consumableIds, SkuProductType.CONSUMABLE);
        addTypes(types, nonConsumableIds, SkuProductType.NON_CONSUMABLE);
        addTypes(types, subscriptionIds, SkuProductType.SUBSCRIPTION);
        if (types.isEmpty()) throw new IllegalArgumentException("At least one product ID is required");
        configuredTypes.clear(); configuredTypes.putAll(types);
    }
    private void addTypes(Map<String, SkuProductType> types, List<String> ids, SkuProductType type) {
        for (String id : ids) if (types.put(id, type) != null)
            throw new IllegalArgumentException("Product appears in more than one list: " + id);
    }
    public final BillingConnector connect() {
        requireUsable();
        if (listener == null) throw new IllegalStateException("Set BillingEventListener before connect()");
        configureTypes();
        configurationLocked = true;
        if (connecting) return this;
        handler.removeCallbacks(reconnectTask); reconnectScheduled = false;
        if (billingClient.isReady()) { refreshPurchases(); return this; }
        connecting = true;
        billingClient.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(@NonNull BillingResult result) {
                onMain(() -> {
                    connecting = false;
                    if (result.getResponseCode() == OK) {
                        reconnectAttempt = 0;
                        // Ownership recovery must not wait for a successful catalog query.
                        refreshPurchases(); refreshProducts();
                    } else {
                        error(errorType(result.getResponseCode()), result);
                    }
                });
            }
            @Override public void onBillingServiceDisconnected() {
                onMain(() -> {
                    connecting = false;
                    error(ErrorType.CLIENT_DISCONNECTED, "Google Play billing service disconnected");
                });
            }
        });
        return this;
    }
    private void scheduleReconnect() {
        if (released || reconnectScheduled) return;
        reconnectScheduled = true;
        handler.postDelayed(reconnectTask, BillingRules.retryDelay(reconnectAttempt++));
    }
    private boolean readyOrError() {
        if (!isReady()) {
            error(ErrorType.CLIENT_NOT_READY, "Billing client is not ready");
            return false;
        }
        return true;
    }

    public void refreshProducts() {
        requireUsable(); if (!readyOrError()) return;
        long generation = ++catalogGeneration;
        List<String> inapp = new ArrayList<>(consumableIds); inapp.addAll(nonConsumableIds);
        queryCatalog(INAPP, inapp, generation); queryCatalog(SUBS, subscriptionIds, generation);
    }
    private void queryCatalog(String type, List<String> ids, long generation) {
        if (ids.isEmpty()) return;
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String id : ids) products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id).setProductType(type).build());
        billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build(),
                (result, details) -> onMain(() -> {
                    if (generation != catalogGeneration) return;
                    if (result.getResponseCode() != OK) { error(ErrorType.BILLING_ERROR, result); return; }
                    List<ProductInfo> found = new ArrayList<>();
                    synchronized (catalog) {
                        for (String id : ids) catalog.remove(id);
                        for (ProductDetails detail : details.getProductDetailsList()) {
                            ProductInfo info = new ProductInfo(configuredTypes.get(detail.getProductId()), detail);
                            catalog.put(info.getProduct(), info); found.add(info);
                        }
                    }
                    for (UnfetchedProduct missing : details.getUnfetchedProductList()) {
                        BillingResponse response = BillingResponse.forUnfetchedProduct(
                                "Product unavailable; unfetched status=" + missing.getStatusCode(), missing.getStatusCode());
                        emit(l -> l.onProductQueryError(missing.getProductId(), response));
                    }
                    emit(l -> l.onProductsFetched(Collections.unmodifiableList(found)));
                }));
    }
    public List<ProductInfo> getFetchedProductsList() {
        synchronized (catalog) { return Collections.unmodifiableList(new ArrayList<>(catalog.values())); }
    }

    public void refreshPurchases() {
        requireUsable();
        if (refreshing) { refreshAgain = true; return; }
        if (!readyOrError()) { fetchedPurchasedProducts = false; emit(l -> l.onPurchasesRefreshFinished(false)); return; }
        refreshing = true; fetchedPurchasedProducts = false;
        BillingResult support = billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS);
        boolean querySubs = support.getResponseCode() != FEATURE_NOT_SUPPORTED;
        Batch batch = new Batch(querySubs ? 2 : 1);
        queryOwned(INAPP, batch);
        if (querySubs) queryOwned(SUBS, batch);
        else ledger.replaceType(SUBS, Collections.emptyList(), ledger.version());
    }
    private void queryOwned(String type, Batch batch) {
        long startedVersion = ledger.version();
        QueryPurchasesParams.Builder params = QueryPurchasesParams.newBuilder().setProductType(type);
        if (SUBS.equals(type)) params.includeSuspendedSubscriptions(true);
        billingClient.queryPurchasesAsync(params.build(), (result, purchases) -> onMain(() -> {
            if (result.getResponseCode() == OK) {
                List<PurchaseInfo> owned = convertPurchases(purchases, type, activeFlow);
                // A malformed/signature-invalid response must not revoke known ownership.
                if (allSignaturesValid(purchases)) {
                    List<PurchaseLedger.Entry<PurchaseInfo>> entries = toEntries(owned);
                    ledger.replaceType(type, entries, startedVersion);
                    List<PurchaseInfo> current = currentForType(type);
                    emit(l -> l.onPurchasedProductsFetched(SUBS.equals(type) ? ProductType.SUBS : ProductType.INAPP,
                            Collections.unmodifiableList(current)));
                    deliverEvents(current, false);
                    correlateFlow(current);
                } else batch.successful = false;
            } else {
                batch.successful = false; error(ErrorType.FETCH_PURCHASED_PRODUCTS_ERROR, result);
            }
            if (--batch.remaining == 0) {
                refreshing = false; fetchedPurchasedProducts = batch.successful;
                emit(l -> l.onPurchasesRefreshFinished(batch.successful));
                if (refreshAgain) { refreshAgain = false; refreshPurchases(); }
            }
        }));
    }
    private boolean allSignaturesValid(List<Purchase> purchases) {
        for (Purchase purchase : purchases) if (!validSignature(purchase)) return false;
        return true;
    }
    private boolean validSignature(Purchase purchase) {
        return context.getPackageName().equals(purchase.getPackageName())
                && Security.verifyPurchase(base64Key, purchase.getOriginalJson(), purchase.getSignature());
    }
    private List<PurchaseInfo> convertPurchases(List<Purchase> purchases, @Nullable String queryType, @Nullable Flow flow) {
        List<PurchaseInfo> result = new ArrayList<>();
        for (Purchase purchase : purchases) {
            if (!validSignature(purchase)) {
                error(ErrorType.SIGNATURE_VERIFICATION_FAILED, "Purchase signature/package verification failed");
                continue;
            }
            if (ledger.isConsumed(purchase.getPurchaseToken())) continue;
            for (String id : purchase.getProducts()) {
                ProductInfo info; synchronized (catalog) { info = catalog.get(id); }
                SkuProductType type = configuredTypes.get(id);
                if (type == null && info != null) type = info.getSkuProductType();
                if (type == null && SUBS.equals(queryType)) type = SkuProductType.SUBSCRIPTION;
                if (type == null && flow != null && flow.oldToken != null && id.equals(flow.oldProductId))
                    type = SkuProductType.SUBSCRIPTION;
                if (type == null) {
                    type = SkuProductType.UNKNOWN;
                    error(ErrorType.PURCHASE_TYPE_UNKNOWN, "Configure the purchased product type: " + id);
                }
                Map<String, String> metadata = localMetadataByToken.get(purchase.getPurchaseToken());
                if (metadata == null) metadata = Collections.emptyMap();
                if (flow != null && matches(flow, purchase)) {
                    metadata = flow.params.getCustomParams();
                    localMetadataByToken.put(purchase.getPurchaseToken(), metadata);
                }
                if (metadata.isEmpty()) {
                    for (PurchaseInfo cached : ledger.values())
                        if (cached.getPurchaseToken().equals(purchase.getPurchaseToken()) && cached.getProduct().equals(id)) {
                            metadata = cached.getCustomParams(); break;
                        }
                }
                PurchaseInfo converted = new PurchaseInfo(id, type, info, purchase, metadata);
                if (acknowledgedTokens.contains(converted.getPurchaseToken())) converted.markAcknowledged();
                result.add(converted);
            }
        }
        return result;
    }
    private List<PurchaseLedger.Entry<PurchaseInfo>> toEntries(List<PurchaseInfo> purchases) {
        List<PurchaseLedger.Entry<PurchaseInfo>> result = new ArrayList<>();
        for (PurchaseInfo info : purchases) result.add(new PurchaseLedger.Entry<>(info.getPurchaseToken(),
                info.getProduct(), info.getSkuProductType() == SkuProductType.SUBSCRIPTION ? SUBS : INAPP,
                info.getPurchaseState(), info));
        return result;
    }
    private List<PurchaseInfo> currentForType(String type) {
        List<PurchaseInfo> result = new ArrayList<>();
        for (PurchaseLedger.Entry<PurchaseInfo> entry : ledger.entries()) if (entry.type.equals(type)) result.add(entry.value);
        return result;
    }
    private void onPurchasesUpdated(BillingResult result, @Nullable List<Purchase> purchases) {
        if (result.getResponseCode() != OK) {
            activeFlow = null; error(errorType(result.getResponseCode()), result);
            if (result.getResponseCode() == ITEM_ALREADY_OWNED) refreshPurchases();
            return;
        }
        if (purchases == null || purchases.isEmpty()) { refreshPurchases(); return; }
        List<PurchaseInfo> converted = convertPurchases(purchases, null, activeFlow);
        for (PurchaseLedger.Entry<PurchaseInfo> entry : toEntries(converted)) ledger.upsert(entry);
        // Use accepted ledger entries, so stale pending callbacks cannot downgrade a completed purchase.
        List<PurchaseInfo> accepted = new ArrayList<>();
        for (PurchaseInfo info : ledger.values()) for (PurchaseInfo candidate : converted)
            if (info.equals(candidate)) { accepted.add(info); break; }
        deliverEvents(accepted, true);
        correlateFlow(accepted);
        if (converted.isEmpty()) activeFlow = null;
    }
    private void deliverEvents(List<PurchaseInfo> purchases, boolean live) {
        List<PurchaseInfo> completed = new ArrayList<>(), pending = new ArrayList<>(), suspended = new ArrayList<>();
        Set<String> finalizeOnce = new HashSet<>();
        List<PurchaseInfo> autoFinalize = new ArrayList<>();
        for (PurchaseInfo info : purchases) {
            String stateKey = info.getPurchaseToken() + ":" + info.getProduct() + ":" + info.getPurchaseState()
                    + ":" + info.isSuspended();
            boolean first = deliveredStates.add(stateKey);
            if (first) {
                if (info.isPending()) pending.add(info);
                else if (info.isSuspended()) suspended.add(info);
                else if (info.isPurchased() && live) completed.add(info);
            }
            Purchase.PendingPurchaseUpdate update = info.getPendingPurchaseUpdate();
            if (update != null && deliveredPendingUpdates.add(info.getPurchaseToken() + ":" + update.getPurchaseToken()))
                emit(l -> l.onPendingPurchaseUpdate(info, update));
            if (info.isPurchased()) {
                String target = deferredTargets.remove(info.getPurchaseToken());
                if (target != null) emit(l -> l.onSubscriptionChangeScheduled(target));
                if (finalizeOnce.add(info.getPurchaseToken())) autoFinalize.add(info);
            }
        }
        if (!completed.isEmpty()) emit(l -> l.onProductsPurchased(Collections.unmodifiableList(completed)));
        if (!pending.isEmpty()) emit(l -> l.onPurchasesPending(Collections.unmodifiableList(pending)));
        if (!suspended.isEmpty()) emit(l -> l.onPurchasesSuspended(Collections.unmodifiableList(suspended)));
        // Deliver events first. Async server integrations should leave auto-finalization disabled.
        handler.post(() -> {
            if (released) return;
            for (PurchaseInfo info : autoFinalize) {
                if (autoConsume && info.getSkuProductType() == SkuProductType.CONSUMABLE) consumePurchase(info);
                else if (autoAcknowledge && (info.getSkuProductType() == SkuProductType.SUBSCRIPTION
                        || info.getSkuProductType() == SkuProductType.NON_CONSUMABLE)) acknowledgePurchase(info);
            }
        });
    }
    private boolean matches(Flow flow, Purchase purchase) {
        if (flow.oldToken == null) return purchase.getProducts().contains(flow.productId);
        return !flow.oldToken.equals(purchase.getPurchaseToken()) &&
                (purchase.getProducts().contains(flow.productId) || purchase.getProducts().contains(flow.oldProductId));
    }
    private void correlateFlow(List<PurchaseInfo> purchases) {
        Flow flow = activeFlow; if (flow == null) return;
        for (PurchaseInfo info : purchases) {
            Purchase.PendingPurchaseUpdate update = info.getPendingPurchaseUpdate();
            if (flow.oldToken != null && flow.oldToken.equals(info.getPurchaseToken()) && update != null
                    && update.getProducts().contains(flow.productId)) {
                activeFlow = null;
                localMetadataByToken.put(update.getPurchaseToken(), flow.params.getCustomParams());
                if (flow.mode == 5) deferredTargets.put(update.getPurchaseToken(), flow.productId);
                return;
            }
            if (matches(flow, info.getPurchase())) {
                activeFlow = null;
                if (flow.oldToken != null && flow.mode == 5) {
                    if (info.isPurchased()) emit(l -> l.onSubscriptionChangeScheduled(flow.productId));
                    else if (info.isPending()) deferredTargets.put(info.getPurchaseToken(), flow.productId);
                }
                return;
            }
        }
    }

    public void consumePurchase(@NonNull PurchaseInfo info) {
        requireUsable();
        if (info.getSkuProductType() != SkuProductType.CONSUMABLE) {
            error(ErrorType.CONSUME_ERROR, "Only configured consumable products can be consumed"); return;
        }
        for (PurchaseInfo cached : ledger.values()) if (cached.getPurchaseToken().equals(info.getPurchaseToken())
                && cached.getSkuProductType() != SkuProductType.CONSUMABLE) {
            error(ErrorType.CONSUME_ERROR, "Token also contains a nonconsumable product"); return;
        }
        finalizePurchase(info, true);
    }
    public void acknowledgePurchase(@NonNull PurchaseInfo info) {
        requireUsable(); finalizePurchase(info, false);
    }
    private void finalizePurchase(PurchaseInfo info, boolean consume) {
        if (!info.isPurchased()) {
            error(consume ? ErrorType.CONSUME_WARNING : ErrorType.ACKNOWLEDGE_WARNING,
                    "Only completed, nonsuspended purchases can be finalized"); return;
        }
        if (!validSignature(info.getPurchase())) {
            error(ErrorType.SIGNATURE_VERIFICATION_FAILED, "Cannot finalize an unverified purchase"); return;
        }
        String token = info.getPurchaseToken();
        if (ledger.isConsumed(token)) return;
        if (!consume && (info.isAcknowledged() || acknowledgedTokens.contains(token))) return;
        if (!finalizingTokens.add(token)) return;
        finishAttempt(info, consume, 0);
    }
    private void finishAttempt(PurchaseInfo info, boolean consume, int attempt) {
        if (released) return;
        if (!billingClient.isReady()) {
            finalizationResult(info, consume, attempt, BillingResult.newBuilder()
                    .setResponseCode(SERVICE_DISCONNECTED).setDebugMessage("Billing service disconnected").build());
            return;
        }
        if (consume) billingClient.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(info.getPurchaseToken()).build(),
                (result, token) -> onMain(() -> finalizationResult(info, true, attempt, result)));
        else billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(info.getPurchaseToken()).build(),
                result -> onMain(() -> finalizationResult(info, false, attempt, result)));
    }
    private void finalizationResult(PurchaseInfo info, boolean consume, int attempt, BillingResult result) {
        String token = info.getPurchaseToken();
        if (result.getResponseCode() == OK) {
            finalizingTokens.remove(token);
            if (consume) { ledger.consume(token); pollingTokens.remove(token); emit(l -> l.onPurchaseConsumed(info)); }
            else {
                acknowledgedTokens.add(token); info.markAcknowledged();
                for (PurchaseInfo cached : ledger.values()) if (cached.getPurchaseToken().equals(token)) cached.markAcknowledged();
                emit(l -> l.onPurchaseAcknowledged(info));
            }
        } else if (BillingRules.isTransient(result.getResponseCode()) && attempt + 1 < MAX_ATTEMPTS) {
            handler.postDelayed(() -> { if (!released) finishAttempt(info, consume, attempt + 1); }, BillingRules.retryDelay(attempt));
        } else {
            finalizingTokens.remove(token);
            error(consume ? ErrorType.CONSUME_ERROR : ErrorType.ACKNOWLEDGE_ERROR, result);
            if (result.getResponseCode() == ITEM_NOT_OWNED) refreshPurchases();
        }
    }

    public void retryPendingPurchase(String productId) {
        requireUsable(); if (!readyOrError()) return;
        PurchaseInfo found = null;
        for (PurchaseInfo info : ledger.values()) if (info.getProduct().equals(productId) && info.isPending()) { found = info; break; }
        if (found == null) { error(ErrorType.NOT_PENDING, "No pending purchase for " + productId); return; }
        if (pollingTokens.add(found.getPurchaseToken())) pollPending(found, 0);
    }
    private void pollPending(PurchaseInfo original, int attempt) {
        String token = original.getPurchaseToken();
        handler.postDelayed(() -> {
            if (released || !pollingTokens.contains(token)) return;
            PurchaseInfo current = null;
            for (PurchaseInfo info : ledger.values()) if (info.getPurchaseToken().equals(token)) { current = info; break; }
            if (current == null || !current.isPending()) { pollingTokens.remove(token); return; }
            String type = original.getSkuProductType() == SkuProductType.SUBSCRIPTION ? SUBS : INAPP;
            billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build(),
                    (result, purchases) -> onMain(() -> {
                        if (!pollingTokens.contains(token)) return;
                        if (result.getResponseCode() != OK) {
                            if (BillingRules.isTransient(result.getResponseCode()) && attempt + 1 < MAX_ATTEMPTS) pollPending(original, attempt + 1);
                            else { pollingTokens.remove(token); error(ErrorType.PENDING_PURCHASE_RETRY_ERROR, result); }
                            return;
                        }
                        List<PurchaseInfo> converted = convertPurchases(purchases, type, activeFlow);
                        PurchaseInfo match = null;
                        for (PurchaseInfo info : converted) if (info.getPurchaseToken().equals(token)) { match = info; break; }
                        if (match == null) {
                            pollingTokens.remove(token);
                            // Signature failure is not evidence of cancellation.
                            if (allSignaturesValid(purchases)) {
                                ledger.removeToken(token);
                                error(ErrorType.PENDING_PURCHASE_CANCELED, "Pending purchase no longer returned by Google Play");
                            }
                            return;
                        }
                        for (PurchaseLedger.Entry<PurchaseInfo> entry : toEntries(converted)) ledger.upsert(entry);
                        deliverEvents(converted, true);
                        if (match.isPurchased()) {
                            pollingTokens.remove(token);
                            correlateFlow(Collections.singletonList(match));
                        } else if (attempt + 1 < MAX_ATTEMPTS) pollPending(original, attempt + 1);
                        else {
                            pollingTokens.remove(token);
                            // Timeout only stops polling; the payment remains pending in the cache.
                            error(ErrorType.PENDING_PURCHASE_RETRY_ERROR, "Polling stopped; payment is still pending, not canceled");
                        }
                    }));
        }, BillingRules.retryDelay(attempt));
    }

    public final void purchase(Activity activity, String productId) { purchase(activity, productId, (PurchaseParams) null); }
    public final void purchase(Activity activity, String productId, PurchaseParams params) {
        startFlow(activity, productId, -1, null, params, null, null, 0, false);
    }
    /** Explicit one-time purchase option/offer token selected from ProductInfo.getOneTimePurchaseOffers(). */
    public final void purchaseWithOffer(Activity activity, String productId, String offerToken, PurchaseParams params) {
        startFlow(activity, productId, -1, offerToken, params, null, null, 0, false);
    }
    public final void subscribe(Activity activity, String productId) { subscribe(activity, productId, 0, null); }
    public final void subscribe(Activity activity, String productId, PurchaseParams params) { subscribe(activity, productId, 0, params); }
    public final void subscribe(Activity activity, String productId, int index) { subscribe(activity, productId, index, null); }
    public final void subscribe(Activity activity, String productId, int index, PurchaseParams params) {
        startFlow(activity, productId, index, null, params, null, null, 0, true);
    }
    public final void subscribeWithOfferToken(Activity activity, String productId, String offerToken, PurchaseParams params) {
        startFlow(activity, productId, -1, offerToken, params, null, null, 0, true);
    }
    /** Legacy replacement mode values are mapped to the new item-level values. */
    public final void changeSubscription(Activity activity, String newId, String oldId, String token,
                                         @BillingFlowParams.SubscriptionUpdateParams.ReplacementMode int mode) {
        changeSubscription(activity, newId, oldId, token, mode, 0, null);
    }
    public final void changeSubscription(Activity activity, String newId, String oldId, String token,
                                         @BillingFlowParams.SubscriptionUpdateParams.ReplacementMode int mode, int index, PurchaseParams params) {
        startFlow(activity, newId, index, null, params, requireText(token, "oldPurchaseToken"),
                requireText(oldId, "oldProductId"), BillingRules.productReplacementMode(mode), true);
    }
    /** Uses the NEW ProductDetailsParams replacement-mode constants, not the legacy constants. */
    public final void changeSubscriptionWithProductReplacementMode(Activity activity, String newId, String oldId,
                                                                   String token, int mode, String offerToken, PurchaseParams params) {
        if (mode < 1 || mode > 6) throw new IllegalArgumentException("Invalid product replacement mode");
        startFlow(activity, newId, -1, offerToken, params, requireText(token, "oldPurchaseToken"),
                requireText(oldId, "oldProductId"), mode, true);
    }
    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
    private void startFlow(Activity activity, String id, int index, String explicitOfferToken, PurchaseParams params,
                           String oldToken, String oldId, int mode, boolean subscription) {
        requireUsable(); if (!readyOrError()) return;
        if (activeFlow != null) { error(ErrorType.PURCHASE_FLOW_IN_PROGRESS, "Another purchase flow is in progress"); return; }
        if (activity == null || activity.isFinishing()) { error(ErrorType.DEVELOPER_ERROR, "A live Activity is required"); return; }
        SkuProductType configured = configuredTypes.get(id);
        if (configured == null) { error(ErrorType.PRODUCT_NOT_EXIST, "Product ID is not configured: " + id); return; }
        if (subscription != (configured == SkuProductType.SUBSCRIPTION)) {
            error(ErrorType.DEVELOPER_ERROR, "Product type does not match the purchase method"); return;
        }
        PurchaseParams selectedParams = params != null ? params.copy()
                : defaultPurchaseParams == null ? new PurchaseParams() : defaultPurchaseParams.copy();
        if (selectedParams.getObfuscatedProfileId() != null && selectedParams.getObfuscatedAccountId() == null) {
            error(ErrorType.DEVELOPER_ERROR, "Profile ID requires an obfuscated account ID"); return;
        }
        String selectedToken = explicitOfferToken;
        if (subscription && selectedToken == null && mode != 6) {
            ProductInfo cached; synchronized (catalog) { cached = catalog.get(id); }
            if (cached != null) {
                List<SubscriptionOfferDetails> offers = cached.getSubscriptionOfferDetails();
                if (index < 0 || index >= offers.size()) { error(ErrorType.DEVELOPER_ERROR, "Invalid offer index"); return; }
                selectedToken = offers.get(index).getOfferToken();
            }
        }
        Flow flow = new Flow(id, oldId, oldToken, mode, selectedParams);
        activeFlow = flow;
        String chosenToken = selectedToken;
        String type = subscription ? SUBS : INAPP;
        // Always obtain fresh ProductDetails immediately before launch.
        billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(
                        Collections.singletonList(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build())).build(),
                (result, details) -> onMain(() -> {
                    if (activeFlow != flow) return;
                    if (result.getResponseCode() != OK) { activeFlow = null; error(ErrorType.BILLING_ERROR, result); return; }
                    ProductDetails product = null;
                    for (ProductDetails detail : details.getProductDetailsList()) if (detail.getProductId().equals(id)) { product = detail; break; }
                    if (product == null) { activeFlow = null; error(ErrorType.PRODUCT_NOT_EXIST, "No eligible offer found for " + id); return; }
                    if (activity.isFinishing()) { activeFlow = null; error(ErrorType.DEVELOPER_ERROR, "Activity has finished"); return; }
                    try {
                        launchFresh(activity, product, index, chosenToken, flow, configured);
                    } catch (RuntimeException exception) {
                        activeFlow = null; error(ErrorType.DEVELOPER_ERROR, "Cannot launch billing flow: " + exception.getMessage());
                    }
                }));
    }
    private void launchFresh(Activity activity, ProductDetails product, int index, String token, Flow flow, SkuProductType type) {
        synchronized (catalog) { catalog.put(product.getProductId(), new ProductInfo(type, product)); }
        BillingFlowParams.ProductDetailsParams.Builder item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product);
        if (SUBS.equals(product.getProductType())) {
            if (flow.mode != 6) {
                List<ProductDetails.SubscriptionOfferDetails> offers = product.getSubscriptionOfferDetails();
                String validToken = null;
                if (offers != null) {
                    if (token != null) {
                        for (ProductDetails.SubscriptionOfferDetails offer : offers) if (token.equals(offer.getOfferToken())) validToken = token;
                    } else if (index >= 0 && index < offers.size()) validToken = offers.get(index).getOfferToken();
                }
                if (validToken == null) throw new IllegalArgumentException("Selected subscription offer is no longer eligible");
                item.setOfferToken(validToken);
            }
            if (flow.oldToken != null) item.setSubscriptionProductReplacementParams(
                    BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.newBuilder()
                            .setOldProductId(flow.oldProductId).setReplacementMode(flow.mode).build());
        } else {
            List<ProductDetails.OneTimePurchaseOfferDetails> offers = product.getOneTimePurchaseOfferDetailsList();
            if (offers != null && !offers.isEmpty()) {
                if (token == null && offers.size() > 1) throw new IllegalArgumentException("Multiple one-time offers; use purchaseWithOffer()");
                ProductDetails.OneTimePurchaseOfferDetails selected = null;
                for (ProductDetails.OneTimePurchaseOfferDetails offer : offers)
                    if (token == null || token.equals(offer.getOfferToken())) { selected = offer; break; }
                if (selected == null) throw new IllegalArgumentException("Selected one-time offer is no longer eligible");
                item.setOfferToken(selected.getOfferToken());
            } else if (token != null) throw new IllegalArgumentException("Selected one-time offer is unavailable");
        }
        BillingFlowParams.Builder builder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(item.build()))
                .setIsOfferPersonalized(flow.params.isOfferPersonalized());
        if (flow.params.getObfuscatedAccountId() != null) builder.setObfuscatedAccountId(flow.params.getObfuscatedAccountId());
        if (flow.params.getObfuscatedProfileId() != null) builder.setObfuscatedProfileId(flow.params.getObfuscatedProfileId());
        if (flow.oldToken != null) builder.setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                .setOldPurchaseToken(flow.oldToken).build());
        BillingResult result = billingClient.launchBillingFlow(activity, builder.build());
        if (result.getResponseCode() != OK) { activeFlow = null; error(errorType(result.getResponseCode()), result); }
    }

    /** Shows all currently eligible Play Billing transactional in-app messages. */
    public BillingResult showInAppMessages(@NonNull Activity activity,
                                           @NonNull InAppMessageResponseListener responseListener) {
        requireUsable();
        if (activity.isFinishing()) {
            return BillingResult.newBuilder().setResponseCode(DEVELOPER_ERROR)
                    .setDebugMessage("A live Activity is required").build();
        }
        InAppMessageParams params = InAppMessageParams.newBuilder()
                .addAllInAppMessageCategoriesToShow()
                .build();
        return billingClient.showInAppMessages(activity, params, responseListener);
    }

    /** Shows selected Play Billing in-app message categories. */
    public BillingResult showInAppMessages(@NonNull Activity activity,
                                           @NonNull List<Integer> categoryIds,
                                           @NonNull InAppMessageResponseListener responseListener) {
        requireUsable();
        if (activity.isFinishing()) {
            return BillingResult.newBuilder().setResponseCode(DEVELOPER_ERROR)
                    .setDebugMessage("A live Activity is required").build();
        }
        InAppMessageParams.Builder params = InAppMessageParams.newBuilder();
        if (categoryIds.isEmpty()) params.addAllInAppMessageCategoriesToShow();
        else for (Integer categoryId : categoryIds) {
            if (categoryId != null) params.addInAppMessageCategoryToShow(categoryId);
        }
        return billingClient.showInAppMessages(activity, params.build(), responseListener);
    }

    public SupportState isInAppMessagingSupported() {
        return support(BillingClient.FeatureType.IN_APP_MESSAGING);
    }

    public SupportState isSubscriptionSupported() { return support(BillingClient.FeatureType.SUBSCRIPTIONS); }
    public SupportState isSubscriptionUpdateSupported() { return isSubscriptionSupported(); }
    private SupportState support(String feature) {
        if (!isReady()) return SupportState.DISCONNECTED;
        int code = billingClient.isFeatureSupported(feature).getResponseCode();
        return code == OK ? SupportState.SUPPORTED : code == SERVICE_DISCONNECTED ? SupportState.DISCONNECTED : SupportState.NOT_SUPPORTED;
    }
    public interface BillingConfigListener {
        void onBillingConfigReceived(@Nullable String countryCode);
        void onBillingConfigError(String message);
    }
    public void queryBillingConfig(@NonNull BillingConfigListener configListener) {
        requireUsable();
        if (!isReady()) { handler.post(() -> { if (!released) configListener.onBillingConfigError("Billing client is not ready"); }); return; }
        billingClient.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build(), (result, config) -> onMain(() -> {
            if (result.getResponseCode() == OK && config != null) configListener.onBillingConfigReceived(config.getCountryCode());
            else configListener.onBillingConfigError(result.getDebugMessage());
        }));
    }
    /** Opens management UI; cancellation is performed by the user, not this method. */
    public final void unsubscribe(Activity activity, String productId) { openSubscriptionManagement(activity, productId); }
    public void openSubscriptionManagement(Activity activity, String productId) {
        requireUsable();
        if (activity == null || activity.isFinishing()) { error(ErrorType.DEVELOPER_ERROR, "A live Activity is required"); return; }
        Uri uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
                .appendQueryParameter("package", activity.getPackageName()).appendQueryParameter("sku", productId).build();
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (RuntimeException exception) { error(ErrorType.BILLING_ERROR, "Cannot open subscription management: " + exception.getMessage()); }
    }
    /** Local nonsuspended ownership only. Backend determines expiry and authoritative entitlement. */
    public boolean isSubscriptionActive(String id) {
        for (PurchaseInfo info : ledger.values()) if (info.getProduct().equals(id)
                && info.getSkuProductType() == SkuProductType.SUBSCRIPTION && info.isPurchased()) return true;
        return false;
    }
    public boolean isPurchasePending(String id) {
        for (PurchaseInfo info : ledger.values()) if (info.getProduct().equals(id) && info.isPending()) return true;
        return false;
    }
    /** Diagnostic only; package visibility must never block the actual BillingClient connection. */
    public boolean isPlayStoreInstalled(@NonNull Context context) {
        try { context.getPackageManager().getPackageInfo("com.android.vending", 0); return true; }
        catch (PackageManager.NameNotFoundException exception) { return false; }
    }
    public List<PurchaseInfo> getPurchasedProductsList() { return Collections.unmodifiableList(ledger.values()); }
    public final PurchasedResult isPurchased(@NonNull ProductInfo info) { return isPurchased(info.getProduct()); }
    public final PurchasedResult isPurchased(@NonNull String id) {
        if (!isReady()) return PurchasedResult.CLIENT_NOT_READY;
        if (!fetchedPurchasedProducts) return PurchasedResult.PURCHASED_PRODUCTS_NOT_FETCHED_YET;
        boolean pending = false;
        for (PurchaseInfo info : ledger.values()) if (info.getProduct().equals(id)) {
            if (info.isPurchased()) return PurchasedResult.YES;
            if (info.isPending()) pending = true;
        }
        return pending ? PurchasedResult.PENDING : PurchasedResult.NO;
    }
    public final BillingConnector setDefaultPurchaseParams(@Nullable PurchaseParams params) {
        requireUsable(); defaultPurchaseParams = params == null ? null : params.copy(); return this;
    }
    @Nullable public final PurchaseParams getDefaultPurchaseParams() {
        requireMain(); return defaultPurchaseParams == null ? null : defaultPurchaseParams.copy();
    }
    public final BillingConnector clearDefaultPurchaseParams() { return setDefaultPurchaseParams(null); }

    private ErrorType errorType(int code) {
        switch (code) {
            case USER_CANCELED: return ErrorType.USER_CANCELED;
            case SERVICE_DISCONNECTED: return ErrorType.CLIENT_DISCONNECTED;
            case SERVICE_UNAVAILABLE: return ErrorType.SERVICE_UNAVAILABLE;
            case BILLING_UNAVAILABLE: case FEATURE_NOT_SUPPORTED: return ErrorType.BILLING_UNAVAILABLE;
            case ITEM_UNAVAILABLE: return ErrorType.ITEM_UNAVAILABLE;
            case DEVELOPER_ERROR: return ErrorType.DEVELOPER_ERROR;
            case ERROR: return ErrorType.ERROR;
            case ITEM_ALREADY_OWNED: return ErrorType.ITEM_ALREADY_OWNED;
            case ITEM_NOT_OWNED: return ErrorType.ITEM_NOT_OWNED;
            case NETWORK_ERROR: return ErrorType.NETWORK_ERROR;
            default: return ErrorType.BILLING_ERROR;
        }
    }
    @Override public void onResume(@NonNull LifecycleOwner owner) {
        if (released || listener == null || (consumableIds.isEmpty() && nonConsumableIds.isEmpty()
                && subscriptionIds.isEmpty())) return;
        onMain(() -> { if (billingClient.isReady()) refreshPurchases(); else connect(); });
    }
    public void release() {
        requireMain(); if (released) return;
        released = true; handler.removeCallbacksAndMessages(null);
        connecting = false; refreshing = false; refreshAgain = false; reconnectScheduled = false;
        fetchedPurchasedProducts = false; activeFlow = null; listener = null;
        finalizingTokens.clear(); pollingTokens.clear(); deliveredStates.clear(); deliveredPendingUpdates.clear(); localMetadataByToken.clear(); acknowledgedTokens.clear(); deferredTargets.clear(); ledger.clear();
        synchronized (catalog) { catalog.clear(); }
        if (lifecycle != null) lifecycle.removeObserver(this);
        billingClient.endConnection();
        log("Billing connection released");
    }
    @Override public void onDestroy(@NonNull LifecycleOwner owner) { release(); }
}
