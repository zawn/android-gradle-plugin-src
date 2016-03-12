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

package com.android.build.gradle.tasks;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.LintGradleClient;
import com.android.build.gradle.internal.dsl.LintOptions;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.BaseTask;
import com.android.builder.model.AndroidProject;
import com.android.builder.model.Variant;
import com.android.tools.lint.LintCliFlags;
import com.android.tools.lint.Reporter;
import com.android.tools.lint.Warning;
import com.android.tools.lint.checks.BuiltinIssueRegistry;
import com.android.tools.lint.checks.GradleDetector;
import com.android.tools.lint.checks.UnusedResourceDetector;
import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.Severity;
import com.android.utils.StringHelper;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.tasks.ParallelizableTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ParallelizableTask
public class Lint extends BaseTask {

    private static final Logger LOG = Logging.getLogger(Lint.class);

    @Nullable private LintOptions mLintOptions;
    @Nullable private File mSdkHome;
    private boolean mFatalOnly;
    private ToolingModelBuilderRegistry mToolingRegistry;

    public void setLintOptions(@NonNull LintOptions lintOptions) {
        mLintOptions = lintOptions;
    }

    public void setSdkHome(@NonNull File sdkHome) {
        mSdkHome = sdkHome;
    }

    public void setToolingRegistry(ToolingModelBuilderRegistry toolingRegistry) {
        mToolingRegistry = toolingRegistry;
    }

    public void setFatalOnly(boolean fatalOnly) {
        mFatalOnly = fatalOnly;
    }

    @TaskAction
    public void lint() throws IOException {
        AndroidProject modelProject = createAndroidProject(getProject());
        if (getVariantName() != null && !getVariantName().isEmpty()) {
            for (Variant variant : modelProject.getVariants()) {
                if (variant.getName().equals(getVariantName())) {
                    lintSingleVariant(modelProject, variant);
                }
            }
        } else {
            lintAllVariants(modelProject);
        }
    }

    /**
     * Runs lint individually on all the variants, and then compares the results
     * across variants and reports these
     */
    public void lintAllVariants(@NonNull AndroidProject modelProject) throws IOException {
        // In the Gradle integration we iterate over each variant, and
        // attribute unused resources to each variant, so don't make
        // each variant run go and inspect the inactive variant sources
        UnusedResourceDetector.sIncludeInactiveReferences = false;

        Map<Variant,List<Warning>> warningMap = Maps.newHashMap();
        for (Variant variant : modelProject.getVariants()) {
            List<Warning> warnings = runLint(modelProject, variant, false);
            warningMap.put(variant, warnings);
        }

        // Compute error matrix
        boolean quiet = mLintOptions.isQuiet();


        for (Map.Entry<Variant,List<Warning>> entry : warningMap.entrySet()) {
            Variant variant = entry.getKey();
            List<Warning> warnings = entry.getValue();
            if (!mFatalOnly && !quiet) {
                LOG.warn("Ran lint on variant {}: {} issues found",
                        variant.getName(), warnings.size());
            }
        }

        List<Warning> mergedWarnings = LintGradleClient.merge(warningMap, modelProject);
        int errorCount = 0;
        int warningCount = 0;
        for (Warning warning : mergedWarnings) {
            if (warning.severity == Severity.ERROR || warning.severity == Severity.FATAL) {
                errorCount++;
            } else if (warning.severity == Severity.WARNING) {
                warningCount++;
            }
        }

        /*
         * We pick the first variant to generate the full report and don't generate if we don't
         * have any variants.
         */
        if (!modelProject.getVariants().isEmpty()) {
            Set<Variant> allVariants = Sets.newTreeSet(new Comparator<Variant>() {
                @Override
                public int compare(Variant v1, Variant v2) {
                    return v1.getName().compareTo(v2.getName());
                }
            });

            allVariants.addAll(modelProject.getVariants());
            Variant variant = allVariants.iterator().next();

            IssueRegistry registry = new BuiltinIssueRegistry();
            LintCliFlags flags = new LintCliFlags();
            LintGradleClient client = new LintGradleClient(
                    registry, flags, getProject(), modelProject,
                    mSdkHome, variant, getBuildTools());
            syncOptions(mLintOptions, client, flags, variant, getProject(), true, mFatalOnly);

            for (Reporter reporter : flags.getReporters()) {
                reporter.write(errorCount, warningCount, mergedWarnings);
            }

            if (flags.isSetExitCode() && errorCount > 0) {
                abort();
            }
        }
    }

    private void abort() {
        String message;
        if (mFatalOnly) {
            message = "" +
                    "Lint found fatal errors while assembling a release target.\n" +
                    "\n" +
                    "To proceed, either fix the issues identified by lint, or modify your build script as follows:\n" +
                    "...\n" +
                    "android {\n" +
                    "    lintOptions {\n" +
                    "        checkReleaseBuilds false\n" +
                    "        // Or, if you prefer, you can continue to check for errors in release builds,\n" +
                    "        // but continue the build even when errors are found:\n" +
                    "        abortOnError false\n" +
                    "    }\n" +
                    "}\n" +
                    "...";
        } else {
            message = "" +
                    "Lint found errors in the project; aborting build.\n" +
                    "\n" +
                    "Fix the issues identified by lint, or add the following to your build script to proceed with errors:\n" +
                    "...\n" +
                    "android {\n" +
                    "    lintOptions {\n" +
                    "        abortOnError false\n" +
                    "    }\n" +
                    "}\n" +
                    "...";
        }
        throw new GradleException(message);
    }

    /**
     * Runs lint on a single specified variant
     */
    public void lintSingleVariant(@NonNull AndroidProject modelProject, @NonNull Variant variant) {
        runLint(modelProject, variant, true);
    }

    /** Runs lint on the given variant and returns the set of warnings */
    private List<Warning> runLint(
            @NonNull AndroidProject modelProject,
            @NonNull Variant variant,
            boolean report) {
        IssueRegistry registry = createIssueRegistry();
        LintCliFlags flags = new LintCliFlags();
        LintGradleClient client = new LintGradleClient(registry, flags, getProject(), modelProject,
                mSdkHome, variant, getBuildTools());
        if (mFatalOnly) {
            if (!mLintOptions.isCheckReleaseBuilds()) {
                return Collections.emptyList();
            }
            flags.setFatalOnly(true);
        }
        syncOptions(mLintOptions, client, flags, variant, getProject(), report, mFatalOnly);
        if (!report || mFatalOnly) {
            flags.setQuiet(true);
        }

        List<Warning> warnings;
        try {
            warnings = client.run(registry);
        } catch (IOException e) {
            throw new GradleException("Invalid arguments.", e);
        }

        if (report && client.haveErrors() && flags.isSetExitCode()) {
            abort();
        }

        return warnings;
    }

    private static void syncOptions(
            @NonNull LintOptions options,
            @NonNull LintGradleClient client,
            @NonNull LintCliFlags flags,
            @NonNull Variant variant,
            @NonNull Project project,
            boolean report,
            boolean fatalOnly) {
        options.syncTo(client, flags, variant.getName(), project, report);

        if (fatalOnly || flags.isQuiet()) {
            for (Reporter reporter : flags.getReporters()) {
                reporter.setDisplayEmpty(false);
            }
        }
    }

    private AndroidProject createAndroidProject(@NonNull Project gradleProject) {
        String modelName = AndroidProject.class.getName();
        ToolingModelBuilder modelBuilder = mToolingRegistry.getBuilder(modelName);
        assert modelBuilder != null;
        return (AndroidProject) modelBuilder.buildAll(modelName, gradleProject);
    }

    private static BuiltinIssueRegistry createIssueRegistry() {
        return new LintGradleIssueRegistry();
    }

    // Issue registry when Lint is run inside Gradle: we replace the Gradle
    // detector with a local implementation which directly references Groovy
    // for parsing. In Studio on the other hand, the implementation is replaced
    // by a PSI-based check. (This is necessary for now since we don't have a
    // tool-agnostic API for the Groovy AST and we don't want to add a 6.3MB dependency
    // on Groovy itself quite yet.
    public static class LintGradleIssueRegistry extends BuiltinIssueRegistry {
        private boolean mInitialized;

        public LintGradleIssueRegistry() {
        }

        @NonNull
        @Override
        public List<Issue> getIssues() {
            List<Issue> issues = super.getIssues();
            if (!mInitialized) {
                mInitialized = true;
                for (Issue issue : issues) {
                    if (issue.getImplementation().getDetectorClass() == GradleDetector.class) {
                        issue.setImplementation(GroovyGradleDetector.IMPLEMENTATION);
                    }
                }
            }

            return issues;
        }
    }

    public static class ConfigAction implements TaskConfigAction<Lint> {

        private final VariantScope scope;

        public ConfigAction(@NonNull VariantScope scope) {
            this.scope = scope;
        }

        @Override
        @NonNull
        public String getName() {
            return scope.getTaskName("lint");
        }

        @Override
        @NonNull
        public Class<Lint> getType() {
            return Lint.class;
        }

        @Override
        public void execute(@NonNull Lint lint) {
            lint.setLintOptions(scope.getGlobalScope().getExtension().getLintOptions());
            lint.setSdkHome(scope.getGlobalScope().getSdkHandler().getSdkFolder());
            lint.setAndroidBuilder(scope.getGlobalScope().getAndroidBuilder());
            lint.setVariantName(scope.getVariantConfiguration().getFullName());
            lint.setToolingRegistry(scope.getGlobalScope().getToolingRegistry());
            lint.setDescription("Runs lint on the " + StringHelper
                            .capitalize(scope.getVariantConfiguration().getFullName()) + " build.");
            lint.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
        }
    }
}
