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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.HashingDeterministicJarWriter;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

public class DefaultJavaLibraryTest {
  private static final String ANNOTATION_SCENARIO_TARGET =
      "//android/java/src/com/facebook:fb";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();
  private String annotationScenarioGenPath;
  BuildRuleResolver ruleResolver;

  @Before
  public void stubOutBuildContext() {
    ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());

    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    StepRunner stepRunner = createNiceMock(StepRunner.class);
    JavaPackageFinder packageFinder = createNiceMock(JavaPackageFinder.class);
    replay(packageFinder, stepRunner);

    annotationScenarioGenPath =
        filesystem
            .resolve(filesystem.getBuckPaths().getAnnotationDir())
            .resolve("android/java/src/com/facebook/__fb_gen__")
            .toAbsolutePath()
            .toString();
  }

  /** Make sure that when isAndroidLibrary is true, that the Android bootclasspath is used. */
  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testBuildInternalWithAndroidBootclasspath() throws Exception {
    String folder = "android/java/src/com/facebook";
    tmp.newFolder(folder.split("/"));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//" + folder + ":fb");

    Path src = Paths.get(folder, "Main.java");
    tmp.newFile(src.toString());

    BuildRule libraryRule = AndroidLibraryBuilder
        .createBuilder(buildTarget)
        .addSrc(src)
        .build(ruleResolver);
    DefaultJavaLibrary javaLibrary = (DefaultJavaLibrary) libraryRule;

    String bootclasspath = "effects.jar" + File.pathSeparator + "maps.jar" +
        File.pathSeparator + "usb.jar" + File.pathSeparator;
    BuildContext context = createBuildContext(libraryRule, bootclasspath);

    List<Step> steps = javaLibrary.getBuildSteps(context, new FakeBuildableContext());

    // Find the JavacStep and verify its bootclasspath.
    Step step = Iterables.find(steps, command -> command instanceof JavacStep);
    assertNotNull("Expected a JavacStep in the steplist.", step);
    JavacStep javac = (JavacStep) step;
    assertEquals("Should compile Main.java rather than generated R.java.",
        ImmutableSet.of(src),
        javac.getSrcs());
  }

  @Test
  public void testJavaLibaryThrowsIfResourceIsDirectory() throws Exception {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem() {
      @Override
      public boolean isDirectory(Path path, LinkOption... linkOptionsk) {
        return true;
      }
    };

    try {
      JavaLibraryBuilder
          .createBuilder(BuildTargetFactory.newInstance("//library:code"))
          .addResource(new FakeSourcePath("library"))
          .build(ruleResolver, filesystem);
      fail("An exception should have been thrown because a directory was passed as a resource.");
    } catch (HumanReadableException e) {
      assertTrue(e.getHumanReadableErrorMessage().contains("a directory is not a valid input"));
    }
  }

  /**
   * Verify adding an annotation processor java binary.
   */
  @Test
  public void testAddAnnotationProcessorJavaBinary() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validJavaBinary);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor"));
    assertEquals(
        "Expected '-s " + annotationScenarioGenPath + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(annotationScenarioGenPath));

    for (String parameter : parameters) {
      assertThat(
          "Expected no custom annotation options.",
          parameter.startsWith("-A"),
          is(false));
    }
  }

  /**
   * Verify adding an annotation processor prebuilt jar.
   */
  @Test
  public void testAddAnnotationProcessorPrebuiltJar() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validPrebuiltJar);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
  }

  /**
   * Verify adding an annotation processor java library.
   */
  @Test
  public void testAddAnnotationProcessorJavaLibrary() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validPrebuiltJar);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
  }

  /**
   * Verify adding multiple annotation processors.
   */
  @Test
  public void testAddAnnotationProcessorJar() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validPrebuiltJar);
    scenario.addAnnotationProcessorTarget(validJavaBinary);
    scenario.addAnnotationProcessorTarget(validJavaLibrary);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
  }

  @Test
  public void testGetClasspathEntriesMap() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    TargetNode<?, ?> libraryOne = JavaLibraryBuilder.createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build();

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    TargetNode<?, ?> libraryTwo = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOne.getBuildTarget())
        .build();

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    TargetNode<?, ?> parent = JavaLibraryBuilder
        .createBuilder(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(libraryTwo.getBuildTarget())
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryOne, libraryTwo, parent);
    ruleResolver = new BuildRuleResolver(
        targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    JavaLibrary libraryOneRule = (JavaLibrary) ruleResolver.requireRule(libraryOneTarget);
    JavaLibrary parentRule = (JavaLibrary) ruleResolver.requireRule(parentTarget);

    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    Path root = libraryOneRule.getProjectFilesystem().getRootPath();
    assertEquals(
        ImmutableSet.of(
            root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryTwoTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(parentTarget, filesystem))),
        resolve(parentRule.getTransitiveClasspaths(), pathResolver));
  }

  @Test
  public void testGetClasspathDeps() throws Exception {
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    TargetNode<?, ?> libraryOne = JavaLibraryBuilder.createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build();

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    TargetNode<?, ?> libraryTwo = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOne.getBuildTarget())
        .build();

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    TargetNode<?, ?> parent = JavaLibraryBuilder
        .createBuilder(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(libraryTwo.getBuildTarget())
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryOne, libraryTwo, parent);
    ruleResolver = new BuildRuleResolver(
        targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule libraryOneRule = ruleResolver.requireRule(libraryOneTarget);
    BuildRule libraryTwoRule = ruleResolver.requireRule(libraryTwoTarget);
    BuildRule parentRule = ruleResolver.requireRule(parentTarget);

    assertThat(
        ((HasClasspathEntries) parentRule).getTransitiveClasspathDeps(),
        equalTo(
            ImmutableSet.of(
                getJavaLibrary(libraryOneRule),
                getJavaLibrary(libraryTwoRule),
                getJavaLibrary(parentRule))));
  }

  @Test
  public void testClasspathForJavacCommand() throws Exception {
    // libraryOne responds like an ordinary prebuilt_jar with no dependencies. We have to use a
    // FakeJavaLibraryRule so that we can override the behavior of getAbiKey().
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    SourcePathResolver resolver = new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    FakeJavaLibrary libraryOne = new FakeJavaLibrary(libraryOneTarget, resolver) {

      @Override
      public Optional<BuildTarget> getAbiJar() {
        return Optional.empty();
      }

      @Override
      public ImmutableSet<SourcePath> getOutputClasspaths() {
        return ImmutableSet.of(new FakeSourcePath("java/src/com/libone/bar.jar"));
      }

      @Override
      public ImmutableSet<SourcePath> getTransitiveClasspaths() {
        return ImmutableSet.of();
      }

      @Override
      public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
        return ImmutableSet.of();
      }
    };
    ruleResolver.addToIndex(libraryOne);

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    BuildRule libraryTwo = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOne.getBuildTarget())
        .build(ruleResolver);

    List<Step> steps = libraryTwo.getBuildSteps(
        FakeBuildContext.withSourcePathResolver(resolver), new FakeBuildableContext());

    ImmutableList<JavacStep> javacSteps = FluentIterable
        .from(steps)
        .filter(JavacStep.class)
        .toList();
    assertEquals("There should be only one javac step.", 1, javacSteps.size());
    JavacStep javacStep = javacSteps.get(0);
    assertEquals(
        "The classpath for the javac step to compile //:libtwo should contain only bar.jar.",
        ImmutableSet.of(libraryOne.getProjectFilesystem().resolve("java/src/com/libone/bar.jar")),
        javacStep.getClasspathEntries());
  }

  /**
   * Verify adding an annotation processor java binary with options.
   */
  @Test
  public void testAddAnnotationProcessorWithOptions() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validJavaBinary);

    scenario.getAnnotationProcessingParamsBuilder().addAllProcessors(
        ImmutableList.of("MyProcessor"));
    scenario.getAnnotationProcessingParamsBuilder().addParameter("MyParameter");
    scenario.getAnnotationProcessingParamsBuilder().addParameter("MyKey=MyValue");
    scenario.getAnnotationProcessingParamsBuilder().setProcessOnly(true);

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
    MoreAsserts.assertContainsOne(parameters, "-proc:only");

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor"));
    assertEquals(
        "Expected '-s " + annotationScenarioGenPath + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(annotationScenarioGenPath));

    MoreAsserts.assertContainsOne(parameters, "-AMyParameter");
    MoreAsserts.assertContainsOne(parameters, "-AMyKey=MyValue");
  }

  private void assertHasProcessor(List<String> params, String processor) {
    int index = params.indexOf("-processor");
    if (index >= params.size()) {
      fail(String.format("No processor argument found in %s.", params));
    }

    Set<String> processors = ImmutableSet.copyOf(Splitter.on(',').split(params.get(index + 1)));
    if (!processors.contains(processor)) {
      fail(String.format("Annotation processor %s not found in %s.", processor, params));
    }
  }

  @Test
  public void testExportedDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget nonIncludedTarget = BuildTargetFactory.newInstance("//:not_included");
    TargetNode<?, ?> notIncludedNode = JavaLibraryBuilder
        .createBuilder(nonIncludedTarget)
        .addSrc(Paths.get("java/src/com/not_included/Raz.java"))
        .build();

    BuildTarget includedTarget = BuildTargetFactory.newInstance("//:included");
    TargetNode<?, ?> includedNode = JavaLibraryBuilder
        .createBuilder(includedTarget)
        .addSrc(Paths.get("java/src/com/included/Rofl.java"))
        .build();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    TargetNode<?, ?> libraryOneNode = JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addDep(notIncludedNode.getBuildTarget())
        .addDep(includedNode.getBuildTarget())
        .addExportedDep(includedNode.getBuildTarget())
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build();

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    TargetNode<?, ?> libraryTwoNode = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOneNode.getBuildTarget())
        .addExportedDep(libraryOneNode.getBuildTarget())
        .build();

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    TargetNode<?, ?> parentNode = JavaLibraryBuilder
        .createBuilder(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(libraryTwoNode.getBuildTarget())
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        notIncludedNode,
        includedNode,
        libraryOneNode,
        libraryTwoNode,
        parentNode);
    ruleResolver = new BuildRuleResolver(
        targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    BuildRule notIncluded = ruleResolver.requireRule(notIncludedNode.getBuildTarget());
    BuildRule included = ruleResolver.requireRule(includedNode.getBuildTarget());
    BuildRule libraryOne = ruleResolver.requireRule(libraryOneTarget);
    BuildRule libraryTwo = ruleResolver.requireRule(libraryTwoTarget);
    BuildRule parent = ruleResolver.requireRule(parentTarget);

    Path root = parent.getProjectFilesystem().getRootPath();
    assertEquals(
        "A java_library that depends on //:libone should include only libone.jar in its " +
            "classpath when compiling itself.",
        ImmutableSet.of(
            root.resolve(DefaultJavaLibrary.getOutputJarPath(nonIncludedTarget, filesystem))),
        resolve(getJavaLibrary(notIncluded).getOutputClasspaths(), pathResolver));

    assertEquals(
        ImmutableSet.of(
            root.resolve(DefaultJavaLibrary.getOutputJarPath(includedTarget, filesystem))),
        resolve(getJavaLibrary(included).getOutputClasspaths(), pathResolver));

    assertEquals(
        ImmutableSet.of(
            root.resolve(DefaultJavaLibrary.getOutputJarPath(includedTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(includedTarget, filesystem))),
        resolve(getJavaLibrary(libraryOne).getOutputClasspaths(), pathResolver));

    assertEquals(
        "//:libtwo exports its deps, so a java_library that depends on //:libtwo should include " +
            "both libone.jar and libtwo.jar in its classpath when compiling itself.",
        ImmutableSet.of(
            root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(includedTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryTwoTarget, filesystem)),
            root.resolve(DefaultJavaLibrary.getOutputJarPath(includedTarget, filesystem))),
        resolve(getJavaLibrary(libraryTwo).getOutputClasspaths(), pathResolver));

    assertEquals(
        "A java_binary that depends on //:parent should include libone.jar, libtwo.jar and " +
            "parent.jar.",
        ImmutableSet.<Path>builder()
            .add(
                root.resolve(DefaultJavaLibrary.getOutputJarPath(includedTarget, filesystem)),
                root.resolve(DefaultJavaLibrary.getOutputJarPath(nonIncludedTarget, filesystem)),
                root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryOneTarget, filesystem)),
                root.resolve(DefaultJavaLibrary.getOutputJarPath(libraryTwoTarget, filesystem)),
                root.resolve(DefaultJavaLibrary.getOutputJarPath(parentTarget, filesystem)))
            .build(),
        resolve(getJavaLibrary(parent).getTransitiveClasspaths(), pathResolver));

    assertThat(
        getJavaLibrary(parent).getTransitiveClasspathDeps(),
        equalTo(
            ImmutableSet.<JavaLibrary>builder()
                .add(getJavaLibrary(included))
                .add(getJavaLibrary(notIncluded))
                .add(getJavaLibrary(libraryOne))
                .add(getJavaLibrary(libraryTwo))
                .add(getJavaLibrary(parent))
                .build()));

    assertEquals(
        "A java_library that depends on //:parent should include only parent.jar in its " +
            "-classpath when compiling itself.",
        ImmutableSet.of(
            root.resolve(DefaultJavaLibrary.getOutputJarPath(parentTarget, filesystem))),
        resolve(getJavaLibrary(parent).getOutputClasspaths(), pathResolver));
  }

  /**
   * Tests that an error is thrown when non-java library rules are listed in the exported deps
   * parameter.
   */
  @Test
  public void testExportedDepsShouldOnlyContainJavaLibraryRules() throws Exception {
    BuildTarget genruleBuildTarget = BuildTargetFactory.newInstance("//generated:stuff");
    BuildRule genrule = GenruleBuilder
        .newGenruleBuilder(genruleBuildTarget)
        .setBash("echo 'aha' > $OUT")
        .setOut("stuff.txt")
        .build(ruleResolver);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    try {
      createDefaultJavaLibraryRuleWithAbiKey(
          buildTarget,
          /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
          /* deps */ ImmutableSortedSet.of(),
          /* exportedDeps */ ImmutableSortedSet.of(genrule),
          /* spoolMode */ Optional.empty(),
          /* postprocessClassesCommands */ ImmutableList.of());
      fail("A non-java library listed as exported dep should have thrown.");
    } catch (HumanReadableException e) {
      String expected =
          buildTarget + ": exported dep " +
          genruleBuildTarget + " (" + genrule.getType() + ") " +
          "must be a type of java library.";
      assertEquals(expected, e.getMessage());
    }

  }

  @Test
  public void testStepsPresenceForForDirectJarSpooling() throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    BuildRule javaLibraryBuildRule = createDefaultJavaLibraryRuleWithAbiKey(
        buildTarget,
        /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.of(),
        /* exportedDeps */ ImmutableSortedSet.of(),
        Optional.of(AbstractJavacOptions.SpoolMode.DIRECT_TO_JAR),
        /* postprocessClassesCommands */ ImmutableList.of());

    BuildContext buildContext = createBuildContext(javaLibraryBuildRule, /* bootclasspath */ null);

    ImmutableList<Step> steps =
        javaLibraryBuildRule.getBuildSteps(buildContext, new FakeBuildableContext());

    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JavacDirectToJarStep.class)));
    assertThat(steps, Matchers.not(Matchers.hasItem(Matchers.instanceOf(JavacStep.class))));
    assertThat(
        steps,
        Matchers.not(Matchers.hasItem(Matchers.instanceOf(JarDirectoryStep.class))));
  }

  @Test
  public void testJavacDirectToJarStepIsNotPresentWhenPostprocessClassesCommandsPresent()
      throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    BuildRule javaLibraryBuildRule = createDefaultJavaLibraryRuleWithAbiKey(
        buildTarget,
        /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.of(),
        /* exportedDeps */ ImmutableSortedSet.of(),
        Optional.of(AbstractJavacOptions.SpoolMode.DIRECT_TO_JAR),
        /* postprocessClassesCommands */ ImmutableList.of("process_class_files.py"));

    BuildContext buildContext = createBuildContext(javaLibraryBuildRule, /* bootclasspath */ null);

    ImmutableList<Step> steps =
        javaLibraryBuildRule.getBuildSteps(buildContext, new FakeBuildableContext());

    assertThat(
        steps,
        Matchers.not(Matchers.hasItem(Matchers.instanceOf(JavacDirectToJarStep.class))));
    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JavacStep.class)));
    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JarDirectoryStep.class)));
  }

  @Test
  public void testStepsPresenceForIntermediateOutputToDiskSpooling()
      throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    BuildRule javaLibraryBuildRule = createDefaultJavaLibraryRuleWithAbiKey(
        buildTarget,
        /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.of(),
        /* exportedDeps */ ImmutableSortedSet.of(),
        Optional.of(AbstractJavacOptions.SpoolMode.INTERMEDIATE_TO_DISK),
        /* postprocessClassesCommands */ ImmutableList.of());

    BuildContext buildContext = createBuildContext(javaLibraryBuildRule, /* bootclasspath */ null);

    ImmutableList<Step> steps =
        javaLibraryBuildRule.getBuildSteps(buildContext, new FakeBuildableContext());

    assertThat(
        steps,
        Matchers.not(Matchers.hasItem(Matchers.instanceOf(JavacDirectToJarStep.class))));
    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JavacStep.class)));
    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JarDirectoryStep.class)));
  }

  /**
   * Tests that input-based rule keys work properly with generated sources.
   */
  @Test
  public void testInputBasedRuleKeySourceChange() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    // Setup a Java library consuming a source generated by a genrule and grab its rule key.
    BuildRule genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something")
            .build(ruleResolver, filesystem);
    filesystem.writeContentsToPath(
        "class Test {}",
        pathResolver.getRelativePath(genSrc.getSourcePathToOutput()));
    JavaLibrary library =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(genSrc.getSourcePathToOutput())
            .build(ruleResolver, filesystem);
    FileHashCache originalHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    InputBasedRuleKeyFactory factory =
        new InputBasedRuleKeyFactory(
            0,
            originalHashCache,
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the genrule such that its rule key changes, but it's output stays the same (since
    // we don't change it).  This should *not* affect the input-based rule key of the consuming
    // java library, since it only cares about the contents of the source.
    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something else")
            .build(ruleResolver, filesystem);
    library =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(genSrc.getSourcePathToOutput())
            .build(ruleResolver, filesystem);
    FileHashCache unaffectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            unaffectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually modify the source, which should make the input-based rule key change.
    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something else")
            .build(ruleResolver, filesystem);
    filesystem.writeContentsToPath(
        "class Test2 {}",
        pathResolver.getRelativePath(genSrc.getSourcePathToOutput()));
    library =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(genSrc.getSourcePathToOutput())
            .build(ruleResolver, filesystem);
    FileHashCache affectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            affectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with simple Java library deps.
   */
  @Test
  public void testInputBasedRuleKeyWithJavaLibraryDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library which builds against another Java library dep.
    TargetNode<JavaLibraryDescription.Arg, ?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"), filesystem)
            .addSrc(Paths.get("Source.java"))
            .build();
    TargetNode<?, ?> libraryNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"), filesystem)
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, libraryNode);
    ruleResolver = new BuildRuleResolver(
        targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    JavaLibrary dep = (JavaLibrary) ruleResolver.requireRule(depNode.getBuildTarget());
    JavaLibrary library = (JavaLibrary) ruleResolver.requireRule(libraryNode.getBuildTarget());

    filesystem.writeContentsToPath(
        "JAR contents",
        pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            ruleResolver.requireRule(dep.getAbiJar().get()).getSourcePathToOutput()),
        "Source.class",
        "ABI JAR contents");
    FileHashCache originalHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    InputBasedRuleKeyFactory factory =
        new InputBasedRuleKeyFactory(
            0,
            originalHashCache,
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the Java library dependency such that its rule key changes, and change its JAR
    // contents, but keep its ABI JAR the same.  This should *not* affect the input-based rule key
    // of the consuming java library, since it only cares about the contents of the ABI JAR.
    depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("Source.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build();
    targetGraph = TargetGraphFactory.newInstance(depNode, libraryNode);
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);

    dep = (JavaLibrary) ruleResolver.requireRule(depNode.getBuildTarget());
    library = (JavaLibrary) ruleResolver.requireRule(libraryNode.getBuildTarget());

    filesystem.writeContentsToPath(
        "different JAR contents",
        pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    FileHashCache unaffectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            unaffectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually change the Java library dependency's ABI JAR.  This *should* affect the
    // input-based rule key of the consuming java library.
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);

    dep = (JavaLibrary) ruleResolver.requireRule(depNode.getBuildTarget());
    library = (JavaLibrary) ruleResolver.requireRule(libraryNode.getBuildTarget());

    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            ruleResolver.requireRule(dep.getAbiJar().get()).getSourcePathToOutput()),
        "Source.class",
        "changed ABI JAR contents");
    FileHashCache affectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            affectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with a Java library dep exported by a
   * first-order dep.
   */
  @Test
  public void testInputBasedRuleKeyWithExportedDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    // Setup a Java library which builds against another Java library dep exporting another Java
    // library dep.

    TargetNode<JavaLibraryDescription.Arg, ?> exportedDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .build();
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"), filesystem)
            .addExportedDep(exportedDepNode.getBuildTarget())
            .build();
    TargetNode<?, ?> libraryNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"), filesystem)
            .addDep(depNode.getBuildTarget())
            .build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(exportedDepNode, depNode, libraryNode);
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    JavaLibrary exportedDep =
        (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:edep"));
    JavaLibrary library =
        (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "JAR contents",
        pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            ruleResolver.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "ABI JAR contents");

    FileHashCache originalHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    InputBasedRuleKeyFactory factory =
        new InputBasedRuleKeyFactory(
            0,
            originalHashCache,
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the exported Java library dependency such that its rule key changes, and change
    // its JAR contents, but keep its ABI JAR the same.  This should *not* affect the input-based
    // rule key of the consuming java library, since it only cares about the contents of the ABI
    // JAR.
    exportedDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build();
    targetGraph = TargetGraphFactory.newInstance(exportedDepNode, depNode, libraryNode);
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);

    exportedDep = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "different JAR contents",
        pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    FileHashCache unaffectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            unaffectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually change the exproted Java library dependency's ABI JAR.  This *should* affect
    // the input-based rule key of the consuming java library.
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);

    exportedDep = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:lib"));

    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            ruleResolver.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "changed ABI JAR contents");
    FileHashCache affectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            affectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with a Java library dep exported through
   * multiple Java library dependencies.
   */
  @Test
  public void testInputBasedRuleKeyWithRecursiveExportedDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library which builds against another Java library dep exporting another Java
    // library dep.
    TargetNode<JavaLibraryDescription.Arg, ?> exportedDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .build();
    TargetNode<?, ?> dep2Node =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep2"), filesystem)
            .addExportedDep(exportedDepNode.getBuildTarget())
            .build();
    TargetNode<?, ?> dep1Node =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep1"), filesystem)
            .addExportedDep(dep2Node.getBuildTarget())
            .build();
    TargetNode<?, ?> libraryNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"), filesystem)
            .addDep(dep1Node.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(exportedDepNode, dep2Node, dep1Node, libraryNode);
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    JavaLibrary exportedDep =
        (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:edep"));
    JavaLibrary library =
        (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "JAR contents",
        pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            ruleResolver.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "ABI JAR contents");
    FileHashCache originalHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    InputBasedRuleKeyFactory factory =
        new InputBasedRuleKeyFactory(
            0,
            originalHashCache,
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the exported Java library dependency such that its rule key changes, and change
    // its JAR contents, but keep its ABI JAR the same.  This should *not* affect the input-based
    // rule key of the consuming java library, since it only cares about the contents of the ABI
    // JAR.
    exportedDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build();
    targetGraph = TargetGraphFactory.newInstance(exportedDepNode, dep2Node, dep1Node, libraryNode);
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);

    exportedDep = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "different JAR contents",
        pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    FileHashCache unaffectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            unaffectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually change the exproted Java library dependency's ABI JAR.  This *should* affect
    // the input-based rule key of the consuming java library.
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);

    exportedDep = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) ruleResolver.requireRule(BuildTargetFactory.newInstance("//:lib"));

    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            ruleResolver.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "changed ABI JAR contents");
    FileHashCache affectedHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    factory =
        new InputBasedRuleKeyFactory(
            0,
            affectedHashCache,
            pathResolver,
            ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  private BuildRule createDefaultJavaLibraryRuleWithAbiKey(
      BuildTarget buildTarget,
      ImmutableSet<String> srcs,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSortedSet<BuildRule> exportedDeps,
      Optional<AbstractJavacOptions.SpoolMode> spoolMode,
      ImmutableList<String> postprocessClassesCommands)
      throws NoSuchBuildTargetException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ImmutableSortedSet<SourcePath> srcsAsPaths = FluentIterable.from(srcs)
        .transform(Paths::get)
        .transform(p -> (SourcePath) new PathSourcePath(projectFilesystem, p))
        .toSortedSet(Ordering.natural());

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
        .build();

    JavacOptions javacOptions = spoolMode.isPresent()
        ? JavacOptions.builder(DEFAULT_JAVAC_OPTIONS).setSpoolMode(spoolMode.get()).build()
        : DEFAULT_JAVAC_OPTIONS;

    JavacToJarStepFactory compileStepFactory = new JavacToJarStepFactory(
        javacOptions,
        JavacOptionsAmender.IDENTITY);
    DefaultJavaLibrary defaultJavaLibrary = DefaultJavaLibrary
        .builder(buildRuleParams, ruleResolver, compileStepFactory)
        .setSrcs(srcsAsPaths)
        .setGeneratedSourceFolder(javacOptions.getGeneratedSourceFolderName())
        .setPostprocessClassesCommands(postprocessClassesCommands)
        .setExportedDepRules(exportedDeps)
        .setTrackClassUsage(javacOptions.trackClassUsage())
        .build();

    ruleResolver.addToIndex(defaultJavaLibrary);
    return defaultJavaLibrary;
  }

  @Test
  public void testRuleKeyIsOrderInsensitiveForSourcesAndResources() throws Exception {
    // Note that these filenames were deliberately chosen to have identical hashes to maximize
    // the chance of order-sensitivity when being inserted into a HashMap.  Just using
    // {foo,bar}.{java,txt} resulted in a passing test even for the old broken code.

    ProjectFilesystem filesystem = new AllExistingProjectFilesystem() {
      @Override
      public boolean isDirectory(Path path, LinkOption... linkOptionsk) {
        return false;
      }
    };
    BuildRuleResolver resolver1 =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder1 = new SourcePathRuleFinder(resolver1);
    SourcePathResolver pathResolver1 = new SourcePathResolver(ruleFinder1);
    DefaultJavaLibrary rule1 = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//lib:lib"))
        .addSrc(Paths.get("agifhbkjdec.java"))
        .addSrc(Paths.get("bdeafhkgcji.java"))
        .addSrc(Paths.get("bdehgaifjkc.java"))
        .addSrc(Paths.get("cfiabkjehgd.java"))
        .addResource(new FakeSourcePath("becgkaifhjd.txt"))
        .addResource(new FakeSourcePath("bkhajdifcge.txt"))
        .addResource(new FakeSourcePath("cabfghjekid.txt"))
        .addResource(new FakeSourcePath("chkdbafijge.txt"))
        .build(resolver1, filesystem);

    BuildRuleResolver resolver2 =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder2 = new SourcePathRuleFinder(resolver2);
    SourcePathResolver pathResolver2 = new SourcePathResolver(ruleFinder2);
    DefaultJavaLibrary rule2 = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//lib:lib"))
        .addSrc(Paths.get("cfiabkjehgd.java"))
        .addSrc(Paths.get("bdehgaifjkc.java"))
        .addSrc(Paths.get("bdeafhkgcji.java"))
        .addSrc(Paths.get("agifhbkjdec.java"))
        .addResource(new FakeSourcePath("chkdbafijge.txt"))
        .addResource(new FakeSourcePath("cabfghjekid.txt"))
        .addResource(new FakeSourcePath("bkhajdifcge.txt"))
        .addResource(new FakeSourcePath("becgkaifhjd.txt"))
        .build(resolver2, filesystem);

    ImmutableMap.Builder<String, String> fileHashes = ImmutableMap.builder();
    for (String filename : ImmutableList.of(
        "agifhbkjdec.java", "bdeafhkgcji.java", "bdehgaifjkc.java", "cfiabkjehgd.java",
        "becgkaifhjd.txt", "bkhajdifcge.txt", "cabfghjekid.txt", "chkdbafijge.txt")) {
      fileHashes.put(filename, Hashing.sha1().hashString(filename, Charsets.UTF_8).toString());
    }
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            FakeFileHashCache.createFromStrings(fileHashes.build()),
            pathResolver1,
            ruleFinder1);
    DefaultRuleKeyFactory ruleKeyFactory2 =
        new DefaultRuleKeyFactory(
            0,
            FakeFileHashCache.createFromStrings(fileHashes.build()),
            pathResolver2,
            ruleFinder2);

    RuleKey key1 = ruleKeyFactory.build(rule1);
    RuleKey key2 = ruleKeyFactory2.build(rule2);
    assertEquals(key1, key2);
  }

  @Test
  public void testWhenNoJavacIsProvidedAJavacInMemoryStepIsAdded() throws Exception {
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildRule rule = JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build(ruleResolver);
    DefaultJavaLibrary buildRule = (DefaultJavaLibrary) rule;
    ImmutableList<Step> steps = buildRule.getBuildSteps(
        FakeBuildContext.withSourcePathResolver(
            new SourcePathResolver(new SourcePathRuleFinder(ruleResolver))),
        new FakeBuildableContext());

    assertEquals(10, steps.size());
    assertTrue(((JavacStep) steps.get(6)).getJavac() instanceof Jsr199Javac);
  }

  @Test
  public void testWhenJavacJarIsProvidedAJavacInMemoryStepIsAdded() throws Exception {
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildTarget javacTarget = BuildTargetFactory.newInstance("//langtools:javac");
    TargetNode<?, ?> javacNode = PrebuiltJarBuilder.createBuilder(javacTarget)
        .setBinaryJar(Paths.get("java/src/com/libone/JavacJar.jar"))
        .build();
    TargetNode<?, ?> ruleNode = JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .setCompiler(new DefaultBuildTargetSourcePath(javacTarget))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(javacNode, ruleNode);
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    BuildRule javac = ruleResolver.requireRule(javacTarget);
    BuildRule rule = ruleResolver.requireRule(libraryOneTarget);

    DefaultJavaLibrary buildable = (DefaultJavaLibrary) rule;
    ImmutableList<Step> steps = buildable.getBuildSteps(
        FakeBuildContext.withSourcePathResolver(
            new SourcePathResolver(new SourcePathRuleFinder(ruleResolver))),
        new FakeBuildableContext());
    assertEquals(10, steps.size());
    Javac javacStep = ((JavacStep) steps.get(6)).getJavac();
    assertTrue(javacStep instanceof Jsr199Javac);
    JarBackedJavac jsrJavac = ((JarBackedJavac) javacStep);
    assertEquals(
        RichStream.from(jsrJavac.getCompilerClassPath())
            .map(pathResolver::getRelativePath)
            .collect(MoreCollectors.toImmutableSet()),
        ImmutableSet.of(pathResolver.getRelativePath(javac.getSourcePathToOutput()))
    );
  }

  // Utilities
  private JavaLibrary getJavaLibrary(BuildRule rule) {
    return (JavaLibrary) rule;
  }

  private void writeAbiJar(
      ProjectFilesystem filesystem,
      Path abiJarPath,
      String fileName,
      String fileContents) throws IOException {
    try (HashingDeterministicJarWriter jar = new HashingDeterministicJarWriter(
        new ZipOutputStream(filesystem.newFileOutputStream(abiJarPath)))) {
      jar.writeEntry(
          fileName,
          new ByteArrayInputStream(fileContents.getBytes(StandardCharsets.UTF_8)));
    }
  }

  // test.
  private BuildContext createBuildContext(BuildRule javaLibrary, @Nullable String bootclasspath) {
    AndroidPlatformTarget platformTarget = EasyMock.createMock(AndroidPlatformTarget.class);
    ImmutableList<Path> bootclasspathEntries = (bootclasspath == null)
        ? ImmutableList.of(Paths.get("I am not used"))
        : ImmutableList.of(Paths.get(bootclasspath));
    expect(platformTarget.getBootclasspathEntries()).andReturn(bootclasspathEntries)
        .anyTimes();
    replay(platformTarget);

    // TODO(bolinfest): Create a utility that populates a BuildContext.Builder with fakes.
    return BuildContext.builder()
        .setActionGraph(new ActionGraph(ImmutableList.of(javaLibrary)))
        .setSourcePathResolver(new SourcePathResolver(new SourcePathRuleFinder(ruleResolver)))
        .setJavaPackageFinder(EasyMock.createMock(JavaPackageFinder.class))
        .setAndroidPlatformTargetSupplier(Suppliers.ofInstance(platformTarget))
        .setEventBus(BuckEventBusFactory.newInstance())
        .build();
  }

  private abstract static class AnnotationProcessorTarget {
    private final String targetName;

    private AnnotationProcessorTarget(String targetName) {
      this.targetName = targetName;
    }

    public BuildTarget createTarget() {
      return BuildTargetFactory.newInstance(targetName);
    }

    public abstract BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException;
  }

  private AnnotationProcessorTarget validPrebuiltJar =
      new AnnotationProcessorTarget("//tools/java/src/com/facebook/library:prebuilt-processors") {
    @Override
    public BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException {
      return PrebuiltJarBuilder.createBuilder(target)
          .setBinaryJar(Paths.get("MyJar"))
          .build(ruleResolver);
    }
  };

  private AnnotationProcessorTarget validJavaBinary =
      new AnnotationProcessorTarget("//tools/java/src/com/facebook/annotations:custom-processors") {
        @Override
        public BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException {
          return new JavaBinaryRuleBuilder(target)
              .setMainClass("com.facebook.Main")
              .build(ruleResolver);
        }
      };

  private AnnotationProcessorTarget validJavaLibrary =
      new AnnotationProcessorTarget("//tools/java/src/com/facebook/somejava:library") {
      @Override
      public BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException {
        return JavaLibraryBuilder.createBuilder(target)
            .addSrc(Paths.get("MyClass.java"))
            .setProguardConfig(new FakeSourcePath("MyProguardConfig"))
            .build(ruleResolver);
      }
    };

  // Captures all the common code between the different annotation processing test scenarios.
  private class AnnotationProcessingScenario {
    private final AnnotationProcessingParams.Builder annotationProcessingParamsBuilder;

    public AnnotationProcessingScenario() throws IOException {
      annotationProcessingParamsBuilder = new AnnotationProcessingParams.Builder()
          .setSafeAnnotationProcessors(Collections.emptySet());
    }

    public AnnotationProcessingParams.Builder getAnnotationProcessingParamsBuilder() {
      return annotationProcessingParamsBuilder;
    }

    public void addAnnotationProcessorTarget(AnnotationProcessorTarget processor)
        throws NoSuchBuildTargetException {
      BuildTarget target = processor.createTarget();
      BuildRule rule = processor.createRule(target);

      annotationProcessingParamsBuilder.addProcessorBuildTarget(rule);
    }

    public ImmutableList<String> buildAndGetCompileParameters()
        throws IOException, NoSuchBuildTargetException {
      ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot().toPath());
      BuildRule javaLibrary = createJavaLibraryRule(projectFilesystem);
      BuildContext buildContext = createBuildContext(javaLibrary, /* bootclasspath */ null);
      List<Step> steps = javaLibrary.getBuildSteps(
          buildContext, new FakeBuildableContext());
      JavacStep javacCommand = lastJavacCommand(steps);

      ExecutionContext executionContext = TestExecutionContext.newBuilder()
          .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
          .setDebugEnabled(true)
          .build();

      ImmutableList<String> options = javacCommand.getOptions(
          executionContext,
          /* buildClasspathEntries */ ImmutableSortedSet.of());

      return options;
    }

    // TODO(shs96c): Actually generate a java library rule, rather than an android one.
    private BuildRule createJavaLibraryRule(ProjectFilesystem projectFilesystem)
        throws IOException, NoSuchBuildTargetException {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(ANNOTATION_SCENARIO_TARGET);
      annotationProcessingParamsBuilder.setOwnerTarget(buildTarget);
      annotationProcessingParamsBuilder.setProjectFilesystem(projectFilesystem);

      tmp.newFolder("android", "java", "src", "com", "facebook");
      String src = "android/java/src/com/facebook/Main.java";
      tmp.newFile(src);

      AnnotationProcessingParams params = annotationProcessingParamsBuilder.build();
      JavacOptions options = JavacOptions.builder(DEFAULT_JAVAC_OPTIONS)
          .setAnnotationProcessingParams(params)
          .build();

      BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
          .setProjectFilesystem(projectFilesystem)
          .build();

      JavacToJarStepFactory compileStepFactory = new JavacToJarStepFactory(
          options,
          JavacOptionsAmender.IDENTITY);
      DefaultJavaLibrary javaLibrary = DefaultJavaLibrary
          .builder(buildRuleParams, ruleResolver, compileStepFactory)
          .setSrcs(ImmutableSortedSet.of(new FakeSourcePath(src)))
          .setResources(ImmutableSortedSet.of())
          .setGeneratedSourceFolder(options.getGeneratedSourceFolderName())
          .setProguardConfig(Optional.empty())
          .setPostprocessClassesCommands(ImmutableList.of())
          .setExportedDeps(ImmutableSortedSet.of())
          .setProvidedDeps(ImmutableSortedSet.of())
          .setTrackClassUsage(options.trackClassUsage())
          .setAdditionalClasspathEntries(ImmutableSet.of())
          .setResourcesRoot(Optional.empty())
          .setManifestFile(Optional.empty())
          .setMavenCoords(Optional.empty())
          .setTests(ImmutableSortedSet.of())
          .setClassesToRemoveFromJar(options.getClassesToRemoveFromJar())
          .build();

      ruleResolver.addToIndex(javaLibrary);
      return javaLibrary;
    }

    private JavacStep lastJavacCommand(Iterable<Step> commands) {
      Step javac = null;
      for (Step step : commands) {
        if (step instanceof JavacStep) {
          javac = step;
          // Intentionally no break here, since we want the last one.
        }
      }
      assertNotNull("Expected a JavacStep in step list", javac);
      return (JavacStep) javac;
    }
  }

  private static ImmutableSet<Path> resolve(
      ImmutableSet<SourcePath> paths,
      SourcePathResolver resolver) {
    return paths.stream().map(resolver::getAbsolutePath).collect(MoreCollectors.toImmutableSet());
  }
}
