/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.repository.impl.manager;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.repository.api.Downloader;
import com.android.repository.api.FallbackRemoteRepoLoader;
import com.android.repository.api.ProgressIndicator;
import com.android.repository.api.ProgressIndicatorAdapter;
import com.android.repository.api.RemotePackage;
import com.android.repository.api.Repository;
import com.android.repository.api.RepositorySource;
import com.android.repository.api.RepositorySourceProvider;
import com.android.repository.api.SchemaModule;
import com.android.repository.api.SettingsController;
import com.android.repository.impl.meta.SchemaModuleUtil;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import org.w3c.dom.ls.LSResourceResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.List;

/**
 * Utility class that loads {@link Repository}s from {@link RepositorySource}s.
 */
public class RemoteRepoLoader {

    /**
     * Resource resolver to use for finding imported XSDs.
     */
    private final LSResourceResolver mResourceResolver;

    /**
     * {@link FallbackRemoteRepoLoader} to use if we get an XML file we can't parse.
     */
    private FallbackRemoteRepoLoader mFallback;

    /**
     * The {@link RepositorySourceProvider}s to load from.
     */
    private final Collection<RepositorySourceProvider> mSourceProviders;

    /**
     * Constructor
     *
     * @param sources          The {@link RepositorySourceProvider}s to get the {@link
     *                         RepositorySource}s to load from.
     * @param resourceResolver The resolver to use to find imported XSDs, if necessary for the
     *                         {@link SchemaModule}s used by the {@link RepositorySource}s.
     * @param fallback         The {@link FallbackRemoteRepoLoader} to use if we can't parse an XML
     *                         file.
     */
    public RemoteRepoLoader(@NonNull Collection<RepositorySourceProvider> sources,
            @Nullable LSResourceResolver resourceResolver,
            @Nullable FallbackRemoteRepoLoader fallback) {
        mResourceResolver = resourceResolver;
        mSourceProviders = sources;
        mFallback = fallback;
    }

    /**
     * Actually loads {@link RemotePackage}s from the given sources.
     *
     * @param progress   {@link ProgressIndicator} for logging and showing progress (TODO).
     * @param downloader The {@link Downloader} to use for {@link RepositorySourceProvider}s to use
     *                   if needed.
     * @param settings   The {@link SettingsController} for {@link RepositorySourceProvider}s to use
     *                   if needed.
     * @return A {@link Multimap} of install paths to {@link RemotePackage}s. Usually there should
     * be at most two versions for a given package: a stable version and/or a preview version.
     */
    @NonNull
    public Multimap<String, RemotePackage> fetchPackages(@NonNull ProgressIndicator progress,
            @NonNull Downloader downloader, @Nullable SettingsController settings) {
        Multimap<String, RemotePackage> result = HashMultimap.create();
        for (RepositorySourceProvider provider : mSourceProviders) {
            for (RepositorySource source : provider
                    .getSources(downloader, settings, progress, false)) {
                try {
                    InputStream repoStream = downloader
                            .download(new URL(source.getUrl()), settings, progress);
                    final List<String> errors = Lists.newArrayList();

                    // Don't show the errors, in case the fallback loader can read it. But keep
                    // track of them to show later in case not.
                    ProgressIndicator unmarshalProgress = new ProgressIndicatorAdapter() {
                        @Override
                        public void logWarning(@NonNull String s, Throwable e) {
                            errors.add(s);
                            if (e != null) {
                                errors.add(e.toString());
                            }
                        }

                        @Override
                        public void logError(@NonNull String s, Throwable e) {
                            errors.add(s);
                            if (e != null) {
                                errors.add(e.toString());
                            }
                        }
                    };

                    Repository repo = (Repository) SchemaModuleUtil
                            .unmarshal(repoStream, source.getPermittedModules(),
                                    mResourceResolver, unmarshalProgress);

                    Collection<? extends RemotePackage> parsedPackages = null;
                    if (repo != null) {
                        parsedPackages = repo.getRemotePackage();
                    } else if (mFallback != null) {
                        // TODO: don't require downloading again
                        parsedPackages = mFallback.parseLegacyXml(source, progress);
                    }
                    if (parsedPackages != null && !parsedPackages.isEmpty()) {
                        for (RemotePackage pkg : parsedPackages) {
                            pkg.setSource(source);
                            result.put(pkg.getPath(), pkg);
                        }
                        source.setFetchError(null);
                    } else {
                        progress.logWarning("Errors during XML parse:");
                        for (String error : errors) {
                            progress.logWarning(error);
                        }
                        //noinspection VariableNotUsedInsideIf
                        if (mFallback != null) {
                            progress.logWarning(
                                    "Additionally, the fallback loader failed to parse the XML.");
                        }
                        source.setFetchError(errors.isEmpty() ? "unknown error" : errors.get(0));
                    }
                } catch (MalformedURLException e) {
                    source.setFetchError("Malformed URL");
                    progress.logWarning(e.toString());
                } catch (IOException e) {
                    source.setFetchError(e.getMessage());
                    progress.logWarning(e.toString());
                }
            }
        }
        return result;
    }

}
