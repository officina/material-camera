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

import android.os.Build;

/**
 * This class exists to provide a place to define device specific information as some
 * manufacturers/devices require specific camera setup/requirements.
 */
public class ManufacturerUtil {

    public static final Integer SAMSUNG_S3_PREVIEW_WIDTH = 640;
    public static final Integer SAMSUNG_S3_PREVIEW_HEIGHT = 480;
    // Samsung device info
    private static final String SAMSUNG_MANUFACTURER = "samsung";
    // Samsung Galaxy S3 info
    private static final String SAMSUNG_S3_DEVICE_COMMON_PREFIX = "d2";

    public ManufacturerUtil() {
    }

    // Samsung Galaxy helper functions
    static boolean isSamsungDevice() {
        return SAMSUNG_MANUFACTURER.equals(Build.MANUFACTURER.toLowerCase());
    }

    public static boolean isSamsungGalaxyS3() {
        return Build.DEVICE.startsWith(SAMSUNG_S3_DEVICE_COMMON_PREFIX);
    }
}
