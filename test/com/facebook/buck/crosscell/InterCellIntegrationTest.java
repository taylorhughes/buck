/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.crosscell;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.concurrent.Executors;

public class InterCellIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void ensureThatNormalBuildsWorkAsExpected() throws IOException {
    ProjectWorkspace secondary = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "inter-cell/export-file/secondary",
        tmp);
    secondary.setUp();

    ProjectWorkspace.ProcessResult result = secondary.runBuckBuild("//:hello");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseAnExportFileXRepoTarget() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    String expected = secondary.getFileContents("hello.txt");
    Path path = primary.buildAndReturnOutput("//:exported-file");

    String actual = new String(Files.readAllBytes(path), UTF_8);

    assertEquals(expected, actual);
  }

  @Test
  public void shouldBeAbleToUseTargetsCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace.ProcessResult result = primary.runBuckCommand(
        "targets",
        "--show-target-hash",
        "//:cxxbinary");
    result.assertSuccess();

    ProjectWorkspace.ProcessResult result2 = primary.runBuckCommand(
        "targets",
        "secondary//:cxxlib");
    result2.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseQueryCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace primary = createWorkspace("inter-cell/multi-cell/primary");
    primary.setUp();
    ProjectWorkspace secondary = createWorkspace("inter-cell/multi-cell/secondary");
    secondary.setUp();
    ProjectWorkspace ternary = createWorkspace("inter-cell/multi-cell/ternary");
    ternary.setUp();
    registerCell(secondary, "ternary", ternary);
    registerCell(primary, "secondary", secondary);
    registerCell(primary, "ternary", ternary);

    primary.runBuckCommand("targets", "--show-target-hash", "//:cxxbinary");
    secondary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib");
    ternary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib2");

    ProjectWorkspace.ProcessResult result = primary.runBuckCommand(
        "query",
        "deps(%s)",
        "//:cxxbinary");
    result.assertSuccess();
    assertThat(
        result.getStdout(),
        is(primary.getFileContents("stdout-cross-cell-dep")));
  }

  @Test
  public void shouldBeAbleToUseProjectCommandXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();

    ProjectWorkspace.ProcessResult result = primary.runBuckCommand("project", "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseACxxLibraryXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild("//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseMultipleXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace primary = createWorkspace("inter-cell/multi-cell/primary");
    ProjectWorkspace secondary = createWorkspace("inter-cell/multi-cell/secondary");
    ProjectWorkspace ternary = createWorkspace("inter-cell/multi-cell/ternary");
    registerCell(secondary, "ternary", ternary);
    registerCell(primary, "secondary", secondary);
    registerCell(primary, "ternary", ternary);

    primary.runBuckCommand("targets", "--show-target-hash", "//:cxxbinary");
    secondary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib");
    ternary.runBuckCommand("targets", "--show-target-hash", "//:cxxlib2");

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild("//:cxxbinary");
    result.assertSuccess();
  }

  @Test
  public void xCellCxxLibraryBuildsShouldBeHermetic() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    Path firstBinary = primary.buildAndReturnOutput("//:cxxbinary");
    ImmutableMap<String, HashCode> firstPrimaryObjectFiles = findObjectFiles(primary);
    ImmutableMap<String, HashCode> firstObjectFiles = findObjectFiles(secondary);

    // Now recreate an identical checkout
    cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    primary = cells.getFirst();
    secondary = cells.getSecond();

    Path secondBinary = primary.buildAndReturnOutput("//:cxxbinary");
    ImmutableMap<String, HashCode> secondPrimaryObjectFiles = findObjectFiles(primary);
    ImmutableMap<String, HashCode> secondObjectFiles = findObjectFiles(secondary);

    assertEquals(firstPrimaryObjectFiles, secondPrimaryObjectFiles);
    assertEquals(firstObjectFiles, secondObjectFiles);
    MoreAsserts.assertContentsEqual(firstBinary, secondBinary);
  }

  private ImmutableMap<String, HashCode> findObjectFiles(final ProjectWorkspace workspace)
      throws IOException {
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());
    final Path buckOut = workspace.getPath(filesystem.getBuckPaths().getBuckOut());

    final ImmutableMap.Builder<String, HashCode> objectHashCodes = ImmutableMap.builder();
    Files.walkFileTree(buckOut, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (MorePaths.getFileExtension(file).equals("o")) {
          HashCode hash = MorePaths.asByteSource(file).hash(Hashing.sha1());
          objectHashCodes.put(buckOut.relativize(file).toString(), hash);
        }
        return FileVisitResult.CONTINUE;
      }
    });

    ImmutableMap<String, HashCode> toReturn = objectHashCodes.build();
    Preconditions.checkState(!toReturn.isEmpty());
    return toReturn;
  }

  @Test
  public void shouldBeAbleToUseAJavaLibraryTargetXCell() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/java/primary",
        "inter-cell/java/secondary");
    ProjectWorkspace primary = cells.getFirst();

    primary.runBuckBuild("//:lib").assertSuccess();
    primary.runBuckBuild("//:java-binary", "-v", "5").assertSuccess();
  }

  @Test
  public void buildFileNamesCanBeDifferentCrossCell() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/build-file-names/primary",
        "inter-cell/build-file-names/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    Path output = primary.buildAndReturnOutput("//:export");
    String expected = secondary.getFileContents("hello-world.txt");

    assertEquals(expected, new String(Files.readAllBytes(output), UTF_8));
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void xCellVisibilityShouldWorkAsExpected()
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    try {
      parseTargetForXCellVisibility("//:not-visible-target");
      fail("Did not expect parsing to succeed");
    } catch (HumanReadableException expected) {
      // Everything is as it should be.
    }
  }

  @Test
  public void xCellVisibilityPatternsBasedOnPublicBuildTargetsWork()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    parseTargetForXCellVisibility("//:public-target");
  }

  @Test
  public void xCellVisibilityPatternsBasedOnExplicitBuildTargetsWork()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    parseTargetForXCellVisibility("//:visible-target");
  }

  @Test
  public void xCellSingleDirectoryVisibilityPatternsWork()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    parseTargetForXCellVisibility("//sub2:directory");
  }

  @Test
  public void xCellSubDirectoryVisibilityPatternsWork()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    parseTargetForXCellVisibility("//sub:wild-card");
  }

  private void parseTargetForXCellVisibility(String targetName)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/visibility/primary",
        "inter-cell/visibility/secondary");

    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    registerCell(primary, "primary", primary);
    registerCell(secondary, "primary", primary);

    // We could just do a build, but that's a little extreme since all we need is the target graph
    TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory(
        ObjectMappers.newDefaultInstance());
    Parser parser = new Parser(
        new BroadcastEventListener(),
        primary.asCell().getBuckConfig().getView(ParserConfig.class),
        coercerFactory,
        new ConstructorArgMarshaller(coercerFactory));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    Cell primaryCell = primary.asCell();
    BuildTarget namedTarget = BuildTargetFactory.newInstance(
        primaryCell.getFilesystem(),
        targetName);

    // It's enough that this parses cleanly.
    parser.buildTargetGraph(
        eventBus,
        primaryCell,
        false,
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
        ImmutableSet.of(namedTarget));
  }

  @Test
  @Ignore
  public void allOutputsShouldBePlacedInTheSameRootOutputFolder() {

  }

  @Test
  public void circularCellReferencesAreAllowed() throws IOException {
    ProjectWorkspace mainRepo = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "inter-cell/circular",
        tmp);
    mainRepo.setUp();
    Path primary = mainRepo.getPath("primary");

    ProjectWorkspace.ProcessResult result =
        mainRepo.runBuckCommandWithEnvironmentOverridesAndContext(
            primary,
            Optional.empty(),
            ImmutableMap.of(),
            "build",
            "//:bin");

    result.assertSuccess();
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void shouldBeAbleToUseCommandLineConfigOverrides() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        secondary,
        ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));

    try {
      primary.runBuckBuild("//:cxxbinary");
      fail("Did not expect to finish building");
    } catch (HumanReadableException expected) {
      assertEquals(
          expected.getMessage(),
          "Couldn't get dependency 'secondary//:cxxlib' of target '//:cxxbinary':\n" +
              "Overridden cxx:cc path not found: /does/not/exist");
    }

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild(
        "--config",
        "secondary//cxx.cc=",
        "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void globalCommandLineConfigOverridesShouldWork() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));
    TestDataHelper.overrideBuckconfig(
        secondary,
        ImmutableMap.of("cxx", ImmutableMap.of("cc", "/does/not/exist")));

    try {
      primary.runBuckBuild("//:cxxbinary");
      fail("Did not expect to finish building");
    } catch (HumanReadableException expected) {
      assertEquals(
        expected.getMessage(),
        "Overridden cxx:cc path not found: /does/not/exist");
    }

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild(
        "--config",
        "cxx.cc=",
        "//:cxxbinary");

    result.assertSuccess();
  }

  @Test
  public void buildFilesCanIncludeDefsFromOtherCells() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectWorkspace root = createWorkspace("inter-cell/include-defs/root");
    ProjectWorkspace other = createWorkspace("inter-cell/include-defs/other");
    registerCell(root, "other", other);
    registerCell(root, "root", root);
    registerCell(other, "root", root);

    root.runBuckBuild("//:rule", "other//:rule").assertSuccess();
  }

  @Test
  public void shouldBeAbleToTestACxxLibrary() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    ProjectWorkspace workspace = createWorkspace("inter-cell/gtest/secondary");
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "//gtest:gtest")));

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//test:cxxtest");
    result.assertSuccess();

    result = workspace.runBuckCommand("test", "//test:cxxtest");
    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToTestACxxLibraryXCell() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/gtest/primary",
        "inter-cell/gtest/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        secondary,
        ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "//gtest:gtest")));

    ProjectWorkspace.ProcessResult result = primary.runBuckBuild("secondary//test:cxxtest");
    result.assertSuccess();

    result = primary.runBuckCommand("test", "secondary//test:cxxtest");
    result.assertSuccess();

    result = primary.runBuckCommand("test", "//main:main");
    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToShareGtest() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/gtest/primary",
        "inter-cell/gtest/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "secondary//gtest:gtest")));
    // TODO(mzlee,illicitonion): secondary//gtest:gtest should be //gtest:gtest or we
    // should be able to use different cell names
    registerCell(secondary, "secondary", secondary);
    TestDataHelper.overrideBuckconfig(
        secondary,
        ImmutableMap.of("cxx", ImmutableMap.of("gtest_dep", "secondary//gtest:gtest")));

    // TODO(mzlee,illicitonion): //test:cxxtest should be able to safely depend on
    // secondary//lib:cxxlib instead of having its own copy
    ProjectWorkspace.ProcessResult result = primary.runBuckCommand(
        "test",
        "//test:cxxtest",
        "secondary//test:cxxtest");
    result.assertSuccess();
  }

  @Test
  public void childCellWithCellMappingNotInRootCellShouldThrowError() throws IOException {
    ProjectWorkspace root = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace second = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace third = createWorkspace("inter-cell/validation/root");
    registerCell(root, "second", second);
    registerCell(second, "third", third);

    // should fail if "third" is not specified in root
    try {
      root.runBuckBuild("//:dummy");
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          containsString("repositories.third must exist in the root cell's cell mappings."));
    }

    // and succeeds when it is
    registerCell(root, "third", third);
    ProjectWorkspace.ProcessResult result = root.runBuckBuild("//:dummy");
    result.assertSuccess();
  }

  @Test
  public void childCellWithCellMappingThatDiffersFromRootCellShouldThrowError() throws IOException {
    ProjectWorkspace root = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace second = createWorkspace("inter-cell/validation/root");
    ProjectWorkspace third = createWorkspace("inter-cell/validation/root");
    registerCell(root, "second", second);
    registerCell(second, "third", third);

    // should fail if "third" is not mapped to third in the root.
    registerCell(root, "third", second);
    try {
      root.runBuckBuild("//:dummy");
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          containsString(
              "repositories.third must point to the same directory as the root cell's cell " +
                  "mapping:"));
    }

    // and succeeds when it is
    registerCell(root, "third", third);
    ProjectWorkspace.ProcessResult result = root.runBuckBuild("//:dummy");
    result.assertSuccess();
  }

  @Test
  public void testCrossCellAndroidLibrary() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/android/primary",
        "inter-cell/android/secondary");
    ProjectWorkspace primary = cells.getFirst();

    String target = "//apps/sample:app_with_cross_cell_android_lib";
    ProjectWorkspace.ProcessResult result = primary.runBuckCommand("build", target);
    result.assertSuccess();
  }

  private Pair<ProjectWorkspace, ProjectWorkspace> prepare(
      String primaryPath,
      String secondaryPath) throws IOException {

    ProjectWorkspace primary = createWorkspace(primaryPath);
    ProjectWorkspace secondary = createWorkspace(secondaryPath);

    registerCell(primary, "secondary", secondary);

    return new Pair<>(primary, secondary);
  }

  private ProjectWorkspace createWorkspace(String scenarioName) throws IOException {
    final Path tmpSubfolder = tmp.newFolder();
    ProjectWorkspace projectWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        scenarioName,
        tmpSubfolder);
    projectWorkspace.setUp();
    return projectWorkspace;
  }

  private void registerCell(
      ProjectWorkspace cellToModifyConfigOf,
      String cellName,
      ProjectWorkspace cellToRegisterAsCellName) throws IOException {
    TestDataHelper.overrideBuckconfig(
        cellToModifyConfigOf,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of(
                cellName,
                cellToRegisterAsCellName.getPath(".").normalize().toString())));
  }
}
