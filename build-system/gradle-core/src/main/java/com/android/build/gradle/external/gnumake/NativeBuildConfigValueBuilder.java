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
package com.android.build.gradle.external.gnumake;


import com.android.build.gradle.external.gson.NativeBuildConfigValue;
import com.android.build.gradle.external.gson.NativeLibraryValue;
import com.android.build.gradle.external.gson.NativeSourceFileValue;
import com.android.build.gradle.external.gson.NativeToolchainValue;
import com.android.utils.NativeSourceFileExtensions;
import com.android.utils.StringHelper;
import com.google.common.base.Strings;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 The purpose of this class is to take the raw output of an ndk-build -n call and to produce a
 NativeBuildConfigValue instance to pass upstream through gradle.

 This involves several stages of processing:

 (1) CommandLineParser.parse accepts a single string which is the ndk-build -n output. It tokenizes
 each command in the string according to shell parsing rules on Windows or bash (includes mac).
 The result is a list of CommandLine.

 (2) CommandClassifier.classify accepts the output of (1). It looks at each command for something it
 recognizes. This will typically be calls to clang, gcc or gcc-ar. Once a command is recognized,
 its file inputs and outputs are recorded. The result is a list of Classification.

 (3) FlowAnaylzer.analyze accepts the output of (2). It traces the flow of inputs and ouputs. This
 flow tracing will involve intermediate steps through linking and possibly archiving (gcc-ar).
 Files involved are typically .c, .cpp, .o, .a and .so. The result of this step is a map from
 terminal outputs (.so) to original inputs (.c and .cpp).

 (4) NativeBuildConfigValueBuilder.build accepts the output of (3). It examines the terminal outputs
 and input information to build up an instance of NativeBuildConfigValue.
 */
@SuppressWarnings("SingleCharacterStringConcatenation")
public class NativeBuildConfigValueBuilder {
    private static final List<String> STRIP_FLAGS = Arrays.asList("-I", "-MF", "-c", "-o");

    private final Map<String, String> toolChainToCCompiler = new HashMap<String, String>();
    private final Map<String, String> toolChainToCppCompiler = new HashMap<String, String>();
    private final Set<String> cFileExtensions = new HashSet<String>();
    private final Set<String> cppFileExtensions = new HashSet<String>();
    private final File projectRootPath;
    private final List<Output> outputs;

    /**
     * Constructs a NativeBuildConfigValueBuilder which can be used to build a
     * {@link NativeBuildConfigValue}.
     *
     * projectRootPath -- file path to the project that contains an ndk-build project.
     */
    public NativeBuildConfigValueBuilder(File projectRootPath) {
        this.projectRootPath = projectRootPath;
        this.outputs = new ArrayList<Output>();
    }

    /**
     * Add commands for a particular variant.
     */
    public NativeBuildConfigValueBuilder addCommands(
            String buildCommand,
            String variantName,
            String commands,
            boolean isWin32) {
        ListMultimap<String, List<BuildStepInfo>> outputs = FlowAnalyzer.analyze(commands, isWin32);
        for (Map.Entry<String, List<BuildStepInfo>> entry : outputs.entries()) {
            this.outputs.add(new Output(entry.getKey(), entry.getValue(), buildCommand, variantName));
        }
        return this;
    }

    /**
     * Builds the {@link NativeBuildConfigValue} from the given information.
     */
    public NativeBuildConfigValue build() {
        findLibraryNames();
        findToolchainNames();
        findToolChainCompilers();

        NativeBuildConfigValue config = new NativeBuildConfigValue();
        config.buildFiles = Lists.newArrayList(
                getAndroidMkFile(projectRootPath),
                getApplicationMkFile(projectRootPath));
        config.libraries = generateLibraries();
        config.toolchains = generateToolchains();
        config.cFileExtensions = generateExtensions(cFileExtensions);
        config.cppFileExtensions = generateExtensions(cppFileExtensions);
        return config;
    }

    private static Collection<String> generateExtensions(Set<String> extensionSet) {
        List<String> extensionList = Lists.newArrayList(extensionSet);
        Collections.sort(extensionList);

        return extensionList;
    }

    private static File getAndroidMkFile(File projectRootPath) {
        return new File(projectRootPath, "jni/Android.mk");
    }

    private static File getApplicationMkFile(File projectRootPath) {
        return new File(projectRootPath, "jni/Application.mk");
    }

    private boolean areLibraryNamesUnique() {
        Set<String> uniqueNames = new HashSet<String>();
        for (Output output : outputs) {
            if (Strings.isNullOrEmpty(output.libraryName)) {
                return false;
            }
            uniqueNames.add(output.libraryName);
        }
        return uniqueNames.size() == outputs.size();
    }

    private static String getParentFolder(String output) {
        return new File(output).getParentFile().getName();
    }

    private static String removeFileExtension(String output) {
        int dotIndex = output.lastIndexOf('.');
        if (dotIndex == -1) {
            return output;
        }
        return output.substring(0, dotIndex);
    }

    private static String findLibraryNameByPathFilePattern(String output) {
        File file = new File(removeFileExtension(output));
        return getParentFolder(output) + "-" + file.getName();
    }

    private void findLibraryNames() {
        for (Output output : outputs) {
            // This pattern is for standard ndk-build and should give names like:
            //  mips64-test-libstl-release
            String pattern = findLibraryNameByPathFilePattern(output.outputName);
            if (!pattern.isEmpty()) {
                output.libraryName =
                        pattern + "-" + output.variantName;
            }
        }

        if (areLibraryNamesUnique()) {
            return;
        }

        throw new RuntimeException("Library names not unique");
    }

    private void findToolChainCompilers() {
        for (Output output : outputs) {
            String toolchain = output.toolchain;
            Set<String> cCompilers = new HashSet<String>();
            Set<String> cppCompilers = new HashSet<String>();
            Map<String, Set<String>> compilerToWeirdExtensions = new HashMap<String, Set<String>>();
            for (BuildStepInfo command : output.commandInputs) {
                String compilerCommand = command.getCommand().command;
                int extensionIndex = command.getOnlyInput().lastIndexOf('.');
                String extension = "";
                if (extensionIndex != -1) {
                    extension = command.getOnlyInput().substring(extensionIndex);
                }
                String extensionNoDot = extension.substring(1);

                if (NativeSourceFileExtensions.C_FILE_EXTENSIONS.contains(extensionNoDot)) {
                    cFileExtensions.add(extension);
                    cCompilers.add(compilerCommand);
                } else if (NativeSourceFileExtensions.CPP_FILE_EXTENSIONS.contains(extensionNoDot)) {
                    cppFileExtensions.add(extension);
                    cppCompilers.add(compilerCommand);
                } else {
                    // Unrecognized extensions are recorded and added to the relevant compiler
                    Set<String> extensions = compilerToWeirdExtensions.get(compilerCommand);
                    if (extensions == null) {
                        extensions = new HashSet<String>();
                        compilerToWeirdExtensions.put(compilerCommand, extensions);
                    }
                    extensions.add(extension);
                }
            }

            if (cCompilers.size() > 1) {
                throw new RuntimeException("Too many c compilers in toolchain.");
            }

            if (cppCompilers.size() > 1) {
                throw new RuntimeException("Too many cpp compilers in toolchain.");
            }

            String cCompiler = null;
            String cppCompiler = null;

            if (cCompilers.size() == 1) {
                cCompiler = cCompilers.iterator().next();
                toolChainToCCompiler.put(toolchain, cCompiler);
            }

            if (cppCompilers.size() == 1) {
                cppCompiler = cppCompilers.iterator().next();
                toolChainToCppCompiler.put(toolchain, cppCompiler);
            }

            // Record the weird file extensions.
            for (String compiler : compilerToWeirdExtensions.keySet()) {
                if (compiler.equals(cCompiler)) {
                    cFileExtensions.addAll(compilerToWeirdExtensions.get(compiler));
                } else if (compiler.equals(cppCompiler)) {
                    cppFileExtensions.addAll(compilerToWeirdExtensions.get(compiler));
                }
            }
        }
    }

    private static String findToolChainName(String output) {
        return "toolchain-" + getParentFolder(output);
    }

    private void findToolchainNames() {
        for (Output output : outputs) {
            output.toolchain = findToolChainName(output.outputName);
        }
    }

    private Map<String, NativeLibraryValue> generateLibraries() {
        // Sort by library name so that output is stable
        Collections.sort(outputs, new Comparator<Output>() {
            @Override
            public int compare(Output o1, Output o2) {
                return o1.libraryName.compareTo(o2.libraryName);
            }
        });

        Map<String, NativeLibraryValue> librariesMap = new HashMap<String, NativeLibraryValue>();

        for (Output output : outputs) {
            NativeLibraryValue value = new NativeLibraryValue();
            librariesMap.put(output.libraryName, value);
            value.buildCommand = output.buildCommand;
            value.abi = getParentFolder(output.outputName);
            value.toolchain = output.toolchain;
            value.output = new File(output.outputName);
            value.files = new ArrayList<NativeSourceFileValue>();

            for (BuildStepInfo input : output.commandInputs) {
                NativeSourceFileValue file = new NativeSourceFileValue();
                value.files.add(file);
                file.src = new File(input.getOnlyInput());
                List<String> flags = new ArrayList<String>();
                for (int i = 0; i < input.getCommand().args.size(); ++i) {
                    String arg = input.getCommand().args.get(i);
                    if (STRIP_FLAGS.contains(arg)) {
                        ++i; // skip the next argument.
                        continue;
                    }
                    if (startsWithStripFlag(arg)) {
                        continue;
                    }
                    flags.add(arg);
                }
                file.flags = StringHelper.quoteAndJoinTokens(flags);
            }
        }

        return librariesMap;
    }

    private static boolean startsWithStripFlag(String arg) {
        for (String flag : STRIP_FLAGS) {
            if (arg.startsWith(flag)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, NativeToolchainValue> generateToolchains() {
        Set<String> toolchainSet = new HashSet<String>();
        for (Output output : outputs) {
            toolchainSet.add(output.toolchain);
        }
        List<String> toolchains = new ArrayList<String>(toolchainSet);
        Collections.sort(toolchains);

        Map<String, NativeToolchainValue> toolchainsMap = new HashMap<String, NativeToolchainValue>();

        for (String toolchain : toolchains) {
            NativeToolchainValue toolchainValue = new NativeToolchainValue();
            toolchainsMap.put(toolchain, toolchainValue);

            if (toolChainToCCompiler.containsKey(toolchain)) {
                toolchainValue.cCompilerExecutable = new File(toolChainToCCompiler.get(toolchain));
            }
            if (toolChainToCppCompiler.containsKey(toolchain)) {
                toolchainValue.cppCompilerExecutable = new File(toolChainToCppCompiler.get(toolchain));
            }
        }
        return toolchainsMap;
    }

    private static class Output {
        final String outputName;
        final List<BuildStepInfo> commandInputs;
        final String buildCommand;
        final String variantName;
        String libraryName;
        String toolchain;

        Output(String outputName, List<BuildStepInfo> commandInputs,
                String buildCommand, String variantName) {
            this.outputName = outputName;
            this.commandInputs = commandInputs;
            this.buildCommand = buildCommand;
            this.variantName = variantName;
        }
    }
}