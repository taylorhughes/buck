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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.BuckPaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.AbstractBuildRuleWithResolver;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUCK</code>:
 * <pre>
 * java_library(
 *   name = 'feed',
 *   srcs = [
 *     'FeedStoryRenderer.java',
 *   ],
 *   deps = [
 *     '//src/com/facebook/feed/model:model',
 *     '//third-party/java/guava:guava',
 *   ],
 * )
 * </pre>
 * Then this would compile {@code FeedStoryRenderer.java} against Guava and the classes generated
 * from the {@code //src/com/facebook/feed/model:model} rule.
 */
public class DefaultJavaLibrary extends AbstractBuildRuleWithResolver
    implements JavaLibrary, HasClasspathEntries, ExportDependencies,
    InitializableFromDisk<JavaLibrary.Data>, AndroidPackageable,
    SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey, JavaLibraryWithTests {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);
  private static final Path METADATA_DIR = Paths.get("META-INF");

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;
  @AddToRuleKey
  private final Optional<SourcePath> manifestFile;
  @AddToRuleKey
  private final Optional<String> mavenCoords;
  private final Optional<Path> outputJar;
  @AddToRuleKey
  private final Optional<SourcePath> proguardConfig;
  @AddToRuleKey
  private final ImmutableList<String> postprocessClassesCommands;
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  private final ImmutableSortedSet<BuildRule> providedDeps;
  // Some classes need to override this when enhancing deps (see AndroidLibrary).
  private final ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries;
  private final Supplier<ImmutableSet<SourcePath>>
      outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<SourcePath>>
      transitiveClasspathsSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  private final boolean trackClassUsage;
  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final JarArchiveDependencySupplier abiClasspath;
  private final ImmutableSortedSet<BuildRule> deps;
  @Nullable private Path depFileOutputPath;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final Optional<Path> generatedSourceFolder;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSet<Pattern> classesToRemoveFromJar;

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey
  private final CompileToJarStepFactory compileStepFactory;

  public static DefaultJavaLibraryBuilder builder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CompileToJarStepFactory compileStepFactory) {
    return new DefaultJavaLibraryBuilder(params, buildRuleResolver, compileStepFactory);
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  private static final SuggestBuildRules.JarResolver JAR_RESOLVER =
      classPath -> {
        ImmutableSet.Builder<String> topLevelSymbolsBuilder = ImmutableSet.builder();
        try {
          ClassLoader loader = URLClassLoader.newInstance(
              new URL[]{classPath.toUri().toURL()},
            /* parent */ null);

          // For every class contained in that jar, check to see if the package name
          // (e.g. com.facebook.foo), the simple name (e.g. ImmutableSet) or the name
          // (e.g com.google.common.collect.ImmutableSet) is one of the missing symbols.
          for (ClassPath.ClassInfo classInfo : ClassPath.from(loader).getTopLevelClasses()) {
            topLevelSymbolsBuilder.add(classInfo.getPackageName(),
                classInfo.getSimpleName(),
                classInfo.getName());
          }
        } catch (IOException e) {
          // Since this simply is a heuristic, return an empty set if we fail to load a jar.
          return topLevelSymbolsBuilder.build();
        }
        return topLevelSymbolsBuilder.build();
      };

  protected DefaultJavaLibrary(
      final BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> generatedSourceFolder,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      ImmutableSortedSet<SourcePath> abiInputs,
      boolean trackClassUsage,
      ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    this(
        params,
        resolver,
        ruleFinder,
        srcs,
        resources,
        generatedSourceFolder,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        trackClassUsage,
        new JarArchiveDependencySupplier(abiInputs),
        additionalClasspathEntries,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        mavenCoords,
        tests,
        classesToRemoveFromJar);
  }

  private DefaultJavaLibrary(
      BuildRuleParams params,
      final SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> generatedSourceFolder,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      boolean trackClassUsage,
      final JarArchiveDependencySupplier abiClasspath,
      ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    super(
        params.copyAppendingExtraDeps(() -> ruleFinder.filterBuildRuleInputs(abiClasspath.get())),
        resolver);
    this.ruleFinder = ruleFinder;
    this.compileStepFactory = compileStepFactory;

    // Exported deps are meant to be forwarded onto the CLASSPATH for dependents,
    // and so only make sense for java library types.
    for (BuildRule dep : exportedDeps) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            params.getBuildTarget() + ": exported dep " +
            dep.getBuildTarget() + " (" + dep.getType() + ") " +
            "must be a type of java library.");
      }
    }

    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.resources = ImmutableSortedSet.copyOf(resources);
    this.proguardConfig = proguardConfig;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.exportedDeps = exportedDeps;
    this.providedDeps = providedDeps;
    this.additionalClasspathEntries = additionalClasspathEntries;
    this.resourcesRoot = resourcesRoot;
    this.manifestFile = manifestFile;
    this.mavenCoords = mavenCoords;
    this.tests = tests;

    this.trackClassUsage = trackClassUsage;
    this.abiClasspath = abiClasspath;
    this.deps = params.getDeps();
    if (!srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget(), getProjectFilesystem()));
    } else {
      this.outputJar = Optional.empty();
    }

    this.outputClasspathEntriesSupplier =
        Suppliers.memoize(() -> JavaLibraryClasspathProvider.getOutputClasspathJars(
            DefaultJavaLibrary.this,
            sourcePathForOutputJar()));

    this.transitiveClasspathsSupplier =
        Suppliers.memoize(() -> JavaLibraryClasspathProvider.getClasspathsFromLibraries(
            getTransitiveClasspathDeps()));

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            () -> JavaLibraryClasspathProvider.getTransitiveClasspathDeps(
                DefaultJavaLibrary.this));

    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
    this.generatedSourceFolder = generatedSourceFolder;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
  }

  private Path getPathToAbiOutputDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "lib__%s__abi");
  }

  public static Path getOutputJarDirPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "lib__%s__output");
  }

  private Optional<SourcePath> sourcePathForOutputJar() {
    return outputJar.map(input -> new ExplicitBuildTargetSourcePath(getBuildTarget(), input));
  }

  static Path getOutputJarPath(BuildTarget target, ProjectFilesystem filesystem) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target, filesystem),
            target.getShortNameAndFlavorPostfix()));
  }

  static Path getUsedClassesFilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return getOutputJarDirPath(target, filesystem).resolve("used-classes.json");
  }

  /**
   * @return directory path relative to the project root where .class files will be generated.
   *     The return value does not end with a slash.
   */
  public static Path getClassesDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, target, "lib__%s__classes");
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJavaSrcs() {
    return srcs;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return srcs;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getResources() {
    return resources;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(Sets.union(getDeclaredDeps(), exportedDeps));
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    return transitiveClasspathsSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    ImmutableSet.Builder<SourcePath> builder = ImmutableSet.builder();

    // Add any exported deps.
    for (BuildRule exported : getExportedDeps()) {
      if (exported instanceof JavaLibrary) {
        builder.addAll(((JavaLibrary) exported).getImmediateClasspaths());
      }
    }

    // Add ourselves to the classpath if there's a jar to be built.
    Optional<SourcePath> sourcePathForOutputJar = sourcePathForOutputJar();
    if (sourcePathForOutputJar.isPresent()) {
      builder.add(sourcePathForOutputJar.get());
    }

    return builder.build();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    return outputClasspathEntriesSupplier.get();
  }

  @Override
  public Optional<Path> getGeneratedSourcePath() {
    return generatedSourceFolder;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return exportedDeps;
  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under {@link BuckPaths#getScratchDir()}.
   */
  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    FluentIterable<JavaLibrary> declaredClasspathDeps =
        JavaLibraryClasspathProvider.getJavaLibraryDeps(getDepsForTransitiveClasspathEntries());


    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    BuildTarget target = getBuildTarget();
    Path outputDirectory = getClassesDir(target, getProjectFilesystem());
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirectory));

    SuggestBuildRules suggestBuildRule =
        DefaultSuggestBuildRules.createSuggestBuildFunction(
            JAR_RESOLVER,
            context.getSourcePathResolver(),
            declaredClasspathDeps.toSet(),
            ImmutableSet.<JavaLibrary>builder()
                .addAll(getTransitiveClasspathDeps())
                .add(this)
                .build(),
            context.getActionGraph().getNodes());

    // We don't want to add these to the declared or transitive deps, since they're only used at
    // compile time.
    Collection<Path> provided = JavaLibraryClasspathProvider.getJavaLibraryDeps(providedDeps)
        .transformAndConcat(JavaLibrary::getOutputClasspaths)
        .filter(Objects::nonNull)
        .transform(context.getSourcePathResolver()::getAbsolutePath)
        .toSet();

    Iterable<Path> declaredClasspaths = declaredClasspathDeps
        .transformAndConcat(JavaLibrary::getOutputClasspaths)
        .transform(context.getSourcePathResolver()::getAbsolutePath);
    // Only override the bootclasspath if this rule is supposed to compile Android code.
    ImmutableSortedSet<Path> declared = ImmutableSortedSet.<Path>naturalOrder()
        .addAll(declaredClasspaths)
        .addAll(additionalClasspathEntries.stream()
            .map(e -> e.isLeft() ?
                context.getSourcePathResolver().getAbsolutePath(e.getLeft())
                : checkIsAbsolute(e.getRight()))
            .collect(MoreCollectors.toImmutableSet()))
        .addAll(provided)
        .build();


    // Make sure that this directory exists because ABI information will be written here.
    Step mkdir = new MakeCleanDirectoryStep(getProjectFilesystem(), getPathToAbiOutputDir());
    steps.add(mkdir);

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(resourcesRoot.get(), finder);
    }

    steps.add(
        new CopyResourcesStep(
            getProjectFilesystem(),
            context.getSourcePathResolver(),
            ruleFinder,
            target,
            resources,
            outputDirectory,
            finder));

    steps.add(
        new MakeCleanDirectoryStep(
            getProjectFilesystem(),
            getOutputJarDirPath(target, getProjectFilesystem())));

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!getJavaSrcs().isEmpty()) {
      ClassUsageFileWriter usedClassesFileWriter;
      if (trackClassUsage) {
        final Path usedClassesFilePath =
            getUsedClassesFilePath(getBuildTarget(), getProjectFilesystem());
        depFileOutputPath = getProjectFilesystem().getPathForRelativePath(usedClassesFilePath);
        usedClassesFileWriter = new DefaultClassUsageFileWriter(usedClassesFilePath);

        buildableContext.recordArtifact(usedClassesFilePath);
      } else {
        usedClassesFileWriter = NoOpClassUsageFileWriter.instance();
      }

      // This adds the javac command, along with any supporting commands.
      Path pathToSrcsList =
          BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__srcs");
      steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

      Path scratchDir =
          BuildTargets.getGenPath(getProjectFilesystem(), target, "lib__%s____working_directory");
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));
      Optional<Path> workingDirectory = Optional.of(scratchDir);

      ImmutableSortedSet<Path> javaSrcs = getJavaSrcs().stream()
          .map(context.getSourcePathResolver()::getRelativePath)
          .collect(MoreCollectors.toImmutableSortedSet());

      compileStepFactory.createCompileToJarStep(
          context,
          javaSrcs,
          target,
          context.getSourcePathResolver(),
          ruleFinder,
          getProjectFilesystem(),
          declared,
          outputDirectory,
          workingDirectory,
          pathToSrcsList,
          Optional.of(suggestBuildRule),
          postprocessClassesCommands,
          ImmutableSortedSet.of(outputDirectory),
          /* mainClass */ Optional.empty(),
          manifestFile.map(context.getSourcePathResolver()::getAbsolutePath),
          outputJar.get(),
          usedClassesFileWriter,
          /* output params */
          steps,
          buildableContext,
          classesToRemoveFromJar);
    }

    if (outputJar.isPresent()) {
      Path output = outputJar.get();

      // No source files, only resources
      if (getJavaSrcs().isEmpty()) {
        steps.add(
            new JarDirectoryStep(
                getProjectFilesystem(),
                output,
                ImmutableSortedSet.of(outputDirectory),
                /* mainClass */ null,
                manifestFile.map(context.getSourcePathResolver()::getAbsolutePath).orElse(null),
                true,
                classesToRemoveFromJar));
      }
      buildableContext.recordArtifact(output);
    }

    JavaLibraryRules.addAccumulateClassNamesStep(
        this,
        buildableContext,
        context.getSourcePathResolver(),
        steps);

    return steps.build();
  }

  private Path checkIsAbsolute(Path path) {
    Preconditions.checkArgument(path.isAbsolute(), "Need absolute path but got %s", path);
    return path;
  }

  /**
   * Instructs this rule to report the ABI it has on disk as its current ABI.
   */
  @Override
  public JavaLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    return JavaLibraryRules.initializeFromDisk(
        getBuildTarget(),
        getProjectFilesystem(),
        onDiskBuildInfo);
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public final Optional<BuildTarget> getAbiJar() {
    return outputJar.isPresent() ? JavaLibrary.super.getAbiJar() : Optional.empty();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    return outputJar.map(o -> new ExplicitBuildTargetSourcePath(getBuildTarget(), o)).orElse(null);
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(ImmutableSortedSet.copyOf(
            Sets.difference(
                Sets.union(getDeclaredDeps(), exportedDeps),
                providedDeps)));
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (outputJar.isPresent()) {
      collector.addClasspathEntry(
          this,
          new ExplicitBuildTargetSourcePath(getBuildTarget(), outputJar.get()));
    }
    if (proguardConfig.isPresent()) {
      collector.addProguardConfig(getBuildTarget(), proguardConfig.get());
    }
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return !getJavaSrcs().isEmpty() && trackClassUsage;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    // note, sorted set is intentionally converted to a hash set to achieve constant time look-up
    return ImmutableSet.copyOf(abiClasspath.getArchiveMembers(getResolver()))::contains;
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    // Annotation processors might enumerate all files under a certain path and then generate
    // code based on that list (without actually reading the files), making the list of files
    // itself a used dependency that must be part of the dependency-based key. We don't
    // currently have the instrumentation to detect such enumeration perfectly, but annotation
    // processors are most commonly looking for files under META-INF, so as a stopgap we add
    // the listing of META-INF to the rule key.
    return (SourcePath path) ->
        (path instanceof ArchiveMemberSourcePath) &&
            getResolver().getRelativeArchiveMemberPath(path)
                .getMemberPath().startsWith(METADATA_DIR);
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context)
      throws IOException {
    Preconditions.checkState(useDependencyFileRuleKeys());
    return DefaultClassUsageFileReader.loadFromFile(
        context.getSourcePathResolver(),
        getProjectFilesystem(),
        Preconditions.checkNotNull(depFileOutputPath),
        deps);
  }
}
