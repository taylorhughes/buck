/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.DependencyMode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Optional;

public class RobolectricTestDescription implements Description<RobolectricTestDescription.Arg> {


  private final JavaOptions javaOptions;
  private final JavacOptions templateOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public RobolectricTestDescription(
      JavaOptions javaOptions,
      JavacOptions templateOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.javaOptions = javaOptions;
    this.templateOptions = templateOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            templateOptions,
            params,
            resolver,
            ruleFinder,
            args);

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyReplacingExtraDeps(
            Suppliers.ofInstance(resolver.getAllRules(args.exportedDeps))),
        javacOptions,
        DependencyMode.TRANSITIVE,
        /* forceFinalResourceIds */ true,
        /* resourceUnionPackage */ Optional.empty(),
        /* rName */ Optional.empty(),
        args.useOldStyleableFormat);

    if (CalculateAbi.isAbiTarget(params.getBuildTarget())) {
      if (params.getBuildTarget().getFlavors().contains(
          AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR)) {
        return graphEnhancer.getBuildableForAndroidResourcesAbi(resolver, ruleFinder);
      }
      BuildTarget testTarget = CalculateAbi.getLibraryTarget(params.getBuildTarget());
      BuildRule testRule = resolver.requireRule(testTarget);
      return CalculateAbi.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          Preconditions.checkNotNull(testRule.getSourcePathToOutput()));
    }

    ImmutableList<String> vmArgs = args.vmArgs;

    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ true);

    ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries = ImmutableSet.of();
    if (dummyRDotJava.isPresent()) {
      additionalClasspathEntries = ImmutableSet.of(
          Either.ofLeft(dummyRDotJava.get().getSourcePathToOutput()));
      ImmutableSortedSet<BuildRule> newExtraDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(params.getExtraDeps().get())
          .add(dummyRDotJava.get())
          .build();
      params = params.copyReplacingExtraDeps(Suppliers.ofInstance(newExtraDeps));
    }

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            params,
            args.useCxxLibraries,
            args.cxxLibraryWhitelist,
            resolver,
            ruleFinder,
            cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    // Rewrite dependencies on tests to actually depend on the code which backs the test.
    BuildRuleParams testsLibraryParams = params.copyReplacingDeclaredAndExtraDeps(
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(params.getDeclaredDeps().get())
                .addAll(BuildRules.getExportedRules(
                    Iterables.concat(
                        params.getDeclaredDeps().get(),
                        resolver.getAllRules(args.providedDeps))))
                .build()),
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(params.getExtraDeps().get())
                .addAll(ruleFinder.filterBuildRuleInputs(
                    javacOptions.getInputs(ruleFinder)))
                .build()))
        .withAppendedFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavacToJarStepFactory compileStepFactory = new JavacToJarStepFactory(
        javacOptions,
        new BootClasspathAppender());
    JavaLibrary testsLibrary =
        resolver.addToIndex(
            DefaultJavaLibrary
                .builder(testsLibraryParams, resolver, compileStepFactory)
                .setArgs(args)
                .setGeneratedSourceFolder(javacOptions.getGeneratedSourceFolderName())
                .setTrackClassUsage(javacOptions.trackClassUsage())
                .setAdditionalClasspathEntries(additionalClasspathEntries)
                .build());


    return new RobolectricTest(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        ruleFinder,
        testsLibrary,
        additionalClasspathEntries,
        args.labels,
        args.contacts,
        TestType.JUNIT,
        javaOptions,
        vmArgs,
        cxxLibraryEnhancement.nativeLibsEnvironment,
        dummyRDotJava,
        args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.testCaseTimeoutMs,
        args.env,
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.stdOutLogLevel,
        args.stdErrLogLevel,
        args.robolectricRuntimeDependency,
        args.robolectricManifest);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaTestDescription.Arg {
    public Optional<String> robolectricRuntimeDependency;
    public Optional<SourcePath> robolectricManifest;
    public boolean useOldStyleableFormat = false;
  }
}
