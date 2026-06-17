package com.bitaim.carromaim.cv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * TFLiteDetector - AI Engine for Object Detection
 * Replaces the pixel-based CV approach.
 */
public class TFLiteDetector {

    private static final String TAG = "TFLiteDetector";
    private static final String MODEL_FILE = "carrom_model.tflite"; // Placeholder for the actual model
    
    // Model specific parameters (Will be adjusted based on the actual .tflite model provided)
    private static final int INPUT_SIZE = 320; // Example: 320x320 YOLO/SSD input
    private static final boolean IS_QUANTIZED = false; // true if int8 model, false if float32

    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private boolean isInitialized = false;

    public TFLiteDetector(Context context) {
        initModel(context);
    }

    private void initModel(Context context) {
        try {
            MappedByteBuffer modelFile = FileUtil.loadMappedFile(context, MODEL_FILE);
            Interpreter.Options options = new Interpreter.Options();

            // Try to use GPU hardware acceleration for faster detection
            CompatibilityList compatList = new CompatibilityList();
            if (compatList.isDelegateSupportedOnThisDevice()) {
                gpuDelegate = new GpuDelegate(compatList.getBestOptionsForThisDevice());
                options.addDelegate(gpuDelegate);
                Log.i(TAG, "GPU Delegate added to TFLite.");
            } else {
                options.setNumThreads(4); // Fallback to CPU threads
                Log.i(TAG, "GPU not supported. Using CPU threads.");
            }

            interpreter = new Interpreter(modelFile, options);
            isInitialized = true;
            Log.i(TAG, "TFLite model loaded successfully!");

        } catch (IOException e) {
            Log.e(TAG, "Error reading model file: " + e.getMessage());
            isInitialized = false;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error initializing TFLite (Is model file missing?): " + e.getMessage());
            isInitialized = false;
        }
    }

    /**
     * Converts the Android Bitmap into a ByteBuffer tensor for the AI model.
     */
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer;
        if (IS_QUANTIZED) {
            byteBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3);
        } else {
            byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        }
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false);
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.getWidth(), 0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                final int val = intValues[pixel++];
                if (IS_QUANTIZED) {
                    byteBuffer.put((byte) ((val >> 16) & 0xFF));
                    byteBuffer.put((byte) ((val >> 8) & 0xFF));
                    byteBuffer.put((byte) (val & 0xFF));
                } else {
                    byteBuffer.putFloat((((val >> 16) & 0xFF) - 127.5f) / 127.5f);
                    byteBuffer.putFloat((((val >> 8) & 0xFF) - 127.5f) / 127.5f);
                    byteBuffer.putFloat(((val & 0xFF) - 127.5f) / 127.5f);
                }
            }
        }
        if (scaledBitmap != bitmap) scaledBitmap.recycle();
        return byteBuffer;
    }

    /**
     * Runs the AI inference on the given frame.
     * @return List of detected coins (bounding boxes and classes)
     */
    public List<Coin> detectObjects(Bitmap srcBmp, RectF boardRect) {
        if (!isInitialized || interpreter == null || srcBmp == null) {
            return new ArrayList<>();
        }

        // 1. Preprocess: Convert Image to Tensor
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(srcBmp);

        // 2. Placeholder Output Arrays (Depends on YOLO vs SSD output shape)
        // Assuming a standard SSD MobileNet shape for demonstration: [1, 10, 4] for boxes, [1, 10] for classes
        float[][][] outputLocations = new float[1][10][4];
        float[][] outputClasses = new float[1][10];
        float[][] outputScores = new float[1][10];
        float[] numDetections = new float[1];

        Object[] inputArray = {inputBuffer};
        java.util.Map<Integer, Object> outputMap = new java.util.HashMap<>();
        outputMap.put(0, outputLocations);
        outputMap.put(1, outputClasses);
        outputMap.put(2, outputScores);
        outputMap.put(3, numDetections);

        // 3. Run AI Inference
        try {
            // interpreter.runForMultipleInputsOutputs(inputArray, outputMap);
            // NOTE: The above line is commented out until we have the real .tflite model 
            // to avoid crashing due to shape mismatch.
            Log.d(TAG, "Inference skipped. Waiting for actual .tflite model file.");
        } catch (Exception e) {
            Log.e(TAG, "TFLite Inference Error: " + e.getMessage());
        }

        // 4. Post-process: Convert AI boxes to GameState Coins
        List<Coin> detectedCoins = new ArrayList<>();
        
        // This is where we will loop through outputLocations and outputClasses
        // and create new Coin(x, y, radius, color, isStriker) objects.

        return detectedCoins;
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        isInitialized = false;
    }

    public boolean isReady() {
        return isInitialized;
    }
}
