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

package com.facebook.buck.gwt;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.gwt.GwtBinary.Style;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class GwtBinaryDescription implements Description<GwtBinaryDescription.Arg> {

  /** Default value for {@link Arg#style}. */
  private static final Style DEFAULT_STYLE = Style.OBF;

  /** Default value for {@link Arg#localWorkers}. */
  private static final Integer DEFAULT_NUM_LOCAL_WORKERS = Integer.valueOf(2);

  /** Default value for {@link Arg#draftCompile}. */
  private static final Boolean DEFAULT_DRAFT_COMPILE = Boolean.FALSE;

  /** Default value for {@link Arg#strict}. */
  private static final Boolean DEFAULT_STRICT = Boolean.FALSE;

  /**
   * This value is taken from GWT's source code: http://bit.ly/1nZtmMv
   */
  private static final Integer DEFAULT_OPTIMIZE = Integer.valueOf(9);

  private final JavaOptions javaOptions;

  public GwtBinaryDescription(JavaOptions javaOptions) {
    this.javaOptions = javaOptions;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    final ImmutableSortedSet.Builder<BuildRule> extraDeps = ImmutableSortedSet.naturalOrder();

    // Find all of the reachable JavaLibrary rules and grab their associated GwtModules.
    final ImmutableSortedSet.Builder<SourcePath> gwtModuleJarsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet<BuildRule> moduleDependencies = resolver.getAllRules(args.moduleDeps);
    new AbstractBreadthFirstTraversal<BuildRule>(moduleDependencies) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (!(rule instanceof JavaLibrary)) {
          return ImmutableSet.of();
        }

        // If the java library doesn't generate any output, it doesn't contribute a GwtModule
        JavaLibrary javaLibrary = (JavaLibrary) rule;
        if (javaLibrary.getSourcePathToOutput() == null) {
          return rule.getDeps();
        }

        BuildTarget gwtModuleTarget = BuildTargets.createFlavoredBuildTarget(
            javaLibrary.getBuildTarget().checkUnflavored(),
            JavaLibrary.GWT_MODULE_FLAVOR);
        Optional<BuildRule> gwtModule = resolver.getRuleOptional(gwtModuleTarget);
        if (!gwtModule.isPresent() && javaLibrary.getSourcePathToOutput() != null) {
          ImmutableSortedSet<SourcePath> filesForGwtModule =
              ImmutableSortedSet.<SourcePath>naturalOrder()
                  .addAll(javaLibrary.getSources())
                  .addAll(javaLibrary.getResources())
                  .build();
          ImmutableSortedSet<BuildRule> deps =
              ImmutableSortedSet.copyOf(ruleFinder.filterBuildRuleInputs(filesForGwtModule));

          BuildRule module = resolver.addToIndex(
              new GwtModule(
                  params
                      .withBuildTarget(gwtModuleTarget)
                      .copyReplacingDeclaredAndExtraDeps(
                          Suppliers.ofInstance(deps),
                          Suppliers.ofInstance(ImmutableSortedSet.of())),
                  ruleFinder,
                  filesForGwtModule));
          gwtModule = Optional.of(module);
        }

        // Note that gwtModule could be absent if javaLibrary is a rule with no srcs of its own,
        // but a rule that exists only as a collection of deps.
        if (gwtModule.isPresent()) {
          extraDeps.add(gwtModule.get());
          gwtModuleJarsBuilder.add(
              Preconditions.checkNotNull(gwtModule.get().getSourcePathToOutput()));
        }

        // Traverse all of the deps of this rule.
        return rule.getDeps();
      }
    }.start();

    return new GwtBinary(
        params.copyReplacingExtraDeps(Suppliers.ofInstance(extraDeps.build())),
        args.modules,
        javaOptions.getJavaRuntimeLauncher(),
        args.vmArgs,
        args.style.orElse(DEFAULT_STYLE),
        args.draftCompile.orElse(DEFAULT_DRAFT_COMPILE),
        args.optimize.orElse(DEFAULT_OPTIMIZE),
        args.localWorkers.orElse(DEFAULT_NUM_LOCAL_WORKERS),
        args.strict.orElse(DEFAULT_STRICT),
        args.experimentalArgs,
        gwtModuleJarsBuilder.build());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<String> modules = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> moduleDeps = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();

    /**
     * In practice, these may be values such as {@code -Xmx512m}.
     */
    public ImmutableList<String> vmArgs = ImmutableList.of();

    /** This will be passed to the GWT Compiler's {@code -style} flag. */
    public Optional<Style> style;

    /** If {@code true}, the GWT Compiler's {@code -draftCompile} flag will be set. */
    public Optional<Boolean> draftCompile;

    /** This will be passed to the GWT Compiler's {@code -optimize} flag. */
    public Optional<Integer> optimize;

    /** This will be passed to the GWT Compiler's {@code -localWorkers} flag. */
    public Optional<Integer> localWorkers;

    /** If {@code true}, the GWT Compiler's {@code -strict} flag will be set. */
    public Optional<Boolean> strict;

    /**
     * In practice, these may be values such as {@code -XenableClosureCompiler},
     * {@code -XdisableClassMetadata}, {@code -XdisableCastChecking}, or {@code -XfragmentMerge}.
     */
    public ImmutableList<String> experimentalArgs = ImmutableList.of();
  }
}
