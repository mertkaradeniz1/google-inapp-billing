package games.moisoni.google_inapp_billing;

import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.github.hariprasanths.bounceview.BounceView;

import java.util.ArrayList;
import java.util.List;

import games.moisoni.google_iab.BillingConnector;
import games.moisoni.google_iab.enums.ProductType;
import games.moisoni.google_iab.enums.PurchasedResult;
import games.moisoni.google_iab.enums.SupportState;
import games.moisoni.google_iab.listeners.BillingEventListener;
import games.moisoni.google_iab.models.BillingResponse;
import games.moisoni.google_iab.models.ProductInfo;
import games.moisoni.google_iab.models.PurchaseInfo;


/**
 * This is a sample app to demonstrate how to implement 'google-inapp-billing' library
 *
 * This standalone app won't work because it's just for reference.
 *
 * To see real results, you need to implement the below code into a real project
 * released on Play Console and create your own in-app product ids.
 */
public class JavaSampleActivity extends AppCompatActivity {

    private ImageView exitApp;

    private RelativeLayout purchaseConsumable;
    private RelativeLayout purchaseNonConsumable;
    private RelativeLayout purchaseSubscription;
    private RelativeLayout purchaseSubscriptionOfferOne;
    private RelativeLayout purchaseSubscriptionOfferTwo;
    private RelativeLayout cancelSubscription;

    private BillingConnector billingConnector;

    // List for example purposes to demonstrate how to manually acknowledge or consume purchases.
    private final List<PurchaseInfo> purchasedInfoList = new ArrayList<>();

    // List for example purposes to demonstrate how to synchronously check a purchase state.
    private final List<ProductInfo> fetchedProductInfoList = new ArrayList<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_layout);

        initViews();
        initializeBillingClient();
        clickListeners();
    }


    private void initializeBillingClient() {

        // Create a list with consumable ids.
        List<String> consumableIds = new ArrayList<>();
        consumableIds.add("consumable_id_1");
        consumableIds.add("consumable_id_2");
        consumableIds.add("consumable_id_3");

        // Create a list with non-consumable ids.
        List<String> nonConsumableIds = new ArrayList<>();
        nonConsumableIds.add("non_consumable_id_1");
        nonConsumableIds.add("non_consumable_id_2");
        nonConsumableIds.add("non_consumable_id_3");

        // Create a list with subscription ids.
        List<String> subscriptionIds = new ArrayList<>();
        subscriptionIds.add("subscription_id_1");
        subscriptionIds.add("subscription_id_2");
        subscriptionIds.add("subscription_id_3");

        billingConnector = new BillingConnector(
                this,
                "license_key",
                getLifecycle()
        )
                .setConsumableIds(consumableIds)
                .setNonConsumableIds(nonConsumableIds)
                .setSubscriptionIds(subscriptionIds)
                .autoAcknowledge()
                .autoConsume()
                .enableLogging();

        /*
         * IMPORTANT:
         * BillingEventListener must be set BEFORE connect().
         */
        billingConnector.setBillingEventListener(new BillingEventListener() {

            @Override
            public void onProductsFetched(@NonNull List<ProductInfo> productDetails) {

                String product;
                String price;

                for (ProductInfo productInfo : productDetails) {

                    product = productInfo.getProduct();
                    price = productInfo.getOneTimePurchaseOfferFormattedPrice();

                    if (product.equalsIgnoreCase("consumable_id_1")) {

                        Log.d(
                                "BillingConnector",
                                "Product fetched: " + product
                        );

                        Toast.makeText(
                                JavaSampleActivity.this,
                                "Product fetched: " + product,
                                Toast.LENGTH_SHORT
                        ).show();

                        Log.d(
                                "BillingConnector",
                                "Product price: " + price
                        );

                        Toast.makeText(
                                JavaSampleActivity.this,
                                "Product price: " + price,
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    // Similarly check for other ids.
                    fetchedProductInfoList.add(productInfo);
                }
            }


            @Override
            public void onPurchasedProductsFetched(
                    @NonNull ProductType productType,
                    @NonNull List<PurchaseInfo> purchases
            ) {

                /*
                 * This will be called even when no purchased products
                 * are returned by the API.
                 */

                switch (productType) {

                    case INAPP:
                        // Non-consumable / consumable products.
                        break;

                    case SUBS:
                        // Subscription products.
                        break;

                    case COMBINED:
                        // Triggered on activity start.
                        // Restore purchases.
                        break;
                }

                String product;

                for (PurchaseInfo purchaseInfo : purchases) {

                    product = purchaseInfo.getProduct();

                    if (product.equalsIgnoreCase("consumable_id_1")) {

                        Log.d(
                                "BillingConnector",
                                "Purchased product fetched: " + product
                        );

                        Toast.makeText(
                                JavaSampleActivity.this,
                                "Purchased product fetched: " + product,
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
            }


            @Override
            public void onProductsPurchased(
                    @NonNull List<PurchaseInfo> purchases
            ) {

                String product;
                String purchaseToken;

                for (PurchaseInfo purchaseInfo : purchases) {

                    product = purchaseInfo.getProduct();
                    purchaseToken = purchaseInfo.getPurchaseToken();

                    if (product.equalsIgnoreCase("consumable_id_1")) {

                        Log.d(
                                "BillingConnector",
                                "Product purchased: " + product
                        );

                        Toast.makeText(
                                JavaSampleActivity.this,
                                "Product purchased: " + product,
                                Toast.LENGTH_SHORT
                        ).show();

                        Log.d(
                                "BillingConnector",
                                "Purchase token: " + purchaseToken
                        );

                        Toast.makeText(
                                JavaSampleActivity.this,
                                "Purchase token: " + purchaseToken,
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    purchasedInfoList.add(purchaseInfo);
                }
            }


            @Override
            public void onPurchaseAcknowledged(
                    @NonNull PurchaseInfo purchase
            ) {

                /*
                 * Grant user entitlement for NON-CONSUMABLE products
                 * and SUBSCRIPTIONS here.
                 *
                 * Google will refund purchases that aren't acknowledged
                 * within the required period.
                 */

                String acknowledgedProduct = purchase.getProduct();

                if (acknowledgedProduct.equalsIgnoreCase("consumable_id_1")) {

                    Log.d(
                            "BillingConnector",
                            "Acknowledged: " + acknowledgedProduct
                    );

                    Toast.makeText(
                            JavaSampleActivity.this,
                            "Acknowledged: " + acknowledgedProduct,
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }


            @Override
            public void onPurchaseConsumed(
                    @NonNull PurchaseInfo purchase
            ) {

                /*
                 * Grant user entitlement for CONSUMABLE products here.
                 */

                String consumedProduct = purchase.getProduct();

                if (consumedProduct.equalsIgnoreCase("consumable_id_1")) {

                    Log.d(
                            "BillingConnector",
                            "Consumed: " + consumedProduct
                    );

                    Toast.makeText(
                            JavaSampleActivity.this,
                            "Consumed: " + consumedProduct,
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }


            @Override
            public void onProductQueryError(
                    @NonNull String productId,
                    @NonNull BillingResponse response
            ) {

                Log.d(
                        "BillingConnector",
                        "Product ID not found: " + productId
                );

                Toast.makeText(
                        JavaSampleActivity.this,
                        "Product ID not found: " + productId,
                        Toast.LENGTH_SHORT
                ).show();
            }


            @Override
            public void onBillingError(
                    @NonNull BillingConnector billingConnector,
                    @NonNull BillingResponse response
            ) {

                switch (response.getErrorType()) {

                    case CLIENT_NOT_READY:
                        break;

                    case CLIENT_DISCONNECTED:
                        break;

                    case PRODUCT_NOT_EXIST:
                        break;

                    case CONSUME_ERROR:
                        break;

                    case CONSUME_WARNING:
                        /*
                         * Consumable purchase may still be PENDING.
                         */
                        break;

                    case ACKNOWLEDGE_ERROR:
                        break;

                    case ACKNOWLEDGE_WARNING:
                        /*
                         * Pending purchases cannot be acknowledged
                         * until their state becomes PURCHASED.
                         */
                        break;

                    case FETCH_PURCHASED_PRODUCTS_ERROR:
                        break;

                    case BILLING_ERROR:
                        break;

                    case USER_CANCELED:
                        break;

                    case SERVICE_UNAVAILABLE:
                        break;

                    case NETWORK_ERROR:
                        break;

                    case BILLING_UNAVAILABLE:
                        break;

                    case ITEM_UNAVAILABLE:
                        break;

                    case DEVELOPER_ERROR:
                        break;

                    case ERROR:
                        break;

                    case ITEM_ALREADY_OWNED:
                        break;

                    case ITEM_NOT_OWNED:
                        break;

                    case PLAY_STORE_NOT_INSTALLED:
                        break;
                }

                Log.d(
                        "BillingConnector",
                        "Error type: " + response.getErrorType()
                                + " Response code: " + response.getResponseCode()
                                + " Message: " + response.getDebugMessage()
                );

                Toast.makeText(
                        JavaSampleActivity.this,
                        "Error type: " + response.getErrorType()
                                + " Response code: " + response.getResponseCode()
                                + " Message: " + response.getDebugMessage(),
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        /*
         * Listener is registered.
         * Now it is safe to connect.
         */
        billingConnector.connect();
    }


    private void initViews() {

        purchaseConsumable = findViewById(R.id.purchase_consumable);
        purchaseNonConsumable = findViewById(R.id.purchase_non_consumable);
        purchaseSubscription = findViewById(R.id.purchase_subscription);

        purchaseSubscriptionOfferOne =
                findViewById(R.id.purchase_subscription_offer_one);

        purchaseSubscriptionOfferTwo =
                findViewById(R.id.purchase_subscription_offer_two);

        cancelSubscription =
                findViewById(R.id.cancel_subscription);

        exitApp =
                findViewById(R.id.exit_app);

        BounceView.addAnimTo(purchaseConsumable);
        BounceView.addAnimTo(purchaseNonConsumable);
        BounceView.addAnimTo(purchaseSubscription);
        BounceView.addAnimTo(purchaseSubscriptionOfferOne);
        BounceView.addAnimTo(purchaseSubscriptionOfferTwo);
        BounceView.addAnimTo(cancelSubscription);
        BounceView.addAnimTo(exitApp);
    }


    private void clickListeners() {

        // Purchase consumable.
        purchaseConsumable.setOnClickListener(
                v -> billingConnector.purchase(
                        JavaSampleActivity.this,
                        "consumable_id_1"
                )
        );

        // Purchase non-consumable.
        purchaseNonConsumable.setOnClickListener(
                v -> billingConnector.purchase(
                        JavaSampleActivity.this,
                        "non_consumable_id_2"
                )
        );

        // Purchase subscription with base plan.
        purchaseSubscription.setOnClickListener(
                v -> billingConnector.subscribe(
                        JavaSampleActivity.this,
                        "subscription_id_1"
                )
        );

        // Purchase subscription with offer index 0.
        purchaseSubscriptionOfferOne.setOnClickListener(
                v -> billingConnector.subscribe(
                        JavaSampleActivity.this,
                        "subscription_id_2",
                        0
                )
        );

        // Purchase subscription with offer index 1.
        purchaseSubscriptionOfferTwo.setOnClickListener(
                v -> billingConnector.subscribe(
                        JavaSampleActivity.this,
                        "subscription_id_2",
                        1
                )
        );

        // Cancel subscription.
        cancelSubscription.setOnClickListener(
                v -> billingConnector.unsubscribe(
                        JavaSampleActivity.this,
                        "subscription_id_1"
                )
        );

        exitApp.setOnClickListener(v -> finish());
    }


    /*
     * Check this method to learn how to implement useful public methods
     * provided by 'google-inapp-billing' library.
     */
    @SuppressWarnings("unused")
    private void usefulPublicMethods() {

        /*
         * Returns the state of the billing client.
         */
        if (billingConnector.isReady()) {
            Log.d(
                    "BillingConnector",
                    "Billing client is ready"
            );
        }


        /*
         * Check device support for subscriptions.
         */
        if (billingConnector.isSubscriptionSupported() == SupportState.SUPPORTED) {

            Log.d(
                    "BillingConnector",
                    "Device subscription support: SUPPORTED"
            );

        } else if (
                billingConnector.isSubscriptionSupported()
                        == SupportState.NOT_SUPPORTED
        ) {

            Log.d(
                    "BillingConnector",
                    "Device subscription support: NOT_SUPPORTED"
            );

        } else if (
                billingConnector.isSubscriptionSupported()
                        == SupportState.DISCONNECTED
        ) {

            Log.d(
                    "BillingConnector",
                    "Device subscription support: client DISCONNECTED"
            );
        }


        /*
         * Synchronously check purchase state.
         */
        for (ProductInfo productInfo : fetchedProductInfoList) {

            if (billingConnector.isPurchased(productInfo) == PurchasedResult.YES) {

                Log.d(
                        "BillingConnector",
                        "The product: "
                                + productInfo.getProduct()
                                + " is purchased"
                );

            } else if (
                    billingConnector.isPurchased(productInfo)
                            == PurchasedResult.NO
            ) {

                Log.d(
                        "BillingConnector",
                        "The product: "
                                + productInfo.getProduct()
                                + " is not purchased"
                );

            } else if (
                    billingConnector.isPurchased(productInfo)
                            == PurchasedResult.CLIENT_NOT_READY
            ) {

                Log.d(
                        "BillingConnector",
                        "Cannot check: "
                                + productInfo.getProduct()
                                + " because client is not ready"
                );

            } else if (
                    billingConnector.isPurchased(productInfo)
                            == PurchasedResult.PURCHASED_PRODUCTS_NOT_FETCHED_YET
            ) {

                Log.d(
                        "BillingConnector",
                        "Cannot check: "
                                + productInfo.getProduct()
                                + " because purchased products are not fetched yet"
                );
            }
        }


        /*
         * Consume consumable purchases.
         */
        for (PurchaseInfo purchaseInfo : purchasedInfoList) {
            billingConnector.consumePurchase(purchaseInfo);
        }


        /*
         * Acknowledge non-consumable products and subscriptions.
         */
        for (PurchaseInfo purchaseInfo : purchasedInfoList) {
            billingConnector.acknowledgePurchase(purchaseInfo);
        }


        // Purchase a product.
        billingConnector.purchase(
                JavaSampleActivity.this,
                "product_id"
        );

        // Subscribe using a base plan.
        billingConnector.subscribe(
                JavaSampleActivity.this,
                "product_id"
        );

        // Subscribe using a selected offer.
        billingConnector.subscribe(
                JavaSampleActivity.this,
                "product_id",
                1
        );

        // Open subscription cancellation.
        billingConnector.unsubscribe(
                JavaSampleActivity.this,
                "product_id"
        );
    }
}