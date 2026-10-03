package games.moisoni.google_iab.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class PurchaseParams {

    private String obfuscatedAccountId;
    private String obfuscatedProfileId;
    private final Map<String, String> customParams;

    public PurchaseParams() {
        this.customParams = new HashMap<>();
    }

    /**
     * Set obfuscated account ID (Google Play built-in parameter)
     */
    public PurchaseParams setObfuscatedAccountId(String accountId) {
        this.obfuscatedAccountId = accountId;
        return this;
    }

    /**
     * Set obfuscated profile ID (Google Play built-in parameter)
     */
    public PurchaseParams setObfuscatedProfileId(String profileId) {
        this.obfuscatedProfileId = profileId;
        return this;
    }

    /**
     * Add a custom parameter
     * This will be stored as JSON in developerPayload
     */
    public PurchaseParams addCustomParam(String key, String value) {
        if (key != null && value != null) {
            this.customParams.put(key, value);
        }
        return this;
    }

    /**
     * Add a custom parameter (int)
     */
    public PurchaseParams addCustomParam(String key, int value) {
        if (key != null) {
            this.customParams.put(key, String.valueOf(value));
        }
        return this;
    }

    /**
     * Add a custom parameter (long)
     */
    public PurchaseParams addCustomParam(String key, long value) {
        if (key != null) {
            this.customParams.put(key, String.valueOf(value));
        }
        return this;
    }

    /**
     * Add a custom parameter (boolean)
     */
    public PurchaseParams addCustomParam(String key, boolean value) {
        if (key != null) {
            this.customParams.put(key, String.valueOf(value));
        }
        return this;
    }

    /**
     * Add multiple custom parameters at once
     */
    public PurchaseParams addCustomParams(Map<String, String> params) {
        if (params != null) {
            this.customParams.putAll(params);
        }
        return this;
    }

    /**
     * Remove a custom parameter
     */
    public PurchaseParams removeCustomParam(String key) {
        this.customParams.remove(key);
        return this;
    }

    /**
     * Clear all custom parameters
     */
    public PurchaseParams clearCustomParams() {
        this.customParams.clear();
        return this;
    }

    /**
     * Get obfuscated account ID
     */
    @Nullable
    public String getObfuscatedAccountId() {
        return obfuscatedAccountId;
    }

    /**
     * Get obfuscated profile ID
     */
    @Nullable
    public String getObfuscatedProfileId() {
        return obfuscatedProfileId;
    }

    /**
     * Get custom parameters as Map
     */
    @NonNull
    public Map<String, String> getCustomParams() {
        return new HashMap<>(customParams);
    }

    /**
     * Get a specific custom parameter
     */
    @Nullable
    public String getCustomParam(String key) {
        return customParams.get(key);
    }

    /**
     * Check if has custom parameters
     */
    public boolean hasCustomParams() {
        return !customParams.isEmpty();
    }

    /**
     * Convert custom parameters to JSON string for developerPayload
     */
    @Nullable
    public String getCustomParamsAsJson() {
        if (customParams.isEmpty()) {
            return null;
        }

        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : customParams.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Parse custom parameters from JSON string (from developerPayload)
     */
    public static Map<String, String> parseCustomParamsFromJson(String json) {
        Map<String, String> params = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return params;
        }

        try {
            JSONObject jsonObject = new JSONObject(json);
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                params.put(key, jsonObject.getString(key));
            }
        } catch (JSONException e) {
            // Return empty map on parse error
        }

        return params;
    }

    /**
     * Create a copy of this PurchaseParams
     */
    @NonNull
    public PurchaseParams copy() {
        PurchaseParams copy = new PurchaseParams();
        copy.obfuscatedAccountId = this.obfuscatedAccountId;
        copy.obfuscatedProfileId = this.obfuscatedProfileId;
        copy.customParams.putAll(this.customParams);
        return copy;
    }

    @NonNull
    @Override
    public String toString() {
        return "PurchaseParams{" +
                "obfuscatedAccountId='" + obfuscatedAccountId + '\'' +
                ", obfuscatedProfileId='" + obfuscatedProfileId + '\'' +
                ", customParams=" + customParams +
                '}';
    }
}