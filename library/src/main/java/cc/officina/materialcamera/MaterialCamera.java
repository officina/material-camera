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

package cc.officina.materialcamera;

import android.app.Activity;
import android.content.Intent;
import android.media.CamcorderProfile;
import android.support.annotation.AttrRes;
import android.support.annotation.ColorInt;
import android.support.annotation.ColorRes;
import android.support.annotation.DrawableRes;
import android.support.annotation.FloatRange;
import android.support.annotation.IntDef;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.content.ContextCompat;

import com.afollestad.materialdialogs.util.DialogUtils;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import cc.officina.materialcamera.internal.CameraIntentKey;
import cc.officina.materialcamera.util.CameraUtil;

@SuppressWarnings("WeakerAccess")
public class MaterialCamera {

    public static final int QUALITY_HIGH = CamcorderProfile.QUALITY_HIGH;
    public static final int QUALITY_LOW = CamcorderProfile.QUALITY_LOW;
    public static final int QUALITY_480P = CamcorderProfile.QUALITY_480P;
    public static final int QUALITY_720P = CamcorderProfile.QUALITY_720P;
    public static final int QUALITY_1080P = CamcorderProfile.QUALITY_1080P;
    public static final String EXTRA_ERROR = "mcam_error";
    public static final String EXTRA_STATUS = "mcam_status";
    public static final int STATUS_RECORDED = 1;
    public static final int STATUS_PICKED = 2;
    public static final int STATUS_RETRY = 3;

    private Activity mContext;
    private android.app.Fragment mAppFragment;
    private android.support.v4.app.Fragment mSupportFragment;
    private boolean mIsFragment = false;
    private long mLengthLimit = -1;
    private boolean mAllowRetry = true;
    private boolean mAutoSubmit = false;
    private String mSaveDir;
    private int mPrimaryColor;
    private boolean mAllowChangeCamera = true;
    private boolean mDefaultToFrontFacing = false;
    private boolean mCountdownImmediately = false;
    private boolean mRetryExists = false;
    private boolean mRestartTimerOnRetry = false;
    private boolean mContinueTimerInPlayback = true;
    private boolean mForceCamera1 = false;
    private boolean mAudioDisabled = false;
    private long mAutoRecord = -1;
    private int mVideoEncodingBitRate = -1;
    private int mAudioEncodingBitRate = -1;
    private int mVideoFrameRate = -1;
    private int mVideoPreferredHeight = -1;
    private float mVideoPreferredAspect = -1f;
    private long mMaxFileSize = -1;
    private int mQualityProfile = -1;
    private int mIconRecord;
    private int mIconStop;
    private int mIconFrontCamera;
    private int mIconRearCamera;
    private int mIconPlay;
    private int mIconPause;
    private int mIconRestart;
    private int mLabelRetry;
    private int mLabelConfirm;
    private boolean mAllowVideoRecording;

    public MaterialCamera(@NonNull Activity context) {
        mContext = context;
        mPrimaryColor = DialogUtils.resolveColor(context, R.attr.colorPrimary);
    }

    public MaterialCamera(@NonNull android.app.Fragment context) {
        mIsFragment = true;
        mContext = context.getActivity();
        mAppFragment = context;
        mSupportFragment = null;
        mPrimaryColor = DialogUtils.resolveColor(mContext, R.attr.colorPrimary);
    }

    public MaterialCamera(@NonNull android.support.v4.app.Fragment context) {
        mIsFragment = true;
        mContext = context.getActivity();
        mSupportFragment = context;
        mAppFragment = null;
        mPrimaryColor = DialogUtils.resolveColor(mContext, R.attr.colorPrimary);
    }

    public MaterialCamera countdownMillis(long lengthLimitMs) {
        mLengthLimit = lengthLimitMs;
        return this;
    }

    public MaterialCamera countdownSeconds(float lengthLimitSec) {
        return countdownMillis((int) (lengthLimitSec * 1000f));
    }

    public MaterialCamera countdownMinutes(float lengthLimitMin) {
        return countdownMillis((int) (lengthLimitMin * 1000f * 60f));
    }

    /**
     * Whether or not 'Retry' is visible during playback.
     *
     * @param allowRetry
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera allowRetry(boolean allowRetry) {
        mAllowRetry = allowRetry;
        return this;
    }

    /**
     * Whether or not user is allowed to playback videos after recording. This can affect other things, discussed in the next section.
     *
     * @param autoSubmit
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera autoSubmit(boolean autoSubmit) {
        mAutoSubmit = autoSubmit;
        return this;
    }

    public MaterialCamera countdownImmediately(boolean immediately) {
        mCountdownImmediately = immediately;
        return this;
    }

    /**
     * The folder recorded videos are saved to.
     *
     * @param dir
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera saveDir(@Nullable File dir) {
        if (dir == null)
            return saveDir((String) null);
        return saveDir(dir.getAbsolutePath());
    }

    public MaterialCamera saveDir(@Nullable String dir) {
        mSaveDir = dir;
        return this;
    }

    public MaterialCamera primaryColor(@ColorInt int color) {
        mPrimaryColor = color;
        return this;
    }

    public MaterialCamera primaryColorRes(@ColorRes int colorRes) {
        return primaryColor(ContextCompat.getColor(mContext, colorRes));
    }

    /**
     * The theme color used for the camera, defaults to colorPrimary of Activity in the constructor.
     *
     * @param colorAttr
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera primaryColorAttr(@AttrRes int colorAttr) {
        return primaryColor(DialogUtils.resolveColor(mContext, colorAttr));
    }

    /**
     * Allows the user to change cameras.
     *
     * @param allowChangeCamera
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera allowChangeCamera(boolean allowChangeCamera) {
        mAllowChangeCamera = allowChangeCamera;
        return this;
    }

    /**
     * Whether or not the camera will initially show the front facing camera.
     *
     * @param frontFacing
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera defaultToFrontFacing(boolean frontFacing) {
        mDefaultToFrontFacing = frontFacing;
        return this;
    }

    /**
     * If true, the 'Retry' button in the playback screen will exit the camera instead of going back to the recorder.
     *
     * @param exits
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera retryExits(boolean exits) {
        mRetryExists = exits;
        return this;
    }

    /**
     * If true, the countdown timer is reset to 0 when the user taps 'Retry' in playback.
     *
     * @param restart
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera restartTimerOnRetry(boolean restart) {
        mRestartTimerOnRetry = restart;
        return this;
    }

    /**
     * If true, the countdown timer will continue to go down during playback, rather than pausing.
     *
     * @param continueTimer
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera continueTimerInPlayback(boolean continueTimer) {
        mContinueTimerInPlayback = continueTimer;
        return this;
    }

    public MaterialCamera forceCamera1() {
        mForceCamera1 = true;
        return this;
    }

    /**
     * Set to true to record video without any audio.
     *
     * @param disabled
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera audioDisabled(boolean disabled) {
        mAudioDisabled = disabled;
        return this;
    }

    /**
     * @deprecated Renamed to videoEncodingBitRate(int).
     */
    @Deprecated
    public MaterialCamera videoBitRate(@IntRange(from = 1, to = Integer.MAX_VALUE) int rate) {
        return videoEncodingBitRate(rate);
    }

    /**
     * Sets a custom bit rate for video recording.
     *
     * @param rate
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera videoEncodingBitRate(@IntRange(from = 1, to = Integer.MAX_VALUE) int rate) {
        mVideoEncodingBitRate = rate;
        return this;
    }

    /**
     * Sets a custom bit rate for audio recording.
     *
     * @param rate
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera audioEncodingBitRate(@IntRange(from = 1, to = Integer.MAX_VALUE) int rate) {
        mAudioEncodingBitRate = rate;
        return this;
    }

    /**
     * Sets a custom frame rate (FPS) for video recording.
     *
     * @param rate
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera videoFrameRate(@IntRange(from = 1, to = Integer.MAX_VALUE) int rate) {
        mVideoFrameRate = rate;
        return this;
    }

    /**
     * Sets a preferred height for the recorded video output.
     *
     * @param height
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera videoPreferredHeight(
            @IntRange(from = 1, to = Integer.MAX_VALUE) int height) {
        mVideoPreferredHeight = height;
        return this;
    }

    /**
     * Sets a preferred aspect ratio for the recorded video output.
     *
     * @param ratio
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera videoPreferredAspect(
            @FloatRange(from = 0.1, to = Float.MAX_VALUE) float ratio) {
        mVideoPreferredAspect = ratio;
        return this;
    }

    /**
     * Sets a max file size of 5MB, recording will stop if file reaches this limit. Keep in mind, the FAT file system has a file size limit of 4GB.
     *
     * @param size
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera maxAllowedFileSize(long size) {
        mMaxFileSize = size;
        return this;
    }

    /**
     * Sets a quality profile, manually setting bit rates or frame rates with other settings will overwrite individual quality profile settings.
     *
     * @param profile
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera qualityProfile(@QualityProfile int profile) {
        mQualityProfile = profile;
        return this;
    }

    /**
     * Sets a custom icon for the button used to start recording.
     *
     * @param iconRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera iconRecord(@DrawableRes int iconRes) {
        mIconRecord = iconRes;
        return this;
    }

    /**
     * Sets a custom icon for the button used to stop recording.
     *
     * @param iconRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera iconStop(@DrawableRes int iconRes) {
        mIconStop = iconRes;
        return this;
    }

    /**
     * Sets a custom icon for the button used to switch to the front camera.
     *
     * @param iconRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera iconFrontCamera(@DrawableRes int iconRes) {
        mIconFrontCamera = iconRes;
        return this;
    }

    /**
     * Sets a custom icon for the button used to switch to the rear camera.
     *
     * @param iconRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera iconRearCamera(@DrawableRes int iconRes) {
        mIconRearCamera = iconRes;
        return this;
    }

    /**
     * Sets a custom icon used to start playback.
     *
     * @param iconRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera iconPlay(@DrawableRes int iconRes) {
        mIconPlay = iconRes;
        return this;
    }

    /**
     * Sets a custom icon used to pause playback.
     *
     * @param iconRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera iconPause(@DrawableRes int iconRes) {
        mIconPause = iconRes;
        return this;
    }

    /**
     * Sets a custom icon used to restart playback.
     *
     * @param iconRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera iconRestart(@DrawableRes int iconRes) {
        mIconRestart = iconRes;
        return this;
    }

    /**
     * Sets a custom button label for the button used to retry recording, when available.
     *
     * @param stringRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera labelRetry(@StringRes int stringRes) {
        mLabelRetry = stringRes;
        return this;
    }

    /**
     * Sets a custom button label for the button used to confirm/submit a recording.
     * This has been replaced with labelConfirm.
     *
     * @param stringRes
     * @return The {@link MaterialCamera} builder instance.
     */
    @Deprecated
    public MaterialCamera labelUseVideo(@StringRes int stringRes) {
        mLabelConfirm = stringRes;
        return this;
    }

    /**
     * Sets a custom button label for the button used to confirm/submit a recording.
     *
     * @param stringRes
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera labelConfirm(@StringRes int stringRes) {
        mLabelConfirm = stringRes;
        return this;
    }

    /**
     * Same as the above, expressed with milliseconds instead of seconds.
     *
     * @param delayMillis
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera autoRecordWithDelayMs(
            @IntRange(from = -1, to = Long.MAX_VALUE) long delayMillis) {
        mAutoRecord = delayMillis;
        return this;
    }

    /**
     * The video camera will start recording automatically after a 5 second countdown. This disables switching between the front and back camera initially.
     *
     * @param delaySeconds
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera autoRecordWithDelaySec(
            @IntRange(from = -1, to = Long.MAX_VALUE) int delaySeconds) {
        mAutoRecord = delaySeconds * 1000;
        return this;
    }

    /**
     * Allows the user to use video recording.
     *
     * @param allowVideoRecording
     * @return The {@link MaterialCamera} builder instance.
     */
    public MaterialCamera allowVideoRecording(boolean allowVideoRecording) {
        mAllowVideoRecording = allowVideoRecording;
        return this;
    }

    public Intent getIntent() {
        final Class<?> cls =
                !mForceCamera1 && CameraUtil.hasCamera2(mContext)
                        ? CaptureActivity2.class
                        : CaptureActivity.class;
        Intent intent =
                new Intent(mContext, cls)
                        .putExtra(CameraIntentKey.LENGTH_LIMIT, mLengthLimit)
                        .putExtra(CameraIntentKey.ALLOW_RETRY, mAllowRetry)
                        .putExtra(CameraIntentKey.AUTO_SUBMIT, mAutoSubmit)
                        .putExtra(CameraIntentKey.SAVE_DIR, mSaveDir)
                        .putExtra(CameraIntentKey.PRIMARY_COLOR, mPrimaryColor)
                        .putExtra(CameraIntentKey.ALLOW_CHANGE_CAMERA, mAllowChangeCamera)
                        .putExtra(CameraIntentKey.DEFAULT_TO_FRONT_FACING, mDefaultToFrontFacing)
                        .putExtra(CameraIntentKey.COUNTDOWN_IMMEDIATELY, mCountdownImmediately)
                        .putExtra(CameraIntentKey.RETRY_EXITS, mRetryExists)
                        .putExtra(CameraIntentKey.RESTART_TIMER_ON_RETRY, mRestartTimerOnRetry)
                        .putExtra(CameraIntentKey.CONTINUE_TIMER_IN_PLAYBACK, mContinueTimerInPlayback)
                        .putExtra(CameraIntentKey.AUTO_RECORD, mAutoRecord)
                        .putExtra(CameraIntentKey.AUDIO_DISABLED, mAudioDisabled)
                        .putExtra(CameraIntentKey.ALLOW_VIDEO_RECORDING, mAllowVideoRecording);

        if (mVideoEncodingBitRate > 0)
            intent.putExtra(CameraIntentKey.VIDEO_BIT_RATE, mVideoEncodingBitRate);
        if (mAudioEncodingBitRate > 0)
            intent.putExtra(CameraIntentKey.AUDIO_ENCODING_BIT_RATE, mAudioEncodingBitRate);
        if (mVideoFrameRate > 0)
            intent.putExtra(CameraIntentKey.VIDEO_FRAME_RATE, mVideoFrameRate);
        if (mVideoPreferredHeight > 0)
            intent.putExtra(CameraIntentKey.VIDEO_PREFERRED_HEIGHT, mVideoPreferredHeight);
        if (mVideoPreferredAspect > 0f)
            intent.putExtra(CameraIntentKey.VIDEO_PREFERRED_ASPECT, mVideoPreferredAspect);
        if (mMaxFileSize > -1)
            intent.putExtra(CameraIntentKey.MAX_ALLOWED_FILE_SIZE, mMaxFileSize);
        if (mQualityProfile > -1)
            intent.putExtra(CameraIntentKey.QUALITY_PROFILE, mQualityProfile);

        if (mIconRecord != 0)
            intent.putExtra(CameraIntentKey.ICON_RECORD, mIconRecord);
        if (mIconStop != 0)
            intent.putExtra(CameraIntentKey.ICON_STOP, mIconStop);
        if (mIconFrontCamera != 0)
            intent.putExtra(CameraIntentKey.ICON_FRONT_CAMERA, mIconFrontCamera);
        if (mIconRearCamera != 0)
            intent.putExtra(CameraIntentKey.ICON_REAR_CAMERA, mIconRearCamera);
        if (mIconPlay != 0)
            intent.putExtra(CameraIntentKey.ICON_PLAY, mIconPlay);
        if (mIconPause != 0)
            intent.putExtra(CameraIntentKey.ICON_PAUSE, mIconPause);
        if (mIconRestart != 0)
            intent.putExtra(CameraIntentKey.ICON_RESTART, mIconRestart);
        if (mLabelRetry != 0)
            intent.putExtra(CameraIntentKey.LABEL_RETRY, mLabelRetry);
        if (mLabelConfirm != 0)
            intent.putExtra(CameraIntentKey.LABEL_CONFIRM, mLabelConfirm);

        return intent;
    }

    /**
     * Starts the camera activity, the result will be sent back to the current Activity.
     *
     * @param requestCode
     */
    public void start(int requestCode) {
        //ActivityCompat.startActivityForResult(mContext, getIntent(), requestCode, null);
        if (mIsFragment && mSupportFragment != null)
            mSupportFragment.startActivityForResult(getIntent(), requestCode);
        else if (mIsFragment && mAppFragment != null)
            mAppFragment.startActivityForResult(getIntent(), requestCode);
        else
            mContext.startActivityForResult(getIntent(), requestCode);
    }

    @IntDef({QUALITY_HIGH, QUALITY_LOW, QUALITY_480P, QUALITY_720P, QUALITY_1080P})
    @Retention(RetentionPolicy.SOURCE)
    public @interface QualityProfile {
    }
}
