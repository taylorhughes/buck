/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Standard set of parameters that is passed to all build rules.
 */
public class BuildRuleParams {

  private final BuildTarget buildTarget;
  private final Supplier<ImmutableSortedSet<BuildRule>> declaredDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> extraDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> totalDeps;
  private final ProjectFilesystem projectFilesystem;

  public BuildRuleParams(
      BuildTarget buildTarget,
      final Supplier<ImmutableSortedSet<BuildRule>> declaredDeps,
      final Supplier<ImmutableSortedSet<BuildRule>> extraDeps,
      ProjectFilesystem projectFilesystem) {
    this.buildTarget = buildTarget;
    this.declaredDeps = Suppliers.memoize(declaredDeps);
    this.extraDeps = Suppliers.memoize(extraDeps);
    this.projectFilesystem = projectFilesystem;

    this.totalDeps = Suppliers.memoize(
        () -> ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(declaredDeps.get())
            .addAll(extraDeps.get())
            .build());
  }

  private BuildRuleParams(
      BuildRuleParams baseForDeps,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem) {
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.declaredDeps = baseForDeps.declaredDeps;
    this.extraDeps = baseForDeps.extraDeps;
    this.totalDeps = baseForDeps.totalDeps;
  }

  public BuildRuleParams copyReplacingExtraDeps(Supplier<ImmutableSortedSet<BuildRule>> extraDeps) {
    return copyReplacingDeclaredAndExtraDeps(declaredDeps, extraDeps);
  }

  public BuildRuleParams copyAppendingExtraDeps(
      final Supplier<? extends Iterable<? extends BuildRule>> additional) {
    return copyReplacingDeclaredAndExtraDeps(
        declaredDeps,
        () -> ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(extraDeps.get())
            .addAll(additional.get())
            .build());
  }

  public BuildRuleParams copyAppendingExtraDeps(Iterable<? extends BuildRule> additional) {
    return copyAppendingExtraDeps(Suppliers.ofInstance(additional));
  }

  public BuildRuleParams copyAppendingExtraDeps(BuildRule... additional) {
    return copyAppendingExtraDeps(Suppliers.ofInstance(ImmutableList.copyOf(additional)));
  }

  public BuildRuleParams copyReplacingDeclaredAndExtraDeps(
      Supplier<ImmutableSortedSet<BuildRule>> declaredDeps,
      Supplier<ImmutableSortedSet<BuildRule>> extraDeps) {
    return new BuildRuleParams(buildTarget, declaredDeps, extraDeps, projectFilesystem);
  }

  public BuildRuleParams withBuildTarget(BuildTarget target) {
    return new BuildRuleParams(this, target, projectFilesystem);
  }

  public BuildRuleParams withoutFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.remove(flavor);
    BuildTarget target = BuildTarget
        .builder(getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    return new BuildRuleParams(this, target, projectFilesystem);
  }

  public BuildRuleParams withAppendedFlavor(Flavor flavor) {
    Set<Flavor> flavors = Sets.newHashSet(getBuildTarget().getFlavors());
    flavors.add(flavor);
    BuildTarget target = BuildTarget
        .builder(getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    return new BuildRuleParams(this, target, projectFilesystem);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public ImmutableSortedSet<BuildRule> getDeps() {
    return totalDeps.get();
  }

  public Supplier<ImmutableSortedSet<BuildRule>> getTotalDeps() {
    return totalDeps;
  }

  public Supplier<ImmutableSortedSet<BuildRule>> getDeclaredDeps() {
    return declaredDeps;
  }

  public Supplier<ImmutableSortedSet<BuildRule>> getExtraDeps() {
    return extraDeps;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

}
