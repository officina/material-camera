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
import android.app.Activity;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.CamcorderProfile;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.afollestad.materialdialogs.MaterialDialog;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import cc.officina.materialcamera.MaterialCamera;
import cc.officina.materialcamera.R;
import cc.officina.materialcamera.TimeLimitReachedException;
import cc.officina.materialcamera.util.CameraUtil;
import cc.officina.materialcamera.util.FilenameUtils;
import cc.officina.materialcamera.util.MimeUtils;

public abstract class BaseCaptureActivity extends AppCompatActivity
        implements BaseCaptureInterface {

    public static final int PERMISSION_RC = 69;
    public static final int CAMERA_POSITION_UNKNOWN = 0;
    public static final int CAMERA_POSITION_FRONT = 1;
    public static final int CAMERA_POSITION_BACK = 2;
    public static final int FLASH_MODE_OFF = 0;
    public static final int FLASH_MODE_ALWAYS_ON = 1;
    public static final int FLASH_MODE_AUTO = 2;
    protected static final int REQUEST_CODE_PICK_FROM_GALLERY = 1000;
    private int mCameraPosition = CAMERA_POSITION_UNKNOWN;
    private int mFlashMode = FLASH_MODE_OFF;
    private boolean mRequestingPermission;
    private boolean mRequestingPickFromGallery;
    private long mRecordingStart = -1;
    private long mRecordingEnd = -1;
    private long mLengthLimit = -1;
    private Object mFrontCameraId;
    private Object mBackCameraId;
    private boolean mDidRecord = false;
    private List<Integer> mFlashModes;

    @Override
    protected final void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("camera_position", mCameraPosition);
        outState.putBoolean("requesting_permission", mRequestingPermission);
        outState.putBoolean("requesting_pick_from_gallery", mRequestingPickFromGallery);
        outState.putLong("recording_start", mRecordingStart);
        outState.putLong("recording_end", mRecordingEnd);
        outState.putLong(CameraIntentKey.LENGTH_LIMIT, mLengthLimit);
        if (mFrontCameraId instanceof String) {
            outState.putString("front_camera_id_str", (String) mFrontCameraId);
            outState.putString("back_camera_id_str", (String) mBackCameraId);
        } else {
            if (mFrontCameraId != null)
                outState.putInt("front_camera_id_int", (Integer) mFrontCameraId);
            if (mBackCameraId != null)
                outState.putInt("back_camera_id_int", (Integer) mBackCameraId);
        }
        outState.putInt("flash_mode", mFlashMode);
    }

    @Override
    protected final void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        super.onCreate(savedInstanceState);

        if (!CameraUtil.hasCamera(this)) {
            MaterialDialog dialog = new MaterialDialog(this)
                    .title(R.string.mcam_error, null)
                    .message(R.string.mcam_video_capture_unsupported, null, false, 1f);
            dialog.setOnDismissListener(
                            new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialog) {
                                    finish();
                                }
                            });
            dialog.show();
            return;
        }
        setContentView(R.layout.mcam_activity_videocapture);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final int primaryColor = getIntent().getIntExtra(CameraIntentKey.PRIMARY_COLOR, 0);
            final boolean isPrimaryDark = CameraUtil.isColorDark(primaryColor);
            final Window window = getWindow();
            window.setStatusBarColor(CameraUtil.darkenColor(primaryColor));
            window.setNavigationBarColor(isPrimaryDark ? primaryColor : Color.BLACK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final View view = window.getDecorView();
                int flags = view.getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                view.setSystemUiVisibility(flags);
            }
        }

        if (null == savedInstanceState) {
            checkPermissions();
            mLengthLimit = getIntent().getLongExtra(CameraIntentKey.LENGTH_LIMIT, -1);
        } else {
            mCameraPosition = savedInstanceState.getInt("camera_position", -1);
            mRequestingPermission = savedInstanceState.getBoolean("requesting_permission", false);
            mRequestingPickFromGallery = savedInstanceState.getBoolean("requesting_pick_from_gallery", false);
            mRecordingStart = savedInstanceState.getLong("recording_start", -1);
            mRecordingEnd = savedInstanceState.getLong("recording_end", -1);
            mLengthLimit = savedInstanceState.getLong(CameraIntentKey.LENGTH_LIMIT, -1);
            if (savedInstanceState.containsKey("front_camera_id_str")) {
                mFrontCameraId = savedInstanceState.getString("front_camera_id_str");
                mBackCameraId = savedInstanceState.getString("back_camera_id_str");
            } else {
                mFrontCameraId = savedInstanceState.getInt("front_camera_id_int");
                mBackCameraId = savedInstanceState.getInt("back_camera_id_int");
            }
            mFlashMode = savedInstanceState.getInt("flash_mode");
        }

        getWindow()
                .addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showInitialRecorder();
            return;
        }
        final boolean cameraGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean audioGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean audioNeeded = !audioDisabled() && allowVideoRecording();

        String[] perms = null;
        if (cameraGranted) {
            if (audioNeeded && !audioGranted) {
                perms = new String[]{Manifest.permission.RECORD_AUDIO};
            }
        } else {
            if (audioNeeded && !audioGranted) {
                perms = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
            } else {
                perms = new String[]{Manifest.permission.CAMERA};
            }
        }

        if (perms != null) {
            ActivityCompat.requestPermissions(this, perms, PERMISSION_RC);
            mRequestingPermission = true;
        } else {
            showInitialRecorder();
        }
    }

    @Override
    protected final void onPause() {
        super.onPause();
        if (!isFinishing() && !isChangingConfigurations() && !mRequestingPermission && !mRequestingPickFromGallery)
            finish();
    }

    @Override
    public final void onBackPressed() {
        Fragment frag = getFragmentManager().findFragmentById(R.id.container);
        if (frag != null) {
            if (frag instanceof PlaybackVideoFragment && allowRetry()) {
                onRetry(((CameraUriInterface) frag).getVideoOutputUri());
                return;
            } else if (frag instanceof BaseCameraFragment) {
                ((BaseCameraFragment) frag).cleanup();
            } else if (frag instanceof BaseGalleryFragment && allowRetry()) {
                onRetry(((CameraUriInterface) frag).getPictureOutputUri());
                return;
            }
        }
        finish();
    }

    @NonNull
    public abstract Fragment getFragment();

    public final Fragment createFragment() {
        Fragment frag = getFragment();
        frag.setArguments(getIntent().getExtras());
        return frag;
    }

    @Override
    public long getRecordingStart() {
        return mRecordingStart;
    }

    @Override
    public void setRecordingStart(long start) {
        mRecordingStart = start;
        if (start > -1 && hasLengthLimit())
            setRecordingEnd(mRecordingStart + getLengthLimit());
        else
            setRecordingEnd(-1);
    }

    @Override
    public long getRecordingEnd() {
        return mRecordingEnd;
    }

    @Override
    public void setRecordingEnd(long end) {
        mRecordingEnd = end;
    }

    @Override
    public long getLengthLimit() {
        return mLengthLimit;
    }

    @Override
    public boolean hasLengthLimit() {
        return getLengthLimit() > -1;
    }

    @Override
    public boolean countdownImmediately() {
        return getIntent().getBooleanExtra(CameraIntentKey.COUNTDOWN_IMMEDIATELY, false);
    }

    @Override
    public void setCameraPosition(int position) {
        mCameraPosition = position;
    }

    @Override
    public void toggleCameraPosition() {
        if (getCurrentCameraPosition() == CAMERA_POSITION_FRONT) {
            // Front, go to back if possible
            if (getBackCamera() != null)
                setCameraPosition(CAMERA_POSITION_BACK);
        } else {
            // Back, go to front if possible
            if (getFrontCamera() != null)
                setCameraPosition(CAMERA_POSITION_FRONT);
        }
    }

    @Override
    public int getCurrentCameraPosition() {
        return mCameraPosition;
    }

    @Override
    public Object getCurrentCameraId() {
        if (getCurrentCameraPosition() == CAMERA_POSITION_FRONT)
            return getFrontCamera();
        else
            return getBackCamera();
    }

    @Override
    public Object getFrontCamera() {
        return mFrontCameraId;
    }

    @Override
    public void setFrontCamera(Object id) {
        mFrontCameraId = id;
    }

    @Override
    public Object getBackCamera() {
        return mBackCameraId;
    }

    @Override
    public void setBackCamera(Object id) {
        mBackCameraId = id;
    }

    private void showInitialRecorder() {
        getFragmentManager().beginTransaction().replace(R.id.container, createFragment()).commit();
    }

    @Override
    public final void onRetry(@Nullable String outputUri) {
        if (outputUri != null)
            deleteOutputFile(outputUri);
        if (!shouldAutoSubmit() || restartTimerOnRetry())
            setRecordingStart(-1);
        if (getIntent().getBooleanExtra(CameraIntentKey.RETRY_EXITS, false)) {
            setResult(
                    RESULT_OK,
                    new Intent().putExtra(MaterialCamera.EXTRA_STATUS, MaterialCamera.STATUS_RETRY));
            finish();
            return;
        }
        getFragmentManager().beginTransaction().replace(R.id.container, createFragment()).commit();
    }

    @Override
    public final void onShowPreview(@Nullable final String outputUri, boolean countdownIsAtZero) {
        if ((shouldAutoSubmit() && (countdownIsAtZero || !allowRetry() || !hasLengthLimit()))
                || outputUri == null) {
            if (outputUri == null) {
                setResult(
                        RESULT_CANCELED,
                        new Intent().putExtra(MaterialCamera.EXTRA_ERROR, new TimeLimitReachedException()));
                finish();
                return;
            }
            useMedia(outputUri);
        } else {
            if (!hasLengthLimit() || !continueTimerInPlayback()) {
                // No countdown or countdown should not continue through playback, reset timer to 0
                setRecordingStart(-1);
            }
            Fragment frag =
                    PlaybackVideoFragment.newInstance(
                            outputUri, allowRetry(), getIntent().getIntExtra(CameraIntentKey.PRIMARY_COLOR, 0));
            getFragmentManager().beginTransaction().replace(R.id.container, frag).commit();
        }
    }

    @Override
    public void onShowStillshot(String outputUri) {
        if (shouldAutoSubmit()) {
            useMedia(outputUri);
        } else {
            Fragment frag =
                    StillshotPreviewFragment.newInstance(
                            outputUri, allowRetry(), getIntent().getIntExtra(CameraIntentKey.PRIMARY_COLOR, 0));
            getFragmentManager().beginTransaction().replace(R.id.container, frag).commit();
        }
    }

    @Override
    public final boolean allowRetry() {
        return getIntent().getBooleanExtra(CameraIntentKey.ALLOW_RETRY, true);
    }

    @Override
    public final boolean shouldAutoSubmit() {
        return getIntent().getBooleanExtra(CameraIntentKey.AUTO_SUBMIT, false);
    }

    private void deleteOutputFile(@Nullable String uri) {
        if (uri != null)
            //noinspection ResultOfMethodCallIgnored
            new File(Uri.parse(uri).getPath()).delete();
    }

    @Override
    protected final void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (BaseCaptureActivity.REQUEST_CODE_PICK_FROM_GALLERY == requestCode) {
            mRequestingPickFromGallery = false;

            if (Activity.RESULT_OK == resultCode) {
                Uri uri = data.getData();
                setResult(
                        Activity.RESULT_OK,
                        getIntent()
                                .putExtra(MaterialCamera.EXTRA_STATUS, MaterialCamera.STATUS_PICKED)
                                .setDataAndType(uri, MimeUtils.guessMimeTypeFromContentUri(this, uri)));
                finish();
            }
        } else if (requestCode == PERMISSION_RC) {
            showInitialRecorder();
        }
    }

    @Override
    public final void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        mRequestingPermission = false;
        if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
            MaterialDialog dialog = new MaterialDialog(this)
                    .title(R.string.mcam_permissions_needed, null)
                    .message(R.string.mcam_video_perm_warning, null, false, 1f);
            dialog.setOnDismissListener(
                    new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            finish();
                        }
                    });
            dialog.show();
        } else {
            showInitialRecorder();
        }
    }

    @Override
    public final void useMedia(String uri) {
        if (uri != null) {
            final Uri data = Uri.parse(uri);
            setResult(
                    Activity.RESULT_OK,
                    getIntent()
                            .putExtra(MaterialCamera.EXTRA_STATUS, MaterialCamera.STATUS_RECORDED)
                            .setDataAndType(
                                    data,
                                    MimeUtils.guessMimeTypeFromExtension(FilenameUtils.getExtension(uri.toString()))));
            ///*useStillshot() ? "image/jpeg" : */"video/mp4"));
        }
        finish();
    }

    @Override
    public void setDidRecord(boolean didRecord) {
        mDidRecord = didRecord;
    }

    @Override
    public boolean didRecord() {
        return mDidRecord;
    }

    @Override
    public int getFlashMode() {
        return mFlashMode;
    }

    @Override
    public void toggleFlashMode() {
        if (mFlashModes != null) {

            mFlashMode = mFlashModes.get((mFlashModes.indexOf(mFlashMode) + 1) % mFlashModes.size());
            if (didRecord() && mFlashMode == BaseCaptureActivity.FLASH_MODE_AUTO){
                //if recording skip autoflash
                mFlashMode = mFlashModes.get((mFlashModes.indexOf(mFlashMode) + 1) % mFlashModes.size());
            }
        }
    }

    @Override
    public boolean restartTimerOnRetry() {
        return getIntent().getBooleanExtra(CameraIntentKey.RESTART_TIMER_ON_RETRY, false);
    }

    @Override
    public boolean continueTimerInPlayback() {
        return getIntent().getBooleanExtra(CameraIntentKey.CONTINUE_TIMER_IN_PLAYBACK, false);
    }

    @Override
    public int videoEncodingBitRate(int defaultVal) {
        return getIntent().getIntExtra(CameraIntentKey.VIDEO_BIT_RATE, defaultVal);
    }

    @Override
    public int audioEncodingBitRate(int defaultVal) {
        return getIntent().getIntExtra(CameraIntentKey.AUDIO_ENCODING_BIT_RATE, defaultVal);
    }

    @Override
    public int videoFrameRate(int defaultVal) {
        return getIntent().getIntExtra(CameraIntentKey.VIDEO_FRAME_RATE, defaultVal);
    }

    @Override
    public float videoPreferredAspect() {
        return getIntent().getFloatExtra(CameraIntentKey.VIDEO_PREFERRED_ASPECT, 4f / 3f);
    }

    @Override
    public int videoPreferredHeight() {
        return getIntent().getIntExtra(CameraIntentKey.VIDEO_PREFERRED_HEIGHT, 720);
    }

    @Override
    public long maxAllowedFileSize() {
        return getIntent().getLongExtra(CameraIntentKey.MAX_ALLOWED_FILE_SIZE, -1);
    }

    @Override
    public int qualityProfile() {
        return getIntent().getIntExtra(CameraIntentKey.QUALITY_PROFILE, CamcorderProfile.QUALITY_HIGH);
    }

    @DrawableRes
    @Override
    public int iconPause() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_PAUSE, R.drawable.evp_action_pause);
    }

    @DrawableRes
    @Override
    public int iconPlay() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_PLAY, R.drawable.evp_action_play);
    }

    @DrawableRes
    @Override
    public int iconRestart() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_RESTART, R.drawable.evp_action_restart);
    }

    @DrawableRes
    @Override
    public int iconRearCamera() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_REAR_CAMERA, R.drawable.mcam_camera_rear);
    }

    @DrawableRes
    @Override
    public int iconFrontCamera() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_FRONT_CAMERA, R.drawable.mcam_camera_front);
    }

    @DrawableRes
    @Override
    public int iconStop() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_STOP, R.drawable.mcam_action_stop);
    }

    @DrawableRes
    @Override
    public int iconRecord() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_RECORD, R.drawable.mcam_action_capture_recording);
    }

    @StringRes
    @Override
    public int labelRetry() {
        return getIntent().getIntExtra(CameraIntentKey.LABEL_RETRY, R.string.mcam_retry);
    }

    @Deprecated
    @StringRes
    @Override
    public int labelUseVideo() {
        return getIntent().getIntExtra(CameraIntentKey.LABEL_CONFIRM, R.string.mcam_use_video);
    }

    @StringRes
    @Override
    public int labelConfirm() {
        return getIntent()
                .getIntExtra(
                        CameraIntentKey.LABEL_CONFIRM, R.string.mcam_use_video);
    }

    @Override
    public int iconCapture() {
        return getIntent()
                .getIntExtra(CameraIntentKey.ICON_CAPTURE, R.drawable.mcam_action_capture);
    }

    @DrawableRes
    @Override
    public int iconFlashAuto() {
        return getIntent()
                .getIntExtra(CameraIntentKey.ICON_FLASH_AUTO, R.drawable.mcam_action_flash_auto);
    }

    @DrawableRes
    @Override
    public int iconFlashOn() {
        return getIntent().getIntExtra(CameraIntentKey.ICON_FLASH_ON, R.drawable.mcam_action_flash);
    }

    @DrawableRes
    @Override
    public int iconFlashOff() {
        return getIntent()
                .getIntExtra(CameraIntentKey.ICON_FLASH_OFF, R.drawable.mcam_action_flash_off);
    }

    @Override
    public int iconPickFromGallery() {
        return getIntent()
                .getIntExtra(CameraIntentKey.ICON_PICK_FROM_GALLERY, R.drawable.mcam_action_pick_from_gallery);
    }

    @Override
    public int iconNavigation() {
        return getIntent()
                .getIntExtra(CameraIntentKey.ICON_NAVIGATION, R.drawable.mcam_action_navigation);
    }

    @Override
    public boolean shouldShowNavigationIcon() {
        return getIntent().getBooleanExtra(CameraIntentKey.SHOW_NAVIGATION_ICON, false);
    }

    @Override
    public void setFlashModes(List<Integer> modes) {
        mFlashModes = modes;
    }

    @Override
    public boolean shouldHideFlash() {
        return mFlashModes == null;
    }

    @Override
    public long autoRecordDelay() {
        return getIntent().getLongExtra(CameraIntentKey.AUTO_RECORD, -1);
    }

    @Override
    public boolean audioDisabled() {
        return getIntent().getBooleanExtra(CameraIntentKey.AUDIO_DISABLED, false);
    }

    @Override
    public boolean shouldHideCameraFacing() {
        return !getIntent().getBooleanExtra(CameraIntentKey.ALLOW_CHANGE_CAMERA, false);
    }

    @Override
    public void pickFromGallery() {
        mRequestingPickFromGallery = true;

        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        if (allowVideoRecording()) {
            intent.setType("image/* video/*");
        } else {
            intent.setType("image/*");
        }
        ActivityCompat.startActivityForResult(this, intent, BaseCaptureActivity.REQUEST_CODE_PICK_FROM_GALLERY, null);
    }

    @Override
    public boolean allowVideoRecording() {
        return getIntent().getBooleanExtra(CameraIntentKey.ALLOW_VIDEO_RECORDING, false);
    }

    @Override
    public boolean allowPickFromGallery() {
        return getIntent().getBooleanExtra(CameraIntentKey.ALLOW_PICK_FROM_GALLERY, false);
    }

    @IntDef({CAMERA_POSITION_UNKNOWN, CAMERA_POSITION_BACK, CAMERA_POSITION_FRONT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CameraPosition {
    }

    @IntDef({FLASH_MODE_OFF, FLASH_MODE_ALWAYS_ON, FLASH_MODE_AUTO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlashMode {
    }

    @Override
    public String recordTooltipString() {
        return getIntent().getStringExtra(CameraIntentKey.TOOLTIP_STRING);
    }
}
