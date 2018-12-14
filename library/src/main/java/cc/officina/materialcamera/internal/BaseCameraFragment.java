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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v7.content.res.AppCompatResources;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

import cc.officina.materialcamera.MaterialCamera;
import cc.officina.materialcamera.R;
import cc.officina.materialcamera.util.CameraUtil;

import static android.app.Activity.RESULT_CANCELED;

abstract class BaseCameraFragment extends Fragment
        implements CameraUriInterface, View.OnClickListener, View.OnTouchListener {

    protected ViewGroup mPreviewFrame;
    protected ViewGroup mControlsFrame;

    protected ImageButton mButtonStillshot;
    protected ImageButton mButtonFacing;
    protected ImageButton mButtonFlash;
    protected ImageButton mButtonPickFromGallery;
    protected ImageButton mButtonNavigation;
    protected SeekBar mRecordDurationBar;
    protected TextView mDelayStartCountdown;
    protected TextView mRecordDurationTv;
    protected String mPictureOutputUri;
    protected String mVideoOutputUri;
    protected BaseCaptureInterface mInterface;
    protected Handler mPositionHandler;
    protected MediaRecorder mMediaRecorder;
    private boolean mIsRecording;
    private int mPrimaryColor;
    private int mIconTextColor;
    private final Runnable mPositionUpdater =
            new Runnable() {
                @Override
                public void run() {
                    if (mInterface == null || mRecordDurationBar == null)
                        return;
                    final long mRecordStart = mInterface.getRecordingStart();
                    final long mRecordEnd = mInterface.getRecordingEnd();
                    if (mRecordStart == -1 && mRecordEnd == -1)
                        return;
                    final long now = System.currentTimeMillis();
                    if (mRecordEnd != -1) {
                        if (now >= mRecordEnd) {
                            stopRecordingVideo(true);
                        } else {
                            final long diff = now - mRecordStart;
                            int percentage = (int)((diff * 100)/mInterface.getLengthLimit());
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                mRecordDurationBar.setProgress(percentage, true);
                            } else {
                                mRecordDurationBar.setProgress(percentage);
                            }
                        }
                    } else {
                        mRecordDurationBar.setVisibility(View.GONE);
                        mRecordDurationTv.setText(CameraUtil.getDurationString(now - mRecordStart));
                    }
                    if (mPositionHandler != null)
                        mPositionHandler.postDelayed(this, 1000);
                }
            };
    private boolean mDidAutoRecord = false;
    private Handler mDelayHandler;
    private int mDelayCurrentSecond = -1;
    private Timer mTimer;
    private TimerTask mTimerTask;

    protected static void LOG(Object context, String message) {
        Log.d(
                context instanceof Class<?>
                        ? ((Class<?>) context).getSimpleName()
                        : context.getClass().getSimpleName(),
                message);
    }

    @Override
    public final View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mcam_fragment_videocapture, container, false);
    }

    protected void setImageRes(ImageView iv, @DrawableRes int res) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && iv.getBackground() instanceof RippleDrawable) {
            RippleDrawable rd = (RippleDrawable) iv.getBackground();
            rd.setColor(ColorStateList.valueOf(CameraUtil.adjustAlpha(mIconTextColor, 0.3f)));
        }
        Drawable d = AppCompatResources.getDrawable(iv.getContext(), res);
        d = DrawableCompat.wrap(d.mutate());
        DrawableCompat.setTint(d, mIconTextColor);
        iv.setImageDrawable(d);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mPrimaryColor = getArguments().getInt(CameraIntentKey.PRIMARY_COLOR);
        mIconTextColor = getArguments().getInt(CameraIntentKey.ICON_TEXT_COLOR);

        mPreviewFrame = (RelativeLayout) view.findViewById(R.id.rootFrame);

        mDelayStartCountdown = (TextView) view.findViewById(R.id.delayStartCountdown);
        mButtonStillshot = (ImageButton) view.findViewById(R.id.stillshot);
        mRecordDurationTv = (TextView) view.findViewById(R.id.recordDuration);
        mRecordDurationBar = (SeekBar) view.findViewById(R.id.recordDurationBar);
        mButtonFacing = (ImageButton) view.findViewById(R.id.facing);
        if (mInterface.shouldHideCameraFacing() || CameraUtil.isChromium()) {
            mButtonFacing.setVisibility(View.GONE);
        } else {
            setImageRes(
                    mButtonFacing,
                    mInterface.getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_BACK
                            ? mInterface.iconFrontCamera()
                            : mInterface.iconRearCamera());
        }

        mButtonFlash = (ImageButton) view.findViewById(R.id.flash);
        setupFlashMode();

        mButtonPickFromGallery = view.findViewById(R.id.pick_from_gallery);
        mButtonPickFromGallery.setVisibility(mInterface.allowPickFromGallery() ? View.VISIBLE : View.GONE);
        setImageRes(mButtonPickFromGallery, mInterface.iconPickFromGallery());

        mButtonNavigation = view.findViewById(R.id.navigation);
        mButtonNavigation.setVisibility(mInterface.shouldShowNavigationIcon() ? View.VISIBLE : View.GONE);
        setImageRes(mButtonNavigation, mInterface.iconNavigation());

        //mButtonStillshot.setOnClickListener(this);
        mButtonStillshot.setOnTouchListener(this);
        mButtonFacing.setOnClickListener(this);
        mButtonFlash.setOnClickListener(this);
        mButtonPickFromGallery.setOnClickListener(this);
        mButtonNavigation.setOnClickListener(this);

        mControlsFrame = view.findViewById(R.id.controlsFrame);
        mControlsFrame.setBackgroundColor(mPrimaryColor);
        //mRecordDurationTv.setBackgroundColor(mPrimaryColor);

        if (mMediaRecorder != null && mIsRecording) {
            //setImageRes(mButtonVideo, mInterface.iconStop());
        } else {
            //setImageRes(mButtonVideo, mInterface.iconRecord());
            mInterface.setDidRecord(false);
        }

        if (savedInstanceState != null) {
            mPictureOutputUri = savedInstanceState.getString("picture_output_uri");
            mVideoOutputUri = savedInstanceState.getString("video_output_uri");
        }
        mRecordDurationBar.getProgressDrawable().setColorFilter(Color.parseColor("#00fdc7"), PorterDuff.Mode.SRC_IN);
        mRecordDurationTv.setVisibility(View.GONE);
        mRecordDurationBar.setVisibility(View.GONE);
        mButtonStillshot.setVisibility(View.VISIBLE);
        mButtonStillshot.setImageResource(mInterface.iconCapture());
        mButtonFlash.setVisibility(View.VISIBLE);

        if (mInterface.autoRecordDelay() < 1000) {
            mDelayStartCountdown.setVisibility(View.GONE);
        } else {
            mDelayStartCountdown.setText(Long.toString(mInterface.autoRecordDelay() / 1000));
        }
    }

    protected void onFlashModesLoaded() {
        if (getCurrentCameraPosition() != BaseCaptureActivity.CAMERA_POSITION_FRONT) {
            invalidateFlash(false);
        }
    }

    protected void onCameraOpened() {
        if (mDidAutoRecord
                || mInterface == null
                || mInterface.autoRecordDelay() < 0
                || getActivity() == null) {
            mDelayStartCountdown.setVisibility(View.GONE);
            mDelayHandler = null;
            return;
        }
        mDidAutoRecord = true;
        mButtonFacing.setVisibility(View.GONE);

        if (mInterface.autoRecordDelay() == 0) {
            mDelayStartCountdown.setVisibility(View.GONE);
            mIsRecording = startRecordingVideo();
            mDelayHandler = null;
            return;
        }

        mDelayHandler = new Handler();
        //mButtonVideo.setEnabled(false);

        if (mInterface.autoRecordDelay() < 1000) {
            // Less than a second delay
            mDelayStartCountdown.setVisibility(View.GONE);
            mDelayHandler.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (!isAdded() || getActivity() == null || mIsRecording)
                                return;
                            //mButtonVideo.setEnabled(true);
                            mIsRecording = startRecordingVideo();
                            mDelayHandler = null;
                        }
                    },
                    mInterface.autoRecordDelay());
            return;
        }

        mDelayStartCountdown.setVisibility(View.VISIBLE);
        mDelayCurrentSecond = (int) mInterface.autoRecordDelay() / 1000;
        mDelayHandler.postDelayed(
                new Runnable() {
                    @SuppressLint("SetTextI18n")
                    @Override
                    public void run() {
                        if (!isAdded() || getActivity() == null || mIsRecording)
                            return;
                        mDelayCurrentSecond -= 1;
                        mDelayStartCountdown.setText(Integer.toString(mDelayCurrentSecond));

                        if (mDelayCurrentSecond == 0) {
                            mDelayStartCountdown.setVisibility(View.GONE);
                            //mButtonVideo.setEnabled(true);
                            mIsRecording = startRecordingVideo();
                            mDelayHandler = null;
                            return;
                        }

                        mDelayHandler.postDelayed(this, 1000);
                    }
                },
                1000);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        //mButtonVideo = null;
        mButtonStillshot = null;
        mButtonFacing = null;
        mButtonFlash = null;
        mRecordDurationTv = null;
        mRecordDurationBar = null;
        mButtonPickFromGallery = null;
        mButtonNavigation = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mInterface != null && mInterface.hasLengthLimit()) {
            if (mInterface.countdownImmediately() || mInterface.getRecordingStart() > -1) {
                if (mInterface.getRecordingStart() == -1)
                    mInterface.setRecordingStart(System.currentTimeMillis());
                startCounter();
            } else {
                mRecordDurationBar.setProgress(0);
                mRecordDurationTv.setText(CameraUtil.getDurationString(mInterface.getLengthLimit()));
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public final void onAttach(Activity activity) {
        super.onAttach(activity);
        mInterface = (BaseCaptureInterface) activity;
    }

    @Override
    public final void onAttach(Context context) {
        super.onAttach(context);
        mInterface = (BaseCaptureInterface) context;
    }

    @NonNull
    protected final File getOutputMediaFile() {
        return CameraUtil.makeTempFile(
                getActivity(), getArguments().getString(CameraIntentKey.SAVE_DIR), "VID_", ".mp4");
    }

    @NonNull
    protected final File getOutputPictureFile() {
        return CameraUtil.makeTempFile(
                getActivity(), getArguments().getString(CameraIntentKey.SAVE_DIR), "IMG_", ".jpg");
    }

    public abstract void openCamera();

    public abstract void closeCamera();

    public void cleanup() {
        closeCamera();
        releaseRecorder();
        stopCounter();
    }

    public abstract void takeStillshot();

    public abstract void onPreferencesUpdated();

    @Override
    public void onPause() {
        super.onPause();
        cleanup();
    }

    @Override
    public final void onDetach() {
        super.onDetach();
        mInterface = null;
    }

    public final void startCounter() {
        if (mPositionHandler == null)
            mPositionHandler = new Handler();
        else
            mPositionHandler.removeCallbacks(mPositionUpdater);
        mPositionHandler.post(mPositionUpdater);
    }

    @BaseCaptureActivity.CameraPosition
    public final int getCurrentCameraPosition() {
        if (mInterface == null)
            return BaseCaptureActivity.CAMERA_POSITION_UNKNOWN;
        return mInterface.getCurrentCameraPosition();
    }

    public final int getCurrentCameraId() {
        if (mInterface.getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_BACK)
            return (Integer) mInterface.getBackCamera();
        else
            return (Integer) mInterface.getFrontCamera();
    }

    public final void stopCounter() {
        if (mPositionHandler != null) {
            mPositionHandler.removeCallbacks(mPositionUpdater);
            mPositionHandler = null;
        }
    }

    public final void releaseRecorder() {
        if (mMediaRecorder != null) {
            if (mIsRecording) {
                try {
                    mMediaRecorder.stop();
                } catch (Throwable t) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(mVideoOutputUri).delete();
                    t.printStackTrace();
                }
                mIsRecording = false;
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    public boolean startRecordingVideo() {
        if (mInterface != null && mInterface.hasLengthLimit() && !mInterface.countdownImmediately()) {
            // Countdown wasn't started in onResume, start it now
            if (mInterface.getRecordingStart() == -1)
                mInterface.setRecordingStart(System.currentTimeMillis());
            startCounter();
        }

        mButtonStillshot.setImageResource(mInterface.iconRecord());
        mInterface.setDidRecord(true);

        return true;
    }

    public void stopRecordingVideo(boolean reachedZero) {
        mButtonStillshot.setImageResource(mInterface.iconCapture());
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (mButtonStillshot != null){
            mButtonStillshot.setOnTouchListener(null);
        }
    }

    @Override
    public final void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("picture_output_uri", mPictureOutputUri);
        outState.putString("video_output_uri", mVideoOutputUri);
    }

    @Override
    public final String getPictureOutputUri() {
        return mPictureOutputUri;
    }

    @Override
    public final String getVideoOutputUri() {
        return mVideoOutputUri;
    }

    protected final void throwError(Exception e) {
        Activity act = getActivity();
        if (act != null) {
            act.setResult(RESULT_CANCELED, new Intent().putExtra(MaterialCamera.EXTRA_ERROR, e));
            act.finish();
        }
    }

    @Override
    public void onClick(View view) {
        final int id = view.getId();
        if (id == R.id.facing) {
            mInterface.toggleCameraPosition();
            setImageRes(
                    mButtonFacing,
                    mInterface.getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_BACK
                            ? mInterface.iconFrontCamera()
                            : mInterface.iconRearCamera());
            closeCamera();
            openCamera();
            setupFlashMode();
        } else if (id == R.id.flash) {
            invalidateFlash(true);
        } else if (id == R.id.pick_from_gallery) {
            ((BaseCaptureActivity) getActivity()).pickFromGallery();
        } else if (id == R.id.navigation) {
            getActivity().onBackPressed();
        }
    }

    private void startTimer() {
        stopTimer();

        mTimer = new Timer("VideoRecording");
        mTimerTask = new TimerTask() {
            @Override
            public void run() {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mRecordDurationTv.setVisibility(View.VISIBLE);
                        mRecordDurationTv.setText(String.format("%d\"", mInterface.getLengthLimit()/1000));
                        mRecordDurationBar.setVisibility(View.VISIBLE);
                        mIsRecording = startRecordingVideo();
                    }
                });
            }
        };

        mTimer.schedule(mTimerTask, 1000);
    }

    private void stopTimer() {
        if (mTimerTask != null) {
            mTimerTask.cancel();
            mTimerTask = null;
        }

        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int id = v.getId();
        if (id == R.id.stillshot) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mInterface.allowVideoRecording()) {
                        startTimer();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    stopTimer();

                    if (mIsRecording) {
                        mRecordDurationTv.setVisibility(View.GONE);
                        mRecordDurationBar.setVisibility(View.GONE);
                        stopRecordingVideo(false);
                        mIsRecording = false;
                    } else {
                        takeStillshot();
                    }
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private void invalidateFlash(boolean toggle) {
        if (toggle)
            mInterface.toggleFlashMode();
        setupFlashMode();
        onPreferencesUpdated();
    }

    private void setupFlashMode() {
        if (mInterface.shouldHideFlash()) {
            mButtonFlash.setVisibility(View.GONE);
            return;
        } else {
            mButtonFlash.setVisibility(View.VISIBLE);
        }

        final int res;
        switch (mInterface.getFlashMode()) {
            case BaseCaptureActivity.FLASH_MODE_AUTO:
                res = mInterface.iconFlashAuto();
                break;
            case BaseCaptureActivity.FLASH_MODE_ALWAYS_ON:
                res = mInterface.iconFlashOn();
                break;
            case BaseCaptureActivity.FLASH_MODE_OFF:
            default:
                res = mInterface.iconFlashOff();
        }

        setImageRes(mButtonFlash, res);
    }
}
