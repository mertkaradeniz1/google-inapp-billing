package games.moisoni.google_iab.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingResult;

import games.moisoni.google_iab.enums.ErrorType;

public class BillingResponse {

    private final ErrorType errorType;

    private final String debugMessage;
    private final int responseCode;
    private final int subResponseCode;
    private final Integer productQueryStatusCode;

    public BillingResponse(ErrorType errorType, String debugMessage, int responseCode) {
        this(errorType, debugMessage, responseCode, 0, null);
    }

    private BillingResponse(ErrorType errorType, String debugMessage, int responseCode,
                            int subResponseCode, Integer productQueryStatusCode) {
        this.errorType = errorType;
        this.debugMessage = debugMessage;
        this.responseCode = responseCode;
        this.subResponseCode = subResponseCode;
        this.productQueryStatusCode = productQueryStatusCode;
    }

    public BillingResponse(ErrorType errorType, @NonNull BillingResult billingResult) {
        this(errorType, billingResult.getDebugMessage(), billingResult.getResponseCode(),
                billingResult.getOnPurchasesUpdatedSubResponseCode(), null);
    }

    public static BillingResponse forUnfetchedProduct(String message, int statusCode) {
        return new BillingResponse(ErrorType.PRODUCT_ID_QUERY_FAILED, message, 99, 0, statusCode);
    }

    public int getSubResponseCode() { return subResponseCode; }
    @Nullable public Integer getProductQueryStatusCode() { return productQueryStatusCode; }

    public ErrorType getErrorType() {
        return errorType;
    }

    public String getDebugMessage() {
        return debugMessage;
    }

    public int getResponseCode() {
        return responseCode;
    }

    @NonNull
    @Override
    public String toString() {
        return "BillingResponse: Error type: " + errorType +
                " Response code: " + responseCode + " Message: " + debugMessage;
    }
}