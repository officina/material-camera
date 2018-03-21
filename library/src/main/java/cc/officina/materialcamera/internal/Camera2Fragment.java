/*
 * Copyright (C) 2018 Officina S.r.l.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.officina.materialcamera.internal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import cc.officina.materialcamera.R;
import cc.officina.materialcamera.util.CameraUtil;
import cc.officina.materialcamera.util.Degrees;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Fragment extends BaseCameraFragment implements View.OnClickListener {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;
    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;
    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;
    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;
    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;
    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private boolean mStillshot;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mPreviewSession;
    private AutoFitTextureView mTextureView;
    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;
    private Size mPreviewSize;
    private Size mVideoSize;
    @Degrees.DegreeUnits
    private int mDisplayOrientation;
    private boolean mAfAvailable;
    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewBuilder;
    /**
     * {@link CaptureRequest} generated by {@link #mPreviewBuilder}
     */
    private CaptureRequest mPreviewRequest;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        SurfaceTexture surfaceTexture, int width, int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        SurfaceTexture surfaceTexture, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                }
            };
    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {

                private void process(CaptureResult result) {
                    switch (mState) {
                        case STATE_PREVIEW: {
                            // We have nothing to do when the camera preview is working normally.
                            break;
                        }
                        case STATE_WAITING_LOCK: {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                captureStillPicture();
                            } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState
                                    || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                                // CONTROL_AE_STATE can be null on some devices
                                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                    mState = STATE_PICTURE_TAKEN;
                                    captureStillPicture();
                                } else {
                                    runPrecaptureSequence();
                                }
                            }
                            break;
                        }
                        case STATE_WAITING_PRECAPTURE: {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null
                                    || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                    || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                                    || aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED) {
                                mState = STATE_WAITING_NON_PRECAPTURE;
                            }
                            break;
                        }
                        case STATE_WAITING_NON_PRECAPTURE: {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                mState = STATE_PICTURE_TAKEN;
                                captureStillPicture();
                            }
                            break;
                        }
                    }
                }

                @Override
                public void onCaptureProgressed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureResult partialResult) {
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                    process(result);
                }
            };
    private final CameraDevice.StateCallback mStateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    mCameraOpenCloseLock.release();
                    mCameraDevice = cameraDevice;
                    startPreview();
                    if (null != mTextureView) {
                        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
                    }
                    onCameraOpened();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    mCameraOpenCloseLock.release();
                    cameraDevice.close();
                    mCameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int error) {
                    mCameraOpenCloseLock.release();
                    cameraDevice.close();
                    mCameraDevice = null;

                    String errorMsg = "Unknown camera error";
                    switch (error) {
                        case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                            errorMsg = "Camera is already in use.";
                            break;
                        case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                            errorMsg = "Max number of cameras are open, close previous cameras first.";
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                            errorMsg = "Camera is disabled, e.g. due to device policies.";
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                            errorMsg = "Camera device has encountered a fatal error, please try again.";
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                            errorMsg = "Camera service has encountered a fatal error, please try again.";
                            break;
                    }
                    throwError(new Exception(errorMsg));
                }
            };

    public static Camera2Fragment newInstance() {
        Camera2Fragment fragment = new Camera2Fragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    private static Size chooseVideoSize(BaseCaptureInterface ci, Size[] choices) {
        Size backupSize = null;
        for (Size size : choices) {
            if (size.getHeight() <= ci.videoPreferredHeight()) {

                // from here we can choose a valid size
                final float preferredWidth = size.getHeight() * ci.videoPreferredAspect();
                if (size.getWidth() == preferredWidth) {
                    // exact resolution: return immediate
                    return size;
                } else {
                    // backup
                    if (backupSize == null) {
                        backupSize = size;
                    } else {
                        // get size according best aspect ratio and highest height
                        float backupSizeAspectRatio = backupSize.getWidth() * 1f / backupSize.getHeight() * 1f;
                        float newSizeAspectRatio = size.getWidth() * 1f / size.getHeight() * 1f;
                        float backupSizeAspectRatioDelta = Math.abs(ci.videoPreferredAspect() - backupSizeAspectRatio);
                        float newSizeAspectRatioDelta = Math.abs(ci.videoPreferredAspect() - newSizeAspectRatio);
                        if (newSizeAspectRatioDelta < backupSizeAspectRatioDelta) {
                            backupSize = size;
                        }
                    }
                }
            }
        }

        if (backupSize != null)
            return backupSize;

        LOG(Camera2Fragment.class, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w
                    && option.getWidth() >= width
                    && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            LOG(Camera2Fragment.class, "Couldn't find any suitable preview size");
            return aspectRatio;
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that is
     * at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size, and
     * whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(
            Size[] choices,
            int textureViewWidth,
            int textureViewHeight,
            int maxWidth,
            int maxHeight,
            Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth
                    && option.getHeight() <= maxHeight
                    && option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            LOG(Camera2Fragment.class, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            mTextureView.getSurfaceTexture().release();
        } catch (Throwable ignored) {
        }
        mTextureView = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        stopCounter();
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void openCamera() {
        final int width = mTextureView.getWidth();
        final int height = mTextureView.getHeight();

        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing())
            return;

        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throwError(new Exception("Time out waiting to lock camera opening."));
                return;
            }

            if (mInterface.getFrontCamera() == null || mInterface.getBackCamera() == null) {
                for (String cameraId : manager.getCameraIdList()) {
                    if (cameraId == null)
                        continue;
                    if (mInterface.getFrontCamera() != null && mInterface.getBackCamera() != null)
                        break;
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    //noinspection ConstantConditions
                    int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT)
                        mInterface.setFrontCamera(cameraId);
                    else if (facing == CameraCharacteristics.LENS_FACING_BACK)
                        mInterface.setBackCamera(cameraId);
                }
            }

            switch (mInterface.getCurrentCameraPosition()) {
                case BaseCaptureActivity.CAMERA_POSITION_FRONT:
                    setImageRes(mButtonFacing, mInterface.iconRearCamera());
                    break;
                case BaseCaptureActivity.CAMERA_POSITION_BACK:
                    setImageRes(mButtonFacing, mInterface.iconFrontCamera());
                    break;
                case BaseCaptureActivity.CAMERA_POSITION_UNKNOWN:
                default:
                    if (getArguments().getBoolean(CameraIntentKey.DEFAULT_TO_FRONT_FACING, false)) {
                        // Check front facing first
                        if (mInterface.getFrontCamera() != null) {
                            setImageRes(mButtonFacing, mInterface.iconRearCamera());
                            mInterface.setCameraPosition(BaseCaptureActivity.CAMERA_POSITION_FRONT);
                        } else {
                            setImageRes(mButtonFacing, mInterface.iconFrontCamera());
                            if (mInterface.getBackCamera() != null)
                                mInterface.setCameraPosition(BaseCaptureActivity.CAMERA_POSITION_BACK);
                            else
                                mInterface.setCameraPosition(BaseCaptureActivity.CAMERA_POSITION_UNKNOWN);
                        }
                    } else {
                        // Check back facing first
                        if (mInterface.getBackCamera() != null) {
                            setImageRes(mButtonFacing, mInterface.iconFrontCamera());
                            mInterface.setCameraPosition(BaseCaptureActivity.CAMERA_POSITION_BACK);
                        } else {
                            setImageRes(mButtonFacing, mInterface.iconRearCamera());
                            if (mInterface.getFrontCamera() != null)
                                mInterface.setCameraPosition(BaseCaptureActivity.CAMERA_POSITION_FRONT);
                            else
                                mInterface.setCameraPosition(BaseCaptureActivity.CAMERA_POSITION_UNKNOWN);
                        }
                    }
                    break;
            }

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics((String) mInterface.getCurrentCameraId());
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;

            // For still image captures, we use the largest available size.
            Size largest =
                    Collections.max(
                            Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
            // Find out if we need to swap dimension to get the preview size relative to sensor
            // coordinate.
            int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            //noinspection ConstantConditions,ResourceType
            @Degrees.DegreeUnits final int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            @Degrees.DegreeUnits int deviceRotation = Degrees.getDisplayRotation(getActivity());
            mDisplayOrientation =
                    Degrees.getDisplayOrientation(
                            sensorOrientation,
                            deviceRotation,
                            getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_FRONT);
            Log.d(
                    "Camera2Fragment",
                    String.format(
                            "Orientations: Sensor = %d˚, Device = %d˚, Display = %d˚",
                            sensorOrientation, deviceRotation, mDisplayOrientation));

            //if (mInterface.useStillshot()) {
            //    boolean swappedDimensions = false;
            //    switch (displayRotation) {
            //        case Surface.ROTATION_0:
            //        case Surface.ROTATION_180:
            //            if (sensorOrientation == Degrees.DEGREES_90
            //                    || sensorOrientation == Degrees.DEGREES_270) {
            //                swappedDimensions = true;
            //            }
            //            break;
            //        case Surface.ROTATION_90:
            //        case Surface.ROTATION_270:
            //            if (sensorOrientation == Degrees.DEGREES_0
            //                    || sensorOrientation == Degrees.DEGREES_180) {
            //                swappedDimensions = true;
            //            }
            //            break;
            //        default:
            //            Log.e("stillshot", "Display rotation is invalid: " + displayRotation);
            //    }
            //
            //    Point displaySize = new Point();
            //    activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
            //    int rotatedPreviewWidth = width;
            //    int rotatedPreviewHeight = height;
            //    int maxPreviewWidth = displaySize.x;
            //    int maxPreviewHeight = displaySize.y;
            //
            //    if (swappedDimensions) {
            //        rotatedPreviewWidth = height;
            //        rotatedPreviewHeight = width;
            //        maxPreviewWidth = displaySize.y;
            //        maxPreviewHeight = displaySize.x;
            //    }
            //
            //    if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
            //        maxPreviewWidth = MAX_PREVIEW_WIDTH;
            //    }
            //
            //    if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
            //        maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            //    }
            //
            //    // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            //    // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            //    // garbage capture data.
            //    mPreviewSize =
            //            chooseOptimalSize(
            //                    map.getOutputSizes(SurfaceTexture.class),
            //                    rotatedPreviewWidth,
            //                    rotatedPreviewHeight,
            //                    maxPreviewWidth,
            //                    maxPreviewHeight,
            //                    largest);
            //
            //    mImageReader =
            //            ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
            //    mImageReader.setOnImageAvailableListener(
            //            new ImageReader.OnImageAvailableListener() {
            //                @Override
            //                public void onImageAvailable(ImageReader reader) {
            //                    Image image = reader.acquireNextImage();
            //                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            //                    final byte[] bytes = new byte[buffer.remaining()];
            //                    buffer.get(bytes);
            //
            //                    final File outputPic = getOutputPictureFile();
            //
            //                    FileOutputStream output = null;
            //                    try {
            //                        output = new FileOutputStream(outputPic);
            //                        output.write(bytes);
            //                    } catch (IOException e) {
            //                        e.printStackTrace();
            //                    } finally {
            //                        image.close();
            //                        if (null != output) {
            //                            try {
            //                                output.close();
            //                            } catch (IOException e) {
            //                                e.printStackTrace();
            //                            }
            //                        }
            //                    }
            //                    Log.d("stillshot", "picture saved to disk - jpeg, size: " + bytes.length);
            //                    mOutputUri = Uri.fromFile(outputPic).toString();
            //                    mInterface.onShowStillshot(mOutputUri);
            //                }
            //            },
            //            mBackgroundHandler);
            //} else {
            //    mMediaRecorder = new MediaRecorder();
            //    mVideoSize =
            //            chooseVideoSize(
            //                    (BaseCaptureInterface) activity, map.getOutputSizes(MediaRecorder.class));
            //    mPreviewSize =
            //            chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, mVideoSize);
            //}

            boolean swappedDimensions = false;
            switch (displayRotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    if (sensorOrientation == Degrees.DEGREES_90
                            || sensorOrientation == Degrees.DEGREES_270) {
                        swappedDimensions = true;
                    }
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    if (sensorOrientation == Degrees.DEGREES_0
                            || sensorOrientation == Degrees.DEGREES_180) {
                        swappedDimensions = true;
                    }
                    break;
                default:
                    Log.e("stillshot", "Display rotation is invalid: " + displayRotation);
            }

            Point displaySize = new Point();
            activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
            int rotatedPreviewWidth = width;
            int rotatedPreviewHeight = height;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;

            if (swappedDimensions) {
                rotatedPreviewWidth = height;
                rotatedPreviewHeight = width;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }

            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH;
            }

            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            }

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            mPreviewSize =
                    chooseOptimalSize(
                            map.getOutputSizes(SurfaceTexture.class),
                            rotatedPreviewWidth,
                            rotatedPreviewHeight,
                            maxPreviewWidth,
                            maxPreviewHeight,
                            largest);

            mImageReader =
                    ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
            mImageReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            Image image = reader.acquireNextImage();
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            final byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);

                            final File outputPic = getOutputPictureFile();

                            FileOutputStream output = null;
                            try {
                                output = new FileOutputStream(outputPic);
                                output.write(bytes);
                            } catch (IOException e) {
                                e.printStackTrace();
                            } finally {
                                image.close();
                                if (null != output) {
                                    try {
                                        output.close();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            Log.d("stillshot", "picture saved to disk - jpeg, size: " + bytes.length);
                            mPictureOutputUri = Uri.fromFile(outputPic).toString();
                            mInterface.onShowStillshot(mPictureOutputUri);
                        }
                    },
                    mBackgroundHandler);

            // Initialize video related resources
            mMediaRecorder = new MediaRecorder();
            mVideoSize =
                    chooseVideoSize(
                            (BaseCaptureInterface) activity, map.getOutputSizes(MediaRecorder.class));
            //mPreviewSize =
            //        chooseOptimalSize(
            //                map.getOutputSizes(SurfaceTexture.class),
            //                width,
            //                height,
            //                mVideoSize);

            int orientation = VideoStreamView.getScreenOrientation(activity);
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }

            adjustControlFrame(orientation);

            mAfAvailable = false;
            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (afModes != null) {
                for (int i : afModes) {
                    if (i != 0) {
                        mAfAvailable = true;
                        break;
                    }
                }
            }

            configureTransform(width, height);

            mInterface.setFlashModes(CameraUtil.getSupportedFlashModes(getActivity(), characteristics));
            onFlashModesLoaded();

            // noinspection ResourceType
            manager.openCamera((String) mInterface.getCurrentCameraId(), mStateCallback, null);
        } catch (CameraAccessException e) {
            throwError(new Exception("Cannot access the camera.", e));
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            new ErrorDialog().show(getFragmentManager(), "dialog");
        } catch (InterruptedException e) {
            throwError(new Exception("Interrupted while trying to lock camera opening.", e));
        }
    }

    private void adjustControlFrame(final int screenOrientation) {
        mControlsFrame.setAlpha(0f);
        mTextureView.post(new Runnable() {
            @Override
            public void run() {
                if (mPreviewFrame == null || mTextureView == null || mControlsFrame == null) {
                    return;
                }

                if(screenOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        || screenOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                    int rootViewWidth = mPreviewFrame.getWidth();
                    int cameraPreviewWidth = mTextureView.getWidth();
                    int availableSpace = rootViewWidth - cameraPreviewWidth;

                    if (availableSpace > mControlsFrame.getWidth()) {
                        final ViewGroup.LayoutParams layoutParams = mControlsFrame.getLayoutParams();
                        layoutParams.width = availableSpace;
                        mControlsFrame.setLayoutParams(layoutParams);
                    }
                } else {
                    int rootViewHeight = mPreviewFrame.getHeight();
                    int cameraPreviewHeight = mTextureView.getHeight();
                    int availableSpace = rootViewHeight - cameraPreviewHeight;

                    if (availableSpace > mControlsFrame.getHeight()) {
                        final ViewGroup.LayoutParams layoutParams = mControlsFrame.getLayoutParams();
                        layoutParams.height = availableSpace;
                        mControlsFrame.setLayoutParams(layoutParams);
                    }
                }

                mControlsFrame.animate()
                        .alpha(1f)
                        .setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime))
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
            }
        });
    }

    @Override
    public void closeCamera() {
        try {
            if (mPictureOutputUri != null) {
                final File outputFile = new File(Uri.parse(mPictureOutputUri).getPath());
                if (outputFile.length() == 0)
                    outputFile.delete();
            }
            if (mVideoOutputUri != null) {
                final File outputFile = new File(Uri.parse(mVideoOutputUri).getPath());
                if (outputFile.length() == 0)
                    outputFile.delete();
            }
            mCameraOpenCloseLock.acquire();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throwError(new Exception("Interrupted while trying to lock camera opening.", e));
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    @Override
    public void onPreferencesUpdated() {
        if (mInterface == null
                //|| !mInterface.useStillshot()
                || mPreviewSession == null
                || mPreviewBuilder == null) {
            return;
        }
        setFlashMode(mPreviewBuilder);
        mPreviewRequest = mPreviewBuilder.build();
        try {
            mPreviewSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void startPreview() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize)
            return;
        try {
            //if (!mInterface.useStillshot()) {
            if (!setUpMediaRecorder()) {
                return;
            }
            //}
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            List<Surface> surfaces = new ArrayList<>();
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            //if (mInterface.useStillshot()) {
            //  mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //  mPreviewBuilder.addTarget(previewSurface);
            //
            //  surfaces.add(mImageReader.getSurface());
            //} else {
            //mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            //mPreviewBuilder.addTarget(previewSurface);
            //
            //Surface recorderSurface = mMediaRecorder.getSurface();
            //surfaces.add(recorderSurface);
            //mPreviewBuilder.addTarget(recorderSurface);
            //}
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mPreviewBuilder.addTarget(previewSurface);
            surfaces.add(mImageReader.getSurface());
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            mCameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (mCameraDevice == null) {
                                return;
                            }
                            mPreviewSession = cameraCaptureSession;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            throwError(new Exception("Camera configuration failed"));
                        }
                    },
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }

        try {
            //if (mInterface.useStillshot()) {
            //  mPreviewBuilder.set(
            //      CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            //  // Flash is automatically enabled when necessary.
            //  setFlashMode(mPreviewBuilder);
            //
            //  // Finally, we start displaying the camera preview.
            //  mPreviewRequest = mPreviewBuilder.build();
            //  mPreviewSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
            //} else {
            //setUpCaptureRequestBuilder(mPreviewBuilder);
            //mPreviewRequest = mPreviewBuilder.build();
            //mPreviewSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);
            //}
            setUpCaptureRequestBuilder(mPreviewBuilder);
            setFlashMode(mPreviewBuilder);
            mPreviewRequest = mPreviewBuilder.build();
            mPreviewSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
    //    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    //}

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale =
                    Math.max(
                            (float) viewHeight / mPreviewSize.getHeight(),
                            (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private boolean setUpMediaRecorder() {
        final Activity activity = getActivity();
        if (null == activity)
            return false;
        final BaseCaptureInterface captureInterface = (BaseCaptureInterface) activity;
        if (mMediaRecorder == null)
            mMediaRecorder = new MediaRecorder();

        boolean allowVideoRecording = mInterface.allowVideoRecording();
        boolean canUseAudio = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean audioEnabled = !mInterface.audioDisabled();
        if (allowVideoRecording) {
            if (canUseAudio && audioEnabled) {
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            } else if (audioEnabled) {
                Toast.makeText(getActivity(), R.string.mcam_no_audio_access, Toast.LENGTH_LONG).show();
            }
        }

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        final CamcorderProfile profile = CamcorderProfile.get(0, mInterface.qualityProfile());
        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(mInterface.videoFrameRate(profile.videoFrameRate));
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncodingBitRate(mInterface.videoEncodingBitRate(profile.videoBitRate));
        mMediaRecorder.setVideoEncoder(profile.videoCodec);

        if (allowVideoRecording && canUseAudio && audioEnabled) {
            mMediaRecorder.setAudioEncodingBitRate(mInterface.audioEncodingBitRate(profile.audioBitRate));
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
        }

        Uri uri = Uri.fromFile(getOutputMediaFile());
        mVideoOutputUri = uri.toString();
        mMediaRecorder.setOutputFile(uri.getPath());

        if (captureInterface.maxAllowedFileSize() > 0) {
            mMediaRecorder.setMaxFileSize(captureInterface.maxAllowedFileSize());
            mMediaRecorder.setOnInfoListener(
                    new MediaRecorder.OnInfoListener() {
                        @Override
                        public void onInfo(MediaRecorder mediaRecorder, int what, int extra) {
                            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                                Toast.makeText(
                                        getActivity(), R.string.mcam_file_size_limit_reached, Toast.LENGTH_SHORT)
                                        .show();
                                stopRecordingVideo(false);
                            }
                        }
                    });
        }

        mMediaRecorder.setOrientationHint(mDisplayOrientation);

        try {
            mMediaRecorder.prepare();
            return true;
        } catch (Throwable e) {
            throwError(new Exception("Failed to prepare the media recorder: " + e.getMessage(), e));
            return false;
        }
    }

    @Override
    public boolean startRecordingVideo() {
        super.startRecordingVideo();
        try {
            // UI
            //setImageRes(mButtonVideo, mInterface.iconStop());
            if (!CameraUtil.isChromium())
                mButtonFacing.setVisibility(View.GONE);

            // Only start counter if count down wasn't already started
            if (!mInterface.hasLengthLimit()) {
                mInterface.setRecordingStart(System.currentTimeMillis());
                startCounter();
            }

            // Start recording
            mMediaRecorder.start();

            //mButtonVideo.setEnabled(false);
      /*mButtonVideo.postDelayed(
          new Runnable() {
            @Override
            public void run() {
              mButtonVideo.setEnabled(true);
            }
          },
          200);*/

            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            mInterface.setRecordingStart(-1);
            stopRecordingVideo(false);
            throwError(new Exception("Failed to start recording: " + t.getMessage(), t));
        }
        return false;
    }

    @Override
    public void stopRecordingVideo(boolean reachedZero) {
        super.stopRecordingVideo(reachedZero);

        if (mInterface.hasLengthLimit()
                && mInterface.shouldAutoSubmit()
                && (mInterface.getRecordingStart() < 0 || mMediaRecorder == null)) {
            stopCounter();
            releaseRecorder();
            mInterface.onShowPreview(mVideoOutputUri, reachedZero);
            return;
        }

        if (!mInterface.didRecord())
            mVideoOutputUri = null;

        releaseRecorder();
        //setImageRes(mButtonVideo, mInterface.iconRecord());
        if (!CameraUtil.isChromium())
            mButtonFacing.setVisibility(View.VISIBLE);
        if (mInterface.getRecordingStart() > -1 && getActivity() != null)
            mInterface.onShowPreview(mVideoOutputUri, reachedZero);

        stopCounter();
    }

    @Override
    /**
     * @link http://pierrchen.blogspot.si/2015/01/android-camera2-api-explained.html
     * @link
     *     https://github.com/googlesamples/android-Camera2Basic/blob/master/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java
     */
    public void takeStillshot() {
        lockFocus();
    }

    private void lockFocus() {
        try {
            if (mAfAvailable) {
                // This is how to tell the camera to lock focus.
                mPreviewBuilder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                // Tell #mCaptureCallback to wait for the lock.
                mState = STATE_WAITING_LOCK;
            } else {
                runPrecaptureSequence();
                return;
            }

            setFlashMode(mPreviewBuilder);

            mPreviewSession.capture(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when we
     * get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewBuilder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            setFlashMode(mPreviewBuilder);

            mPreviewSession.capture(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in {@link
     * #mCaptureCallback} from both {@link #takeStillshot()}.
     */
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setFlashMode(captureBuilder);

            // Orientation
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(mCameraDevice.getId());

            //noinspection ConstantConditions,ResourceType
            @Degrees.DegreeUnits final int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();

            // default camera orientation used to be 90 degrees, for Nexus 5X, 6P it is 270 degrees
            if (sensorOrientation == Degrees.DEGREES_270) {
                displayRotation += 2 % 3;
            }

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(displayRotation));

            CameraCaptureSession.CaptureCallback CaptureCallback =
                    new CameraCaptureSession.CaptureCallback() {

                        @Override
                        public void onCaptureCompleted(
                                @NonNull CameraCaptureSession session,
                                @NonNull CaptureRequest request,
                                @NonNull TotalCaptureResult result) {
                            Log.d("stillshot", "onCaptureCompleted");
                            unlockFocus();
                        }
                    };

            mPreviewSession.stopRepeating();
            mPreviewSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is finished.
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setFlashMode(mPreviewBuilder);
            mPreviewSession.capture(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mPreviewSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setFlashMode(CaptureRequest.Builder requestBuilder) {
        mPreviewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        int aeMode;
        int flashMode;
        switch (mInterface.getFlashMode()) {
            case BaseCaptureActivity.FLASH_MODE_AUTO:
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
                flashMode = CameraMetadata.FLASH_MODE_SINGLE;
                break;
            case BaseCaptureActivity.FLASH_MODE_ALWAYS_ON:
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
                flashMode = CameraMetadata.FLASH_MODE_TORCH;
                break;
            case BaseCaptureActivity.FLASH_MODE_OFF:
            default:
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
                flashMode = CameraMetadata.FLASH_MODE_OFF;
                break;
        }

        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
        requestBuilder.set(CaptureRequest.FLASH_MODE, flashMode);
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    public static class ErrorDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new MaterialDialog.Builder(activity)
                    .content("This device doesn't support the Camera2 API.")
                    .positiveText(android.R.string.ok)
                    .onAny(
                            new MaterialDialog.SingleButtonCallback() {
                                @Override
                                public void onClick(
                                        @NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                                    activity.finish();
                                }
                            })
                    .build();
        }
    }
}
