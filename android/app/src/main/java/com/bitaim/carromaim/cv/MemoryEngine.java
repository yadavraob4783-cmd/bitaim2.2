package com.bitaim.carromaim.cv;

import android.util.Log;

public class MemoryEngine {
    private static final String TAG = "MemoryEngine";
    private static boolean isLoaded = false;

    static {
        try {
            System.loadLibrary("carromaim");
            isLoaded = true;
            Log.i(TAG, "Native library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
        }
    }

    /**
     * Initializes the native engine.
     * @return Confirmation string from C++
     */
    public static native String stringFromJNI();

    /**
     * Scans /proc/maps for the Carrom Pool memory module base address.
     * NOTE: This requires the app to either run as Root, or be injected into the game's process.
     * @return Base memory address, or 0 if not found/no permission.
     */
    public static native long findTargetBaseAddress();
    
    public static boolean isReady() {
        return isLoaded;
    }
}
