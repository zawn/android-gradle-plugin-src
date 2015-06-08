/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.core;

import com.android.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Base class for NDK config file.
 */
public interface NdkConfig {

    /**
     * The module name
     */
    @Nullable
    String getModuleName();

    /**
     * The C Flags
     */
    @Nullable
    String getcFlags();

    /**
     * The LD Libs
     */
    @Nullable
    List<String> getLdLibs();

    /**
     * The ABI Filters
     */
    @Nullable
    Set<String> getAbiFilters();

    /**
     * The APP_STL value
     */
    @Nullable
    String getStl();

    /**
     * Number of parallel threads to spawn.
     */
    @Nullable
    Integer getJobs();
}
