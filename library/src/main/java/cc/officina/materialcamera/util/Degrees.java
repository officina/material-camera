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

package cc.officina.materialcamera.util;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

public class Degrees {

    public static final int DEGREES_0 = 0;
    public static final int DEGREES_90 = 90;
    public static final int DEGREES_180 = 180;
    public static final int DEGREES_270 = 270;
    static final int DEGREES_360 = 360;

    private Degrees() {
    }

    @DegreeUnits
    public static int mirror(@DegreeUnits int orientation) {
        switch (orientation) {
            case DEGREES_0:
            case DEGREES_360:
                return DEGREES_180;
            case DEGREES_90:
                return DEGREES_270;
            case DEGREES_180:
                return DEGREES_0;
            case DEGREES_270:
                return DEGREES_90;
        }
        return DEGREES_0;
    }

    @SuppressWarnings("ResourceType")
    @DegreeUnits
    private static int naturalize(@DegreeUnits int orientation) {
        if (orientation == 360)
            orientation = 0;
        else if (orientation > 360) {
            do {
                orientation = orientation - 360;
            } while (orientation > 360);
        } else if (orientation < 0) {
            do {
                orientation = 360 + orientation;
            } while (orientation < 0);
        }
        return orientation;
    }

    @DegreeUnits
    public static int getDisplayRotation(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                return DEGREES_0;
            case Surface.ROTATION_90:
                return DEGREES_90;
            case Surface.ROTATION_180:
                return DEGREES_180;
            case Surface.ROTATION_270:
                return DEGREES_270;
        }
        return DEGREES_0;
    }

    @SuppressWarnings("ResourceType")
    @DegreeUnits
    public static int getDisplayOrientation(
            @Degrees.DegreeUnits int sensorOrientation,
            @Degrees.DegreeUnits int displayOrientation,
            boolean front) {
        final boolean isLandscape = isLandscape(displayOrientation);
        if (displayOrientation == DEGREES_0)
            displayOrientation = DEGREES_360;
        int result = sensorOrientation - displayOrientation;
        result = Degrees.naturalize(result);
        if (isLandscape && front)
            result = mirror(result);
        return result;
    }

    @ActivityOrientation
    public static int getActivityOrientation(@NonNull Activity context) {
        @DegreeUnits final int rotation = getDisplayRotation(context);
        switch (rotation) {
            case DEGREES_0:
            case DEGREES_360:
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case DEGREES_90:
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case DEGREES_180:
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case DEGREES_270:
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            default:
                Log.e("Degrees", "Unknown screen orientation. Defaulting to portrait.");
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }

    public static boolean isPortrait(Context activity) {
        return isPortrait(getDisplayRotation(activity));
    }

    public static boolean isLandscape(Context activity) {
        return isLandscape(getDisplayRotation(activity));
    }

    public static boolean isPortrait(@Degrees.DegreeUnits int degrees) {
        return degrees == DEGREES_0 || degrees == DEGREES_180 || degrees == DEGREES_360;
    }

    private static boolean isLandscape(@Degrees.DegreeUnits int degrees) {
        return degrees == DEGREES_90 || degrees == DEGREES_270;
    }

    @IntDef({
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ActivityOrientation {
    }

    @IntDef({DEGREES_0, DEGREES_90, DEGREES_180, DEGREES_270, DEGREES_360})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DegreeUnits {
    }
}
