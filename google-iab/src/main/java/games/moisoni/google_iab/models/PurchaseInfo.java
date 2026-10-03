package games.moisoni.google_iab.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AccountIdentifiers;
import com.android.billingclient.api.Purchase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import games.moisoni.google_iab.enums.SkuProductType;

public class PurchaseInfo {

    private final SkuProductType skuProductType;
    private final ProductInfo productInfo;
    private final Purchase purchase;

    private final String product;

    private final AccountIdentifiers accountIdentifiers;
    private final List<String> products;

    private final String orderId;
    private final String purchaseToken;
    private final String originalJson;
    private final String developerPayload;
    private final String packageName;
    private final String signature;

    private final int quantity;
    private final int purchaseState;

    private final long purchaseTime;

    private volatile boolean isAcknowledged;
    private final boolean isAutoRenewing;
    private final String obfuscatedAccountId;
    private final String obfuscatedProfileId;
    private final Map<String, String> customParams;

    public PurchaseInfo(@NonNull ProductInfo productInfo, @NonNull Purchase purchase) {
        this(productInfo.getProduct(), productInfo.getSkuProductType(), productInfo, purchase,
                Collections.emptyMap());
    }

    /** A purchase remains usable even when its catalog entry cannot be fetched. */
    public PurchaseInfo(@NonNull String productId, @NonNull SkuProductType productType,
                        @Nullable ProductInfo productInfo, @NonNull Purchase purchase,
                        @NonNull Map<String, String> localCustomParams) {
        this.productInfo = productInfo;
        this.purchase = purchase;
        this.product = productId;
        this.skuProductType = productType;
        this.accountIdentifiers = purchase.getAccountIdentifiers();
        this.products = Collections.unmodifiableList(new ArrayList<>(purchase.getProducts()));
        this.orderId = purchase.getOrderId();
        this.purchaseToken = purchase.getPurchaseToken();
        this.originalJson = purchase.getOriginalJson();
        this.developerPayload = purchase.getDeveloperPayload();
        this.packageName = purchase.getPackageName();
        this.signature = purchase.getSignature();
        this.quantity = purchase.getQuantity();
        this.purchaseState = purchase.getPurchaseState();
        this.purchaseTime = purchase.getPurchaseTime();
        this.isAcknowledged = purchase.isAcknowledged();
        this.isAutoRenewing = purchase.isAutoRenewing();

        // Obfuscated parametreleri al (varsa)
        AccountIdentifiers accountIds = purchase.getAccountIdentifiers();
        if (accountIds != null) {
            this.obfuscatedAccountId = accountIds.getObfuscatedAccountId();
            this.obfuscatedProfileId = accountIds.getObfuscatedProfileId();
        } else {
            this.obfuscatedAccountId = null;
            this.obfuscatedProfileId = null;
        }

        // Local app metadata, never parsed from deprecated developerPayload
        this.customParams = Collections.unmodifiableMap(new HashMap<>(localCustomParams));
    }

    public SkuProductType getSkuProductType() {
        return skuProductType;
    }

    @Nullable
    public ProductInfo getProductInfo() {
        return productInfo;
    }

    public Purchase getPurchase() {
        return purchase;
    }

    public String getProduct() {
        return product;
    }

    public AccountIdentifiers getAccountIdentifiers() {
        return accountIdentifiers;
    }

    public List<String> getProducts() {
        return Collections.unmodifiableList(products);
    }

    public String getOrderId() {
        return orderId;
    }

    public String getPurchaseToken() {
        return purchaseToken;
    }

    public String getOriginalJson() {
        return originalJson;
    }

    public String getDeveloperPayload() {
        return developerPayload;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSignature() {
        return signature;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getPurchaseState() {
        return purchaseState;
    }

    public long getPurchaseTime() {
        return purchaseTime;
    }

    public boolean isAcknowledged() {
        return isAcknowledged;
    }

    public void markAcknowledged() { this.isAcknowledged = true; }

    public boolean isSuspended() { return purchase.isSuspended(); }

    @Nullable
    public Purchase.PendingPurchaseUpdate getPendingPurchaseUpdate() {
        return purchase.getPendingPurchaseUpdate();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PurchaseInfo)) return false;
        PurchaseInfo info = (PurchaseInfo) other;
        return Objects.equals(purchaseToken, info.purchaseToken) && Objects.equals(product, info.product);
    }

    @Override
    public int hashCode() { return Objects.hash(purchaseToken, product); }

    public boolean isAutoRenewing() {
        return isAutoRenewing;
    }

    public boolean isPurchased() {
        return purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isSuspended();
    }

    public boolean isPending() {
        return purchaseState == Purchase.PurchaseState.PENDING;
    }

    /**
     * Returns obfuscated account ID if set during purchase
     */
    @Nullable
    public String getObfuscatedAccountId() {
        return obfuscatedAccountId;
    }

    /**
     * Returns obfuscated profile ID if set during purchase
     */
    @Nullable
    public String getObfuscatedProfileId() {
        return obfuscatedProfileId;
    }

    /**
     * Returns app-local metadata; Google does not transport these custom parameters
     */
    @NonNull
    public Map<String, String> getCustomParams() {
        return new HashMap<>(customParams);
    }

    /**
     * Returns a specific custom parameter value
     */
    @Nullable
    public String getCustomParam(String key) {
        return customParams.get(key);
    }

    /**
     * Check if custom parameters exist
     */
    public boolean hasCustomParams() {
        return !customParams.isEmpty();
    }
}