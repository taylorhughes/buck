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

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.DependencyMode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

public class AndroidLibraryDescription
    implements Description<AndroidLibraryDescription.Arg>, Flavored,
    ImplicitDepsInferringDescription<AndroidLibraryDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("android_library");

  private static final Flavor DUMMY_R_DOT_JAVA_FLAVOR =
      AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR;

  public enum JvmLanguage {
    JAVA,
    KOTLIN,
    SCALA,
  }

  private final JavacOptions defaultOptions;
  private final AndroidLibraryCompilerFactory compilerFactory;

  public AndroidLibraryDescription(
      JavacOptions defaultOptions,
      AndroidLibraryCompilerFactory compilerFactory) {
    this.defaultOptions = defaultOptions;
    this.compilerFactory = compilerFactory;
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
    if (params.getBuildTarget().getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(params, args.srcs, args.mavenCoords);
    }

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    JavacOptions javacOptions = JavacOptionsFactory.create(
        defaultOptions,
        params,
        resolver,
        ruleFinder,
        args
    );

    final ImmutableSet.Builder<BuildRule> queriedDepsBuilder = ImmutableSet.builder();
    if (args.depsQuery.isPresent()) {
      queriedDepsBuilder.addAll(
          QueryUtils.resolveDepQuery(
              params,
              args.depsQuery.get(),
              resolver,
              cellRoots,
              targetGraph)
              .collect(Collectors.toList()));

    }
    final ImmutableSet<BuildRule> queriedDeps = queriedDepsBuilder.build();

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyReplacingExtraDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(queriedDeps)
                    .addAll(resolver.getAllRules(args.exportedDeps))
                    .build())),
        javacOptions,
        DependencyMode.FIRST_ORDER,
        /* forceFinalResourceIds */ false,
        args.resourceUnionPackage,
        args.finalRName,
        false);

    boolean hasDummyRDotJavaFlavor =
        params.getBuildTarget().getFlavors().contains(DUMMY_R_DOT_JAVA_FLAVOR);
    if (CalculateAbi.isAbiTarget(params.getBuildTarget())) {
      if (hasDummyRDotJavaFlavor) {
        return graphEnhancer.getBuildableForAndroidResourcesAbi(resolver, ruleFinder);
      }
      BuildTarget libraryTarget = CalculateAbi.getLibraryTarget(params.getBuildTarget());
      BuildRule libraryRule = resolver.requireRule(libraryTarget);
      return CalculateAbi.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          Preconditions.checkNotNull(libraryRule.getSourcePathToOutput()));
    }
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ hasDummyRDotJavaFlavor);

    if (hasDummyRDotJavaFlavor) {
      return dummyRDotJava.get();
    } else {
      ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries = ImmutableSet.of();
      if (dummyRDotJava.isPresent()) {
        additionalClasspathEntries = ImmutableSet.of(
            Either.ofLeft(dummyRDotJava.get().getSourcePathToOutput()));
        ImmutableSortedSet<BuildRule> newDeclaredDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(params.getDeclaredDeps().get())
            .add(dummyRDotJava.get())
            .build();
        params = params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(newDeclaredDeps),
            params.getExtraDeps());
      }

      AndroidLibraryCompiler compiler =
          compilerFactory.getCompiler(args.language.orElse(JvmLanguage.JAVA));

      ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps);

      ImmutableSortedSet.Builder<BuildRule> declaredDepsBuilder =
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(params.getDeclaredDeps().get())
              .addAll(queriedDeps)
              .addAll(compiler.getDeclaredDeps(args, resolver));

      ImmutableSortedSet<BuildRule> declaredDeps = declaredDepsBuilder.build();

      ImmutableSortedSet<BuildRule> extraDeps =
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(params.getExtraDeps().get())
              .addAll(BuildRules.getExportedRules(
                  Iterables.concat(
                      declaredDeps,
                      exportedDeps,
                      resolver.getAllRules(args.providedDeps))))
              .addAll(ruleFinder.filterBuildRuleInputs(javacOptions.getInputs(ruleFinder)))
              .addAll(compiler.getExtraDeps(args, resolver))
              .build();

      BuildRuleParams androidLibraryParams =
          params.copyReplacingDeclaredAndExtraDeps(
              Suppliers.ofInstance(declaredDeps),
              Suppliers.ofInstance(extraDeps));
      return AndroidLibrary.builder(
          androidLibraryParams,
          resolver,
          compiler.compileToJar(args, javacOptions, resolver),
          javacOptions)
          .setArgs(args)
          .setAdditionalClasspathEntries(additionalClasspathEntries)
          .setTrackClassUsage(compiler.trackClassUsage(javacOptions))
          .setTests(args.tests)
          .build();
    }
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.isEmpty() ||
        flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR)) ||
        flavors.equals(ImmutableSet.of(DUMMY_R_DOT_JAVA_FLAVOR));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return compilerFactory.getCompiler(constructorArg.language.orElse(JvmLanguage.JAVA))
        .findDepsForTargetFromConstructorArgs(buildTarget, cellRoots, constructorArg);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public Optional<SourcePath> manifest;
    public Optional<String> resourceUnionPackage;
    public Optional<String> finalRName;
    public Optional<JvmLanguage> language;
    public Optional<Query> depsQuery;
  }
}

