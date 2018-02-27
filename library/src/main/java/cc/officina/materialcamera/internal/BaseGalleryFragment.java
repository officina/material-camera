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

import android.app.Activity;
import android.app.Fragment;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.Button;

import cc.officina.materialcamera.R;
import cc.officina.materialcamera.util.CameraUtil;
import com.afollestad.materialdialogs.MaterialDialog;

public abstract class BaseGalleryFragment extends Fragment
        implements CameraUriInterface, View.OnClickListener {

    BaseCaptureInterface mInterface;
    int mPrimaryColor;
    String mOutputUri;
    View mControlsFrame;
    Button mRetry;
    Button mConfirm;

    @SuppressWarnings("deprecation")
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mInterface = (BaseCaptureInterface) activity;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null)
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mOutputUri = getArguments().getString("output_uri");
        mControlsFrame = view.findViewById(R.id.controlsFrame);
        mRetry = (Button) view.findViewById(R.id.retry);
        mConfirm = (Button) view.findViewById(R.id.confirm);

        mPrimaryColor = getArguments().getInt(CameraIntentKey.PRIMARY_COLOR);
        if (CameraUtil.isColorDark(mPrimaryColor)) {
            mPrimaryColor = CameraUtil.darkenColor(mPrimaryColor);
            final int textColor = ContextCompat.getColor(view.getContext(), R.color.mcam_color_light);
            mRetry.setTextColor(textColor);
            mConfirm.setTextColor(textColor);
        } else {
            final int textColor = ContextCompat.getColor(view.getContext(), R.color.mcam_color_dark);
            mRetry.setTextColor(textColor);
            mConfirm.setTextColor(textColor);
        }
        mControlsFrame.setBackgroundColor(mPrimaryColor);

        mRetry.setVisibility(
                getArguments().getBoolean(CameraIntentKey.ALLOW_RETRY, true) ? View.VISIBLE : View.GONE);
    }

    @Override
    public String getPictureOutputUri() {
        return getArguments().getString("output_uri");
    }

    @Override
    public String getVideoOutputUri() {
        return null;
    }

    void showDialog(String title, String errorMsg) {
        new MaterialDialog.Builder(getActivity())
                .title(title)
                .content(errorMsg)
                .positiveText(android.R.string.ok)
                .show();
    }
}
