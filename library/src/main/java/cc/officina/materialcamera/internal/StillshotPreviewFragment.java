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

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import cc.officina.materialcamera.R;
import cc.officina.materialcamera.util.ImageUtil;

public class StillshotPreviewFragment extends BaseGalleryFragment {

    /**
     * Reference to the bitmap, in case 'onConfigurationChange' event comes, so we do not recreate the
     * bitmap
     */
    private static Bitmap mBitmap;
    private ImageView mImageView;

    public static StillshotPreviewFragment newInstance(
            String outputUri, boolean allowRetry, int primaryColor) {
        final StillshotPreviewFragment fragment = new StillshotPreviewFragment();
        fragment.setRetainInstance(true);
        Bundle args = new Bundle();
        args.putString("output_uri", outputUri);
        args.putBoolean(CameraIntentKey.ALLOW_RETRY, allowRetry);
        args.putInt(CameraIntentKey.PRIMARY_COLOR, primaryColor);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mcam_fragment_stillshot, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mImageView = (ImageView) view.findViewById(R.id.stillshot_imageview);

        mConfirm.setText(mInterface.labelConfirm());
        mRetry.setText(mInterface.labelRetry());

        mRetry.setOnClickListener(this);
        mConfirm.setOnClickListener(this);

        mImageView
                .getViewTreeObserver()
                .addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                setImageBitmap();
                                mImageView.getViewTreeObserver().removeOnPreDrawListener(this);

                                return true;
                            }
                        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mBitmap != null && !mBitmap.isRecycled()) {
            try {
                mBitmap.recycle();
                mBitmap = null;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    /**
     * Sets bitmap to ImageView widget
     */
    private void setImageBitmap() {
        final int width = mImageView.getMeasuredWidth();
        final int height = mImageView.getMeasuredHeight();

        // TODO IMPROVE MEMORY USAGE HERE, ESPECIALLY ON LOW-END DEVICES.
        if (mBitmap == null)
            mBitmap = ImageUtil.getRotatedBitmap(Uri.parse(mOutputUri).getPath(), width, height);

        if (mBitmap == null)
            showDialog(
                    getString(R.string.mcam_image_preview_error_title),
                    getString(R.string.mcam_image_preview_error_message));
        else
            mImageView.setImageBitmap(mBitmap);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.retry)
            mInterface.onRetry(mOutputUri);
        else if (v.getId() == R.id.confirm)
            mInterface.useMedia(mOutputUri);
    }
}
