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

package com.facebook.buck.jvm.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;

public class PrebuiltJarDescription implements Description<PrebuiltJarDescription.Arg> {

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

    if (CalculateAbi.isAbiTarget(params.getBuildTarget())) {
      return CalculateAbi.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          args.binaryJar);
    }

    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    BuildRule prebuilt = new PrebuiltJar(
        params,
        pathResolver,
        args.binaryJar,
        args.sourceJar,
        args.gwtJar,
        args.javadocUrl,
        args.mavenCoords,
        args.provided.orElse(false));

    params.getBuildTarget().checkUnflavored();
    BuildRuleParams gwtParams = params
        .withAppendedFlavor(JavaLibrary.GWT_MODULE_FLAVOR)
        .copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(prebuilt)),
            Suppliers.ofInstance(ImmutableSortedSet.of()));
    BuildRule gwtModule = createGwtModule(gwtParams, args);
    resolver.addToIndex(gwtModule);

    return prebuilt;
  }

  @VisibleForTesting
  static BuildRule createGwtModule(BuildRuleParams params, Arg arg) {
    // Because a PrebuiltJar rarely requires any building whatsoever (it could if the source_jar
    // is a BuildTargetSourcePath), we make the PrebuiltJar a dependency of the GWT module. If this
    // becomes a performance issue in practice, then we will explore reducing the dependencies of
    // the GWT module.
    final SourcePath input;
    if (arg.gwtJar.isPresent()) {
      input = arg.gwtJar.get();
    } else if (arg.sourceJar.isPresent()) {
      input = arg.sourceJar.get();
    } else {
      input = arg.binaryJar;
    }

    class ExistingOuputs extends AbstractBuildRule {
      @AddToRuleKey
      private final SourcePath source;
      private final Path output;

      protected ExistingOuputs(
          BuildRuleParams params,
          SourcePath source) {
        super(params);
        this.source = source;
        BuildTarget target = params.getBuildTarget();
        this.output = BuildTargets.getGenPath(
            getProjectFilesystem(),
            target,
            String.format("%s/%%s-gwt.jar", target.getShortName()));
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context,
          BuildableContext buildableContext) {
        buildableContext.recordArtifact(
            context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));

        ImmutableList.Builder<Step> steps = ImmutableList.builder();
        steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), output.getParent()));
        steps.add(CopyStep.forFile(
                getProjectFilesystem(),
                context.getSourcePathResolver().getAbsolutePath(source),
                output));

        return steps.build();
      }

      @Override
      public SourcePath getSourcePathToOutput() {
        return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
      }
    }
    return new ExistingOuputs(params, input);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourcePath binaryJar;
    public Optional<SourcePath> sourceJar;
    public Optional<SourcePath> gwtJar;
    public Optional<String> javadocUrl;
    public Optional<String> mavenCoords;
    public Optional<Boolean> provided;

    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
  }
}
