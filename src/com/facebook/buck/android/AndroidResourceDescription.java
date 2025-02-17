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

import com.facebook.buck.android.aapt.MiniAapt;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractMap;
import java.util.Optional;

public class AndroidResourceDescription
    implements Description<AndroidResourceDescription.Arg>, Flavored {

  private static final ImmutableSet<String> NON_ASSET_FILENAMES = ImmutableSet.of(
      ".gitkeep",
      ".svn",
      ".git",
      ".ds_store",
      ".scc",
      "cvs",
      "thumbs.db",
      "picasa.ini");

  private final boolean isGrayscaleImageProcessingEnabled;

  @VisibleForTesting
  static final Flavor RESOURCES_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("resources-symlink-tree");

  @VisibleForTesting
  static final Flavor ASSETS_SYMLINK_TREE_FLAVOR =
      InternalFlavor.of("assets-symlink-tree");

  public AndroidResourceDescription(boolean enableGrayscaleImageProcessing) {
    isGrayscaleImageProcessingEnabled = enableGrayscaleImageProcessing;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    if (params.getBuildTarget().getFlavors().contains(RESOURCES_SYMLINK_TREE_FLAVOR)) {
      return createSymlinkTree(ruleFinder, params, args.res, "res");
    } else if (params.getBuildTarget().getFlavors().contains(ASSETS_SYMLINK_TREE_FLAVOR)) {
      return createSymlinkTree(ruleFinder, params, args.assets, "assets");
    }

    // Only allow android resource and library rules as dependencies.
    Optional<BuildRule> invalidDep = params.getDeclaredDeps().get().stream()
        .filter(rule -> !(rule instanceof AndroidResource || rule instanceof AndroidLibrary))
        .findFirst();
    if (invalidDep.isPresent()) {
      throw new HumanReadableException(
          params.getBuildTarget() + " (android_resource): dependency " +
              invalidDep.get().getBuildTarget() + " (" + invalidDep.get().getType() +
              ") is not of type android_resource or android_library.");
    }

    // We don't handle the resources parameter well in `AndroidResource` rules, as instead of
    // hashing the contents of the entire resources directory, we try to filter out anything that
    // doesn't look like a resource.  This means when our resources are supplied from another rule,
    // we have to resort to some hackery to make sure things work correctly.
    Pair<Optional<SymlinkTree>, Optional<SourcePath>> resInputs =
        collectInputSourcePaths(
            resolver,
            params.getBuildTarget(),
            RESOURCES_SYMLINK_TREE_FLAVOR,
            args.res);
    Pair<Optional<SymlinkTree>, Optional<SourcePath>> assetsInputs =
        collectInputSourcePaths(
            resolver,
            params.getBuildTarget(),
            ASSETS_SYMLINK_TREE_FLAVOR,
            args.assets);

    params = params.copyAppendingExtraDeps(
        Iterables.concat(
            resInputs.getSecond().map(ruleFinder::filterBuildRuleInputs)
                .orElse(ImmutableSet.of()),
            assetsInputs.getSecond().map(ruleFinder::filterBuildRuleInputs)
                .orElse(ImmutableSet.of())));

    return new AndroidResource(
        // We only propagate other AndroidResource rule dependencies, as these are
        // the only deps which should control whether we need to re-run the aapt_package
        // step.
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(
                AndroidResourceHelper.androidResOnly(params.getDeclaredDeps().get())),
            params.getExtraDeps()),
        ruleFinder,
        resolver.getAllRules(args.deps),
        resInputs.getSecond().orElse(null),
        resInputs.getFirst().map(SymlinkTree::getLinks).orElse(ImmutableSortedMap.of()),
        args.rDotJavaPackage.orElse(null),
        assetsInputs.getSecond().orElse(null),
        assetsInputs.getFirst().map(SymlinkTree::getLinks).orElse(ImmutableSortedMap.of()),
        args.manifest.orElse(null),
        args.hasWhitelistedStrings.orElse(false),
        args.resourceUnion.orElse(false),
        isGrayscaleImageProcessingEnabled);
  }

  private SymlinkTree createSymlinkTree(
      SourcePathRuleFinder ruleFinder,
      BuildRuleParams params,
      Optional<Either<SourcePath, ImmutableSortedMap<String, SourcePath>>> symlinkAttribute,
      String outputDirName) {
    ImmutableMap<Path, SourcePath> links = ImmutableMap.of();
    if (symlinkAttribute.isPresent()) {
      if (symlinkAttribute.get().isLeft()) {
        // If our resources are coming from a `PathSourcePath`, we collect only the inputs we care
        // about and pass those in separately, so that that `AndroidResource` rule knows to only
        // hash these into it's rule key.
        // TODO(k21): This is deprecated and should be disabled or removed.
        // Accessing the filesystem during rule creation is problematic because the accesses are
        // not cached or tracked in any way.
        Preconditions.checkArgument(
            symlinkAttribute.get().getLeft() instanceof PathSourcePath,
            "Resource or asset symlink tree can only be built for a PathSourcePath");
        PathSourcePath path = (PathSourcePath) symlinkAttribute.get().getLeft();
        links = collectInputFiles(path.getFilesystem(), path.getRelativePath());
      } else {
        links = RichStream.from(symlinkAttribute.get().getRight().entrySet())
            .map(e -> new AbstractMap.SimpleEntry<>(Paths.get(e.getKey()), e.getValue()))
            .filter(e -> isPossibleResourcePath(e.getKey()))
            .collect(MoreCollectors.toImmutableMap(e -> e.getKey(), e -> e.getValue()));
      }
    }
    Path symlinkTreeRoot =
        BuildTargets
            .getGenPath(params.getProjectFilesystem(), params.getBuildTarget(), "%s")
            .resolve(outputDirName);
    return new SymlinkTree(
        params.getBuildTarget(),
        params.getProjectFilesystem(),
        symlinkTreeRoot,
        links,
        ruleFinder);
  }

  public static Optional<SourcePath> getResDirectoryForProject(
      BuildRuleResolver ruleResolver,
      TargetNode<Arg, ?> node) {
    Arg arg = node.getConstructorArg();
    if (arg.projectRes.isPresent()) {
      return Optional.of(
          new PathSourcePath(node.getFilesystem(), arg.projectRes.get()));
    }
    if (!arg.res.isPresent()) {
      return Optional.empty();
    }
    if (arg.res.get().isLeft()) {
      return Optional.of(arg.res.get().getLeft());
    } else {
      return getResDirectory(ruleResolver, node);
    }
  }

  public static Optional<SourcePath> getAssetsDirectoryForProject(
      BuildRuleResolver ruleResolver,
      TargetNode<Arg, ?> node) {
    Arg arg = node.getConstructorArg();
    if (arg.projectAssets.isPresent()) {
      return Optional.of(
          new PathSourcePath(node.getFilesystem(), arg.projectAssets.get()));
    }
    if (!arg.assets.isPresent()) {
      return Optional.empty();
    }
    if (arg.assets.get().isLeft()) {
      return Optional.of(arg.assets.get().getLeft());
    } else {
      return getAssetsDirectory(ruleResolver, node);
    }
  }

  private static Optional<SourcePath> getResDirectory(
      BuildRuleResolver ruleResolver,
      TargetNode<Arg, ?> node) {
    return collectInputSourcePaths(
        ruleResolver,
        node.getBuildTarget(),
        RESOURCES_SYMLINK_TREE_FLAVOR,
        node.getConstructorArg().res)
        .getSecond();
  }

  private static Optional<SourcePath> getAssetsDirectory(
      BuildRuleResolver ruleResolver,
      TargetNode<Arg, ?> node) {
    return collectInputSourcePaths(
        ruleResolver,
        node.getBuildTarget(),
        ASSETS_SYMLINK_TREE_FLAVOR,
        node.getConstructorArg().assets)
        .getSecond();
  }

  private static Pair<Optional<SymlinkTree>, Optional<SourcePath>> collectInputSourcePaths(
      BuildRuleResolver ruleResolver,
      BuildTarget resourceRuleTarget,
      Flavor symlinkTreeFlavor,
      Optional<Either<SourcePath, ImmutableSortedMap<String, SourcePath>>> attribute) {
    if (!attribute.isPresent()) {
      return new Pair<>(Optional.empty(), Optional.empty());
    }
    if (attribute.get().isLeft()) {
      SourcePath inputSourcePath = attribute.get().getLeft();
      if (!(inputSourcePath instanceof PathSourcePath)) {
        // If the resources are generated by a rule, we can't inspect the contents of the directory
        // in advance to create a symlink tree.  Instead, we have to pass the source path as is.
        return new Pair<>(Optional.empty(), Optional.of(inputSourcePath));
      }
    }
    BuildTarget symlinkTreeTarget = resourceRuleTarget.withAppendedFlavors(symlinkTreeFlavor);
    SymlinkTree symlinkTree;
    try {
      symlinkTree = (SymlinkTree) ruleResolver.requireRule(symlinkTreeTarget);
    } catch (NoSuchBuildTargetException e) {
      throw new RuntimeException(e);
    }
    return new Pair<>(Optional.of(symlinkTree), Optional.of(symlinkTree.getSourcePathToOutput()));
  }

  @VisibleForTesting
  ImmutableSortedMap<Path, SourcePath> collectInputFiles(
      final ProjectFilesystem filesystem,
      Path inputDir) {
    final ImmutableSortedMap.Builder<Path, SourcePath> paths = ImmutableSortedMap.naturalOrder();

    // We ignore the same files that mini-aapt and aapt ignore.
    FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(
          Path dir,
          BasicFileAttributes attr) throws IOException {
        String dirName = dir.getFileName().toString();
        // Special case: directory starting with '_' as per aapt.
        if (dirName.charAt(0) == '_' || !isPossibleResourceName(dirName)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
        String filename = file.getFileName().toString();
        if (isPossibleResourceName(filename)) {
          paths.put(MorePaths.relativize(inputDir, file), new PathSourcePath(filesystem, file));
        }
        return FileVisitResult.CONTINUE;
      }

    };

    try {
      filesystem.walkRelativeFileTree(inputDir, fileVisitor);
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Error while searching for android resources in directory %s.",
          inputDir);
    }
    return paths.build();
  }

  @VisibleForTesting
  static boolean isPossibleResourcePath(Path filePath) {
    for (Path component : filePath) {
      if (!isPossibleResourceName(component.toString())) {
        return false;
      }
    }
    Path parentPath = filePath.getParent();
    if (parentPath != null) {
      for (Path component : parentPath) {
        if (component.toString().startsWith("_")) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean isPossibleResourceName(String fileOrDirName) {
    if (NON_ASSET_FILENAMES.contains(fileOrDirName.toLowerCase())) {
      return false;
    }
    if (fileOrDirName.charAt(fileOrDirName.length() - 1) == '~') {
      return false;
    }
    if (MiniAapt.IGNORED_FILE_EXTENSIONS.contains(Files.getFileExtension(fileOrDirName))) {
      return false;
    }
    return true;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (flavors.isEmpty()) {
      return true;
    }

    if (flavors.size() == 1) {
      Flavor flavor = flavors.iterator().next();
      if (flavor.equals(RESOURCES_SYMLINK_TREE_FLAVOR) ||
          flavor.equals(ASSETS_SYMLINK_TREE_FLAVOR)) {
        return true;
      }
    }

    return false;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<Either<SourcePath, ImmutableSortedMap<String, SourcePath>>> res;
    public Optional<Either<SourcePath, ImmutableSortedMap<String, SourcePath>>> assets;
    public Optional<Path> projectRes;
    public Optional<Path> projectAssets;
    public Optional<Boolean> hasWhitelistedStrings;
    @Hint(name = "package")
    public Optional<String> rDotJavaPackage;
    public Optional<SourcePath> manifest;

    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<Boolean> resourceUnion;
  }
}
