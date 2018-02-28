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

import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;

import java.util.List;

public interface BaseCaptureInterface {

    void onRetry(@Nullable String outputUri);

    void onShowPreview(@Nullable String outputUri, boolean countdownIsAtZero);

    void onShowStillshot(String outputUri);

    long getRecordingStart();

    void setRecordingStart(long start);

    long getRecordingEnd();

    void setRecordingEnd(long end);

    boolean hasLengthLimit();

    boolean countdownImmediately();

    long getLengthLimit();

    void setCameraPosition(int position);

    void toggleCameraPosition();

    Object getCurrentCameraId();

    @BaseCaptureActivity.CameraPosition
    int getCurrentCameraPosition();

    Object getFrontCamera();

    void setFrontCamera(Object id);

    Object getBackCamera();

    void setBackCamera(Object id);

    void useMedia(String uri);

    boolean shouldAutoSubmit();

    boolean allowRetry();

    void setDidRecord(boolean didRecord);

    boolean didRecord();

    boolean restartTimerOnRetry();

    boolean continueTimerInPlayback();

    int videoEncodingBitRate(int defaultVal);

    int audioEncodingBitRate(int defaultVal);

    int videoFrameRate(int defaultVal);

    int videoPreferredHeight();

    float videoPreferredAspect();

    long maxAllowedFileSize();

    int qualityProfile();

    @DrawableRes
    int iconRecord();

    @DrawableRes
    int iconStop();

    @DrawableRes
    int iconFrontCamera();

    @DrawableRes
    int iconRearCamera();

    @DrawableRes
    int iconPlay();

    @DrawableRes
    int iconPause();

    @DrawableRes
    int iconRestart();

    @StringRes
    int labelRetry();

    @Deprecated
    @StringRes
    int labelUseVideo();

    @StringRes
    int labelConfirm();

    @DrawableRes
    int iconStillshot();

    @DrawableRes
    int iconCapture();

    void toggleFlashMode();

    @BaseCaptureActivity.FlashMode
    int getFlashMode();

    @DrawableRes
    int iconFlashAuto();

    @DrawableRes
    int iconFlashOn();

    @DrawableRes
    int iconFlashOff();

    void setFlashModes(List<Integer> modes);

    boolean shouldHideFlash();

    long autoRecordDelay();

    boolean audioDisabled();

    boolean shouldHideCameraFacing();

    void pickFromGallery();

    boolean allowVideoRecording();
}
