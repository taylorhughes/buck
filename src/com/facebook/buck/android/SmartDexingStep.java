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
package com.facebook.buck.android;

import com.facebook.buck.android.DxStep.Option;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.CompositeStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipCompressionLevel;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * Optimized dx command runner which can invoke multiple dx commands in parallel and also avoid
 * doing unnecessary dx invocations in the first place.
 * <p>
 * This is most appropriately represented as a build rule itself (which depends on individual dex
 * rules) however this would require significant refactoring of AndroidBinaryRule that would be
 * disruptive to other initiatives in flight (namely, ApkBuilder).  It is also debatable that it is
 * even the right course of action given that it would require dynamically modifying the DAG.
 */
public class SmartDexingStep implements Step {

  public static final String SHORT_NAME = "smart_dex";
  private static final String SECONDARY_SOLID_DEX_EXTENSION = ".dex.jar.xzs";

  public interface DexInputHashesProvider {
    ImmutableMap<Path, Sha1HashCode> getDexInputHashes();
  }

  private final ProjectFilesystem filesystem;
  private final Supplier<Multimap<Path, Path>> outputToInputsSupplier;
  private final Optional<Path> secondaryOutputDir;
  private final DexInputHashesProvider dexInputHashesProvider;
  private final Path successDir;
  private final EnumSet<DxStep.Option> dxOptions;
  private final ListeningExecutorService executorService;
  private final Optional<Integer> xzCompressionLevel;
  private final Optional<String> dxMaxHeapSize;

  /**
   * @param primaryOutputPath Path for the primary dex artifact.
   * @param primaryInputsToDex Set of paths to include as inputs for the primary dex artifact.
   * @param secondaryOutputDir Directory path for the secondary dex artifacts, if there are any.
   *     Note that this directory will be pruned such that only those secondary outputs generated
   *     by this command will remain in the directory!
   * @param secondaryInputsToDex List of paths to input jar files, to use as dx input, keyed by the
   *     corresponding output dex file.
   *     Note that for each output file (key), a separate dx invocation will be started with the
   *     corresponding jar files (value) as the input.
   * @param successDir Directory where success artifacts are written.
   * @param executorService The thread pool to execute the dx command on.
   */
  public SmartDexingStep(
      ProjectFilesystem filesystem,
      final Path primaryOutputPath,
      final Supplier<Set<Path>> primaryInputsToDex,
      Optional<Path> secondaryOutputDir,
      final Optional<Supplier<Multimap<Path, Path>>> secondaryInputsToDex,
      DexInputHashesProvider dexInputHashesProvider,
      Path successDir,
      EnumSet<Option> dxOptions,
      ListeningExecutorService executorService,
      Optional<Integer> xzCompressionLevel,
      Optional<String> dxMaxHeapSize) {
    this.filesystem = filesystem;
    this.outputToInputsSupplier = Suppliers.memoize(
        () -> {
          final ImmutableMultimap.Builder<Path, Path> map = ImmutableMultimap.builder();
          map.putAll(primaryOutputPath, primaryInputsToDex.get());
          if (secondaryInputsToDex.isPresent()) {
            map.putAll(secondaryInputsToDex.get().get());
          }
          return map.build();
        });
    this.secondaryOutputDir = secondaryOutputDir;
    this.dexInputHashesProvider = dexInputHashesProvider;
    this.successDir = successDir;
    this.dxOptions = dxOptions;
    this.executorService = executorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.dxMaxHeapSize = dxMaxHeapSize;
  }

  public static int determineOptimalThreadCount() {
    return Runtime.getRuntime().availableProcessors();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
    try {
      Multimap<Path, Path> outputToInputs = outputToInputsSupplier.get();
      runDxCommands(context, outputToInputs);
      if (secondaryOutputDir.isPresent()) {
        removeExtraneousSecondaryArtifacts(
            secondaryOutputDir.get(),
            outputToInputs.keySet(),
            filesystem);

        // Concatenate if solid compression is specified.
        // create a mapping of the xzs file target and the dex.jar files that go into it
        ImmutableMultimap.Builder<Path, Path> secondaryDexJarsMultimapBuilder =
            ImmutableMultimap.builder();
        for (Path p : outputToInputs.keySet()) {
          if (DexStore.XZS.matchesPath(p)) {
            String[] matches = p.getFileName().toString().split("-");
            Path output = p.getParent().resolve(matches[0].concat(SECONDARY_SOLID_DEX_EXTENSION));
            secondaryDexJarsMultimapBuilder.put(output, p);
          }
        }
        ImmutableMultimap<Path, Path> secondaryDexJarsMultimap =
            secondaryDexJarsMultimapBuilder.build();
        if (!secondaryDexJarsMultimap.isEmpty()) {
          for (Map.Entry<Path, Collection<Path>> entry :
              secondaryDexJarsMultimap.asMap().entrySet()) {
            Path store = entry.getKey();
            Collection<Path> secondaryDexJars = entry.getValue();
            // Construct the output path for our solid blob and its compressed form.
            Path secondaryBlobOutput = store.getParent().resolve("uncompressed.dex.blob");
            Path secondaryCompressedBlobOutput = store;
            // Concatenate the jars into a blob and compress it.
            StepRunner stepRunner = new DefaultStepRunner();
            Step concatStep = new ConcatStep(
                filesystem,
                ImmutableList.copyOf(secondaryDexJars),
                secondaryBlobOutput);
            Step xzStep;

            if (xzCompressionLevel.isPresent()) {
              xzStep = new XzStep(
                  filesystem,
                  secondaryBlobOutput,
                  secondaryCompressedBlobOutput,
                  xzCompressionLevel.get().intValue());
            } else {
              xzStep = new XzStep(filesystem, secondaryBlobOutput, secondaryCompressedBlobOutput);
            }
            stepRunner.runStepForBuildTarget(context, concatStep, Optional.empty());
            stepRunner.runStepForBuildTarget(context, xzStep, Optional.empty());
          }
        }
      }
    } catch (StepFailedException | IOException e) {
      context.logError(e, "There was an error in smart dexing step.");
      return StepExecutionResult.ERROR;
    }

    return StepExecutionResult.SUCCESS;
  }

  private void runDxCommands(ExecutionContext context, Multimap<Path, Path> outputToInputs)
      throws StepFailedException, InterruptedException {
    DefaultStepRunner stepRunner = new DefaultStepRunner();
    // Invoke dx commands in parallel for maximum thread utilization.  In testing, dx revealed
    // itself to be CPU (and not I/O) bound making it a good candidate for parallelization.
    List<Step> dxSteps = generateDxCommands(filesystem, outputToInputs);

    List<Callable<Void>> callables = Lists.transform(
        dxSteps,
        step -> (Callable<Void>) () -> {
          stepRunner.runStepForBuildTarget(context, step, Optional.empty());
          return null;
        });

    try {
      MoreFutures.getAll(executorService, callables);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.throwIfInstanceOf(cause, StepFailedException.class);

      // Programmer error.  Boo-urns.
      throw new RuntimeException(cause);
    }
  }

  /**
   * Prune the secondary output directory of any files that we didn't generate.  This is
   * needed because we crudely add all files in this directory to the final APK, but the number
   * may have been reduced due to split-zip having less code to process.
   * <p>
   * This is also a defensive measure to cleanup extraneous artifacts left behind due to
   * changes to buck itself.
   */
  private void removeExtraneousSecondaryArtifacts(
      Path secondaryOutputDir,
      Set<Path> producedArtifacts,
      ProjectFilesystem projectFilesystem) throws IOException {
    secondaryOutputDir = secondaryOutputDir.normalize();
    for (Path secondaryOutput : projectFilesystem.getDirectoryContents(secondaryOutputDir)) {
      if (!producedArtifacts.contains(secondaryOutput) &&
          !secondaryOutput.getFileName().toString().endsWith(".meta")) {
        projectFilesystem.deleteRecursivelyIfExists(secondaryOutput);
      }
    }
  }

  @Override
  public String getShortName() {
    return SHORT_NAME;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder b = new StringBuilder();
    b.append(getShortName());
    b.append(' ');

    Multimap<Path, Path> outputToInputs = outputToInputsSupplier.get();
    for (Path output : outputToInputs.keySet()) {
      b.append("-out ");
      b.append(output.toString());
      b.append("-in ");
      Joiner.on(':').appendTo(b,
          Iterables.transform(outputToInputs.get(output), Object::toString));
    }

    return b.toString();
  }

  /**
   * Once the {@code .class} files have been split into separate zip files, each must be converted
   * to a {@code .dex} file.
   */
  private ImmutableList<Step> generateDxCommands(
      ProjectFilesystem filesystem,
      Multimap<Path, Path> outputToInputs) {
    ImmutableList.Builder<DxPseudoRule> pseudoRules = ImmutableList.builder();

    ImmutableMap<Path, Sha1HashCode> dexInputHashes = dexInputHashesProvider.getDexInputHashes();

    for (Path outputFile : outputToInputs.keySet()) {
      pseudoRules.add(
          new DxPseudoRule(
              filesystem,
              dexInputHashes,
              ImmutableSet.copyOf(outputToInputs.get(outputFile)),
              outputFile,
              successDir.resolve(outputFile.getFileName()),
              dxOptions,
              xzCompressionLevel,
              dxMaxHeapSize));
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    for (DxPseudoRule pseudoRule : pseudoRules.build()) {
      if (!pseudoRule.checkIsCached()) {
        steps.addAll(pseudoRule.buildInternal());
      }
    }

    return steps.build();
  }

  /**
   * Internally designed to simulate a dexing buck rule so that once refactored more broadly as
   * such it should be straightforward to convert this code.
   * <p>
   * This pseudo rule does not use the normal .success file model but instead checksums its
   * inputs.  This is because the input zip files are guaranteed to have changed on the
   * filesystem (ZipSplitter will always write them out even if the same), but the contents
   * contained in the zip may not have changed.
   */
  @VisibleForTesting
  static class DxPseudoRule {
    private final ProjectFilesystem filesystem;
    private final Map<Path, Sha1HashCode> dexInputHashes;
    private final Set<Path> srcs;
    private final Path outputPath;
    private final Path outputHashPath;
    private final EnumSet<Option> dxOptions;
    @Nullable
    private String newInputsHash;
    private final Optional<Integer> xzCompressionLevel;
    private final Optional<String> dxMaxHeapSize;

    public DxPseudoRule(
        ProjectFilesystem filesystem,
        Map<Path, Sha1HashCode> dexInputHashes,
        Set<Path> srcs,
        Path outputPath,
        Path outputHashPath,
        EnumSet<Option> dxOptions,
        Optional<Integer> xzCompressionLevel,
        Optional<String> dxMaxHeapSize) {
      this.filesystem = filesystem;
      this.dexInputHashes = ImmutableMap.copyOf(dexInputHashes);
      this.srcs = ImmutableSet.copyOf(srcs);
      this.outputPath = outputPath;
      this.outputHashPath = outputHashPath;
      this.dxOptions = dxOptions;
      this.xzCompressionLevel = xzCompressionLevel;
      this.dxMaxHeapSize = dxMaxHeapSize;
    }

    /**
     * Read the previous run's hash from the filesystem.
     *
     * @return Previous hash if there was one; null otherwise.
     */
    @Nullable
    private String getPreviousInputsHash() {
      // Returning null will trigger the dx command to run again.
      return filesystem.readFirstLine(outputHashPath).orElse(null);
    }

    @VisibleForTesting
    String hashInputs() {
      Hasher hasher = Hashing.sha1().newHasher();
      for (Path src : srcs) {
        Preconditions.checkState(
            dexInputHashes.containsKey(src),
            "no hash key exists for path %s",
            src.toString());
        Sha1HashCode hash = Preconditions.checkNotNull(dexInputHashes.get(src));
        hash.update(hasher);
      }
      return hasher.hash().toString();
    }

    public boolean checkIsCached() {
      newInputsHash = hashInputs();

      if (!filesystem.exists(outputHashPath) ||
          !filesystem.exists(outputPath)) {
        return false;
      }

      // Verify input hashes.
      String currentInputsHash = getPreviousInputsHash();
      return newInputsHash.equals(currentInputsHash);
    }

    private ImmutableList<Step> buildInternal() {
      Preconditions.checkState(newInputsHash != null, "Must call checkIsCached first!");

      List<Step> steps = Lists.newArrayList();

      steps.add(
          createDxStepForDxPseudoRule(
              filesystem,
              srcs,
              outputPath,
              dxOptions,
              xzCompressionLevel,
              dxMaxHeapSize));
      steps.add(
          new WriteFileStep(filesystem, newInputsHash, outputHashPath, /* executable */ false));

      // Use a composite step to ensure that runDxSteps can still make use of
      // runStepsInParallelAndWait.  This is necessary to keep the DxStep and
      // WriteFileStep dependent in series.
      return ImmutableList.of(new CompositeStep(steps));
    }
  }

  /**
   * The step to produce the .dex file will be determined by the file extension of outputPath, much
   * as {@code dx} itself chooses whether to embed the dex inside a jar/zip based on the destination
   * file passed to it.  We also create a ".meta" file that contains information about the
   * compressed and uncompressed size of the dex; this information is useful later, in applications,
   * when unpacking.
   */
  static Step createDxStepForDxPseudoRule(
      ProjectFilesystem filesystem,
      Collection<Path> filesToDex,
      Path outputPath,
      EnumSet<Option> dxOptions,
      Optional<Integer> xzCompressionLevel,
      Optional<String> dxMaxHeapSize) {

    String output = outputPath.toString();
    List<Step> steps = Lists.newArrayList();

    if (DexStore.XZ.matchesPath(outputPath)) {
      Path tempDexJarOutput = Paths.get(output.replaceAll("\\.jar\\.xz$", ".tmp.jar"));
      steps.add(new DxStep(filesystem, tempDexJarOutput, filesToDex, dxOptions, dxMaxHeapSize));
      // We need to make sure classes.dex is STOREd in the .dex.jar file, otherwise .XZ
      // compression won't be effective.
      Path repackedJar = Paths.get(output.replaceAll("\\.xz$", ""));
      steps.add(
          new RepackZipEntriesStep(
              filesystem,
              tempDexJarOutput,
              repackedJar,
              ImmutableSet.of("classes.dex"),
              ZipCompressionLevel.MIN_COMPRESSION_LEVEL));
      steps.add(new RmStep(filesystem, tempDexJarOutput));
      steps.add(
          new DexJarAnalysisStep(
              filesystem,
              repackedJar,
              repackedJar.resolveSibling(
                  repackedJar.getFileName() + ".meta")));
      if (xzCompressionLevel.isPresent()) {
        steps.add(new XzStep(filesystem, repackedJar, xzCompressionLevel.get().intValue()));
      } else {
        steps.add(new XzStep(filesystem, repackedJar));
      }
    } else if (DexStore.XZS.matchesPath(outputPath)) {
      // Essentially the same logic as the XZ case above, except we compress later.
      // The differences in output file names make it worth separating into a different case.

      // Ensure classes.dex is stored.
      Path tempDexJarOutput = Paths.get(output.replaceAll("\\.jar\\.xzs\\.tmp~$", ".tmp.jar"));
      steps.add(new DxStep(filesystem, tempDexJarOutput, filesToDex, dxOptions, dxMaxHeapSize));
      steps.add(
          new RepackZipEntriesStep(
              filesystem,
              tempDexJarOutput,
              outputPath,
              ImmutableSet.of("classes.dex"),
              ZipCompressionLevel.MIN_COMPRESSION_LEVEL));
      steps.add(new RmStep(filesystem, tempDexJarOutput));

      // Write a .meta file.
      steps.add(
          new DexJarAnalysisStep(
              filesystem,
              outputPath,
              outputPath.resolveSibling(
                  outputPath.getFileName() + ".meta")));
    } else if (DexStore.JAR.matchesPath(outputPath) || DexStore.RAW.matchesPath(outputPath) ||
        output.endsWith("classes.dex")) {
      steps.add(new DxStep(filesystem, outputPath, filesToDex, dxOptions, dxMaxHeapSize));
      if (DexStore.JAR.matchesPath(outputPath)) {
        steps.add(
            new DexJarAnalysisStep(
                filesystem,
                outputPath,
                outputPath.resolveSibling(
                  outputPath.getFileName() + ".meta")));
      }
    } else {
      throw new IllegalArgumentException(String.format(
          "Suffix of %s does not have a corresponding DexStore type.",
          outputPath));
    }

    return new CompositeStep(steps);
  }
}
