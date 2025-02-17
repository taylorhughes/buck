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

package com.facebook.buck.cli;

import static java.lang.Integer.parseInt;

import com.facebook.buck.config.Config;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.config.ConfigViewCache;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.BinaryBuildRuleToolProvider;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.PatternAndMessage;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.facebook.infer.annotation.PropagatesNullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structured representation of data read from a {@code .buckconfig} file.
 */
public class BuckConfig implements ConfigPathGetter {

  public static final String BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";

  private static final String ALIAS_SECTION_HEADER = "alias";
  public static final String RESOURCES_SECTION_HEADER = "resources";
  public static final String RESOURCES_PER_RULE_SECTION_HEADER = "resources_per_rule";

  private static final Float DEFAULT_THREAD_CORE_RATIO = Float.valueOf(1.0F);

  /**
   * This pattern is designed so that a fully-qualified build target cannot be a valid alias name
   * and vice-versa.
   */
  private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-zA-Z_-][a-zA-Z0-9_-]*");

  private static final String DEFAULT_MAX_TRACES = "25";

  private static final ImmutableMap<String, ImmutableSet<String>> IGNORE_FIELDS_FOR_DAEMON_RESTART;

  private final CellPathResolver cellPathResolver;

  private final Architecture architecture;

  private final Config config;

  private final ImmutableSetMultimap<String, BuildTarget> aliasToBuildTargetMap;

  private final ProjectFilesystem projectFilesystem;

  private final Platform platform;

  private final ImmutableMap<String, String> environment;

  private final ConfigViewCache<BuckConfig> viewCache = new ConfigViewCache<>(this);

  static {
    ImmutableMap.Builder<String, ImmutableSet<String>> ignoreFieldsForDaemonRestartBuilder =
        ImmutableMap.builder();
    ignoreFieldsForDaemonRestartBuilder.put(
        "apple", ImmutableSet.of("generate_header_symlink_tree_only"));
    ignoreFieldsForDaemonRestartBuilder.put("build", ImmutableSet.of("threads", "load_limit"));
    ignoreFieldsForDaemonRestartBuilder.put("cache", ImmutableSet.of(
        "dir", "dir_mode", "http_mode", "http_url", "mode", "slb_server_pool"));
    ignoreFieldsForDaemonRestartBuilder.put("client",
        ImmutableSet.of("id", "skip-action-graph-cache"));
    ignoreFieldsForDaemonRestartBuilder.put("log", ImmutableSet.of(
        "chrome_trace_generation", "compress_traces", "max_traces", "public_announcements"));
    ignoreFieldsForDaemonRestartBuilder.put("project", ImmutableSet.of(
        "ide_prompt", "xcode_focus_disable_build_with_buck"));
    IGNORE_FIELDS_FOR_DAEMON_RESTART = ignoreFieldsForDaemonRestartBuilder.build();
  }

  public BuckConfig(
      Config config,
      ProjectFilesystem projectFilesystem,
      Architecture architecture,
      Platform platform,
      ImmutableMap<String, String> environment,
      CellPathResolver cellPathResolver) {
    this.cellPathResolver = cellPathResolver;
    this.config = config;
    this.projectFilesystem = projectFilesystem;
    this.architecture = architecture;

    // We could create this Map on demand; however, in practice, it is almost always needed when
    // BuckConfig is needed because CommandLineBuildTargetNormalizer needs it.
    this.aliasToBuildTargetMap = createAliasToBuildTargetMap(
        this.getEntriesForSection(ALIAS_SECTION_HEADER));

    this.platform = platform;
    this.environment = environment;
  }

  /**
   * Get a {@link ConfigView} of this config.
   *
   * @param cls Class of the config view.
   * @param <T> Type of the config view.
   */
  public <T extends ConfigView<BuckConfig>> T getView(final Class<T> cls) {
    return viewCache.getView(cls);
  }

  /**
   * @return whether {@code aliasName} conforms to the pattern for a valid alias name. This does not
   *     indicate whether it is an alias that maps to a build target in a BuckConfig.
   */
  private static boolean isValidAliasName(String aliasName) {
    return ALIAS_PATTERN.matcher(aliasName).matches();
  }

  public static void validateAliasName(String aliasName) throws HumanReadableException {
    validateAgainstAlias(aliasName, "Alias");
  }

  public static void validateLabelName(String aliasName) throws HumanReadableException {
    validateAgainstAlias(aliasName, "Label");
  }

  private static void validateAgainstAlias(String aliasName, String fieldName) {
    if (isValidAliasName(aliasName)) {
      return;
    }

    if (aliasName.isEmpty()) {
      throw new HumanReadableException("%s cannot be the empty string.", fieldName);
    }

    throw new HumanReadableException("Not a valid %s: %s.", fieldName.toLowerCase(), aliasName);
  }

  public Architecture getArchitecture() {
    return architecture;
  }

  public ImmutableMap<String, String> getEntriesForSection(String section) {
    ImmutableMap<String, String> entries = config.get(section);
    if (entries != null) {
      return entries;
    } else {
      return ImmutableMap.of();
    }
  }

  public ImmutableList<String> getMessageOfTheDay() {
    return getListWithoutComments("project", "motd");
  }

  public ImmutableList<String> getListWithoutComments(String section, String field) {
    return config.getListWithoutComments(section, field);
  }

  public ImmutableList<String> getListWithoutComments(
      String section, String field, char splitChar) {
    return config.getListWithoutComments(section, field, splitChar);
  }

  public CellPathResolver getCellPathResolver() {
    return cellPathResolver;
  }

  public Optional<ImmutableList<String>> getOptionalListWithoutComments(
      String section, String field) {
    return config.getOptionalListWithoutComments(section, field);
  }

  public Optional<ImmutableList<String>> getOptionalListWithoutComments(
      String section, String field, char splitChar) {
    return config.getOptionalListWithoutComments(section, field, splitChar);
  }

  public Optional<ImmutableList<Path>> getOptionalPathList(
      String section, String field) {
    Optional<ImmutableList<String>> rawPaths =
        config.getOptionalListWithoutComments(section, field);

    if (rawPaths.isPresent()) {
      ImmutableList<Path> paths =
          rawPaths.get().stream()
              .map(input -> convertPath(input, true))
              .collect(MoreCollectors.toImmutableList());
        return Optional.of(paths);
    }

    return Optional.empty();
  }

  public ImmutableSet<String> getBuildTargetForAliasAsString(String possiblyFlavoredAlias) {
    String[] parts = possiblyFlavoredAlias.split("#", 2);
    String unflavoredAlias = parts[0];
    ImmutableSet<BuildTarget> buildTargets = getBuildTargetsForAlias(unflavoredAlias);
    if (buildTargets.isEmpty()) {
      return ImmutableSet.of();
    }
    String suffix = parts.length == 2 ? "#" + parts[1] : "";
    return buildTargets.stream()
        .map(buildTarget -> buildTarget.getFullyQualifiedName() + suffix)
        .collect(MoreCollectors.toImmutableSet());
  }

  public ImmutableSet<BuildTarget> getBuildTargetsForAlias(String unflavoredAlias) {
    return aliasToBuildTargetMap.get(unflavoredAlias);
  }

  public BuildTarget getBuildTargetForFullyQualifiedTarget(String target) {
    return BuildTargetParser.INSTANCE.parse(
        target,
        BuildTargetPatternParser.fullyQualified(),
        getCellPathResolver());
  }

  public ImmutableList<BuildTarget> getBuildTargetList(String section, String key) {
    ImmutableList<String> targetsToForce = getListWithoutComments(section, key);
    if (targetsToForce.size() == 0) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<BuildTarget> targets = new ImmutableList.Builder<>();
    for (String targetOrAlias : targetsToForce) {
      Set<String> expandedAlias = getBuildTargetForAliasAsString(targetOrAlias);
      if (expandedAlias.isEmpty()) {
        targets.add(getBuildTargetForFullyQualifiedTarget(targetOrAlias));
      } else {
        for (String target : expandedAlias) {
          targets.add(getBuildTargetForFullyQualifiedTarget(target));
        }
      }
    }
    return targets.build();
  }

  /**
   * @return the parsed BuildTarget in the given section and field, if set.
   */
  public Optional<BuildTarget> getBuildTarget(String section, String field) {
    Optional<String> target = getValue(section, field);
    return target.isPresent() ?
        Optional.of(getBuildTargetForFullyQualifiedTarget(target.get())) :
        Optional.empty();
  }

  /**
   * @return the parsed BuildTarget in the given section and field, if set and a valid build target.
   *
   * This is useful if you use getTool to get the target, if any, but allow filesystem references.
   */
  public Optional<BuildTarget> getMaybeBuildTarget(String section, String field) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      return Optional.of(getBuildTargetForFullyQualifiedTarget(value.get()));
    } catch (BuildTargetParseException e) {
      return Optional.empty();
    }
  }

  /**
   * @return the parsed BuildTarget in the given section and field.
   */
  public BuildTarget getRequiredBuildTarget(String section, String field) {
    Optional<BuildTarget> target = getBuildTarget(section, field);
    return required(section, field, target);
  }

  public <T extends Enum<T>> Optional<T> getEnum(String section, String field, Class<T> clazz) {
    return config.getEnum(section, field, clazz);
  }

  /**
   * @return a {@link SourcePath} identified by a @{link BuildTarget} or {@link Path} reference
   *     by the given section:field, if set.
   */
  public Optional<SourcePath> getSourcePath(String section, String field) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      BuildTarget target = getBuildTargetForFullyQualifiedTarget(value.get());
      return Optional.of(new DefaultBuildTargetSourcePath(target));
    } catch (BuildTargetParseException e) {
      checkPathExists(
          value.get(),
          String.format("Overridden %s:%s path not found: ", section, field));
      return Optional.of(
          new PathSourcePath(projectFilesystem, getPathFromVfs(value.get())));
    }
  }

  /**
   * @return a {@link Tool} identified by a @{link BuildTarget} or {@link Path} reference
   *     by the given section:field, if set.
   */
  public Optional<ToolProvider> getToolProvider(String section, String field) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    Optional<BuildTarget> target = getMaybeBuildTarget(section, field);
    if (target.isPresent()) {
      return Optional.of(
          new BinaryBuildRuleToolProvider(
              target.get(),
              String.format("[%s] %s", section, field)));
    } else {
      checkPathExists(
          value.get(),
          String.format("Overridden %s:%s path not found: ", section, field));
      return Optional.of(
          new ConstantToolProvider(new HashedFileTool(getPathFromVfs(value.get()))));
    }
  }

  public Optional<Tool> getTool(String section, String field, BuildRuleResolver resolver) {
    Optional<ToolProvider> provider = getToolProvider(section, field);
    if (!provider.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(provider.get().resolve(resolver));
  }

  public Tool getRequiredTool(String section, String field, BuildRuleResolver resolver) {
    Optional<Tool> path = getTool(section, field, resolver);
    return required(section, field, path);
  }

  /**
   * In a {@link BuckConfig}, an alias can either refer to a fully-qualified build target, or an
   * alias defined earlier in the {@code alias} section. The mapping produced by this method
   * reflects the result of resolving all aliases as values in the {@code alias} section.
   */
  private ImmutableSetMultimap<String, BuildTarget> createAliasToBuildTargetMap(
      ImmutableMap<String, String> rawAliasMap) {
    // We use a LinkedHashMap rather than an ImmutableMap.Builder because we want both (1) order to
    // be preserved, and (2) the ability to inspect the Map while building it up.
    SetMultimap<String, BuildTarget> aliasToBuildTarget = LinkedHashMultimap.create();
    for (Map.Entry<String, String> aliasEntry : rawAliasMap.entrySet()) {
      String alias = aliasEntry.getKey();
      validateAliasName(alias);

      // Determine whether the mapping is to a build target or to an alias.
      List<String> values = Splitter.on(' ').splitToList(aliasEntry.getValue());
      for (String value : values) {
        Set<BuildTarget> buildTargets;
        if (isValidAliasName(value)) {
          buildTargets = aliasToBuildTarget.get(value);
          if (buildTargets.isEmpty()) {
            throw new HumanReadableException("No alias for: %s.", value);
          }
        } else if (value.isEmpty()) {
          continue;
        } else {
          // Here we parse the alias values with a BuildTargetParser to be strict. We could be
          // looser and just grab everything between "//" and ":" and assume it's a valid base path.
          buildTargets = ImmutableSet.of(BuildTargetParser.INSTANCE.parse(
              value,
              BuildTargetPatternParser.fullyQualified(),
              getCellPathResolver()));
        }
        aliasToBuildTarget.putAll(alias, buildTargets);
      }
    }
    return ImmutableSetMultimap.copyOf(aliasToBuildTarget);
  }

  /**
   * Create a map of {@link BuildTarget} base paths to aliases. Note that there may be more than
   * one alias to a base path, so the first one listed in the .buckconfig will be chosen.
   */
  public ImmutableMap<Path, String> getBasePathToAliasMap() {
    ImmutableMap<String, String> aliases = config.get(ALIAS_SECTION_HEADER);
    if (aliases == null) {
      return ImmutableMap.of();
    }

    // Build up the Map with an ordinary HashMap because we need to be able to check whether the Map
    // already contains the key before inserting.
    Map<Path, String> basePathToAlias = Maps.newHashMap();
    for (Map.Entry<String, BuildTarget> entry : aliasToBuildTargetMap.entries()) {
      String alias = entry.getKey();
      BuildTarget buildTarget = entry.getValue();

      Path basePath = buildTarget.getBasePath();
      if (!basePathToAlias.containsKey(basePath)) {
        basePathToAlias.put(basePath, alias);
      }
    }
    return ImmutableMap.copyOf(basePathToAlias);
  }

  public ImmutableMultimap<String, BuildTarget> getAliases() {
    return this.aliasToBuildTargetMap;
  }

  public long getDefaultTestTimeoutMillis() {
    return Long.parseLong(getValue("test", "timeout").orElse("0"));
  }

  private static final String LOG_SECTION = "log";

  public int getMaxTraces() {
    return parseInt(getValue(LOG_SECTION, "max_traces").orElse(DEFAULT_MAX_TRACES));
  }

  public boolean isChromeTraceCreationEnabled() {
    return getBooleanValue(LOG_SECTION, "chrome_trace_generation", true);
  }

  public boolean isPublicAnnouncementsEnabled() {
    return getBooleanValue(LOG_SECTION, "public_announcements", true);
  }

  public boolean isProcessTrackerEnabled() {
    return getBooleanValue(LOG_SECTION, "process_tracker_enabled", true);
  }

  public boolean isProcessTrackerDeepEnabled() {
    return getBooleanValue(LOG_SECTION, "process_tracker_deep_enabled", false);
  }

  public boolean isRuleKeyLoggerEnabled() {
    return getBooleanValue(LOG_SECTION, "rule_key_logger_enabled", false);
  }

  public boolean isMachineReadableLoggerEnabled() {
    return getBooleanValue(LOG_SECTION, "machine_readable_logger_enabled", true);
  }

  public boolean getCompressTraces() {
    return getBooleanValue("log", "compress_traces", false);
  }

  public ProjectTestsMode xcodeProjectTestsMode() {
    return getEnum("project", "xcode_project_tests_mode", ProjectTestsMode.class).orElse(
        ProjectTestsMode.WITH_TESTS);
  }

  public boolean getRestartAdbOnFailure() {
    return Boolean.parseBoolean(getValue("adb", "adb_restart_on_failure").orElse("true"));
  }

  public boolean getMultiInstallMode() {
    return getBooleanValue("adb", "multi_install_mode", false);
  }

  public boolean getFlushEventsBeforeExit() {
    return getBooleanValue("daemon", "flush_events_before_exit", false);
  }

  public ImmutableSet<String> getListenerJars() {
    return ImmutableSet.copyOf(getListWithoutComments("extensions", "listeners"));
  }

  /**
   * Return Strings so as to avoid a dependency on {@link LabelSelector}!
   */
  public ImmutableList<String> getDefaultRawExcludedLabelSelectors() {
    return getListWithoutComments("test", "excluded_labels");
  }

  /**
   * Create an Ansi object appropriate for the current output. First respect the user's
   * preferences, if set. Next, respect any default provided by the caller. (This is used by buckd
   * to tell the daemon about the client's terminal.) Finally, allow the Ansi class to autodetect
   * whether the current output is a tty.
   * @param defaultColor Default value provided by the caller (e.g. the client of buckd)
   */
  public Ansi createAnsi(Optional<String> defaultColor) {
    String color = getValue("color", "ui").map(Optional::of).orElse(defaultColor).orElse("auto");

    switch (color) {
      case "false":
      case "never":
        return Ansi.withoutTty();
      case "true":
      case "always":
        return Ansi.forceTty();
      case "auto":
      default:
        return new Ansi(
            AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(platform, environment));
    }
  }

  public Path resolvePathThatMayBeOutsideTheProjectFilesystem(@PropagatesNullable Path path) {
    if (path == null) {
      return path;
    }
    return resolveNonNullPathOutsideTheProjectFilesystem(path);
  }

  public Path resolveNonNullPathOutsideTheProjectFilesystem(Path path) {
    if (path.isAbsolute()) {
      return getPathFromVfs(path);
    }

    Path expandedPath = MorePaths.expandHomeDir(path);
    return projectFilesystem.resolve(expandedPath);
  }

  public String getLocalhost() {
    try {
      return HostnameFetching.getHostname();
    } catch (IOException e) {
      return "<unknown>";
    }
  }

  public Platform getPlatform() {
    return platform;
  }

  public boolean isActionGraphCheckingEnabled() {
    return getBooleanValue("cache", "action_graph_cache_check_enabled", false);
  }

  public Optional<String> getRepository() {
    return config.get("cache", "repository");
  }

  public Optional<ImmutableSet<PatternAndMessage>> getUnexpectedFlavorsMessages() {
    ImmutableMap<String, String> entries = config.get("unknown_flavors_messages");
    if (!entries.isEmpty()) {
      Set<PatternAndMessage> patternAndMessages = new HashSet<>();
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        patternAndMessages.add(
            PatternAndMessage.of(Pattern.compile(entry.getKey()), entry.getValue()));
      }
      return Optional.of(ImmutableSet.copyOf(patternAndMessages));
    }

    return Optional.empty();
  }

  public boolean hasUserDefinedValue(String sectionName, String propertyName) {
    return config.get(sectionName).containsKey(propertyName);
  }

  public Optional<ImmutableMap<String, String>> getSection(String sectionName) {
    ImmutableMap<String, String> values = config.get(sectionName);
    return values.isEmpty() ? Optional.empty() : Optional.of(values);
  }

  public Optional<String> getValue(String sectionName, String propertyName) {
    return config.getValue(sectionName, propertyName);
  }

  public Optional<Integer> getInteger(String sectionName, String propertyName) {
    return config.getInteger(sectionName, propertyName);
  }

  public Optional<Long> getLong(String sectionName, String propertyName) {
    return config.getLong(sectionName, propertyName);
  }

  public Optional<Float> getFloat(String sectionName, String propertyName) {
    return config.getFloat(sectionName, propertyName);
  }

  public Optional<Boolean> getBoolean(String sectionName, String propertyName) {
    return config.getBoolean(sectionName, propertyName);
  }

  public boolean getBooleanValue(String sectionName, String propertyName, boolean defaultValue) {
    return config.getBooleanValue(sectionName, propertyName, defaultValue);
  }

  public Optional<URI> getUrl(String section, String field) {
    return config.getUrl(section, field);
  }

  public ImmutableMap<String, String> getMap(String section, String field) {
    Optional<String> value = getValue(section, field);
    if (value.isPresent()) {
      return ImmutableMap.copyOf(
          Splitter.on(',')
              .omitEmptyStrings()
              .withKeyValueSeparator("=>")
              .split(value.get().trim())
              .entrySet());
    } else {
      return ImmutableMap.of();
    }
  }

  private <T> T required(String section, String field, Optional<T> value) {
    if (!value.isPresent()) {
      throw new HumanReadableException(String.format(
          ".buckconfig: %s:%s must be set",
          section,
          field));
    }
    return value.get();
  }

  // This is a hack. A cleaner approach would be to expose a narrow view of the config to any code
  // that affects the state cached by the Daemon.
  public boolean equalsForDaemonRestart(BuckConfig other) {
    return this.config.equalsIgnoring(
        other.config,
        IGNORE_FIELDS_FOR_DAEMON_RESTART);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (!(obj instanceof BuckConfig)) {
      return false;
    }
    BuckConfig that = (BuckConfig) obj;
    return Objects.equal(this.config, that.config);
  }

  @Override
  public String toString() {
    return String.format("%s (config=%s)", super.toString(), config);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(config);
  }

  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  public String[] getEnv(String propertyName, String separator) {
    String value = getEnvironment().get(propertyName);
    if (value == null) {
      value = "";
    }
    return value.split(separator);
  }
  /**
   * @return the local cache directory
   */
  public String getLocalCacheDirectory(String dirCacheName) {
    return getValue(dirCacheName, "dir")
        .orElse(projectFilesystem.getBuckPaths().getCacheDir().toString());
  }

  public int getKeySeed() {
    return parseInt(getValue("cache", "key_seed").orElse("0"));
  }

  /**
   * @return the path for the given section and property.
   */
  @Override
  public Optional<Path> getPath(String sectionName, String name) {
    return getPath(sectionName, name, true);
  }

  public Path getRequiredPath(String section, String field) {
    Optional<Path> path = getPath(section, field);
    return required(section, field, path);
  }

  public String getClientId() {
    return getValue("client", "id").orElse("buck");
  }

  /**
   * @return whether the current invocation of Buck should skip the Action Graph cache, leaving the
   *     cached Action Graph in memory for the next request and creating a fresh Action Graph for
   *     the current request (which will be garbage-collected when the current request is complete).
   *     Commonly, a one-off request, like from a linter, will specify this option so that it does
   *     not invalidate the primary in-memory Action Graph that the user is likely relying on for
   *     fast iterative builds.
   */
  public boolean isSkipActionGraphCache() {
    return getBooleanValue("client", "skip-action-graph-cache", false);
  }

  /**
   * @return the number of threads Buck should use.
   */
  public int getNumThreads() {
    return getNumThreads(getDefaultMaximumNumberOfThreads());
  }

  private int getDefaultMaximumNumberOfThreads() {
    return getDefaultMaximumNumberOfThreads(Runtime.getRuntime().availableProcessors());
  }

  @VisibleForTesting
  int getDefaultMaximumNumberOfThreads(int detectedProcessorCount) {
    double ratio = config
        .getFloat("build", "thread_core_ratio").orElse(DEFAULT_THREAD_CORE_RATIO);
    if (ratio <= 0.0F) {
      throw new HumanReadableException(
          "thread_core_ratio must be greater than zero (was " + ratio + ")");
    }

    int scaledValue = (int) Math.ceil(ratio * detectedProcessorCount);

    int threadLimit = detectedProcessorCount;

    Optional<Long> reservedCores = getNumberOfReservedCores();
    if (reservedCores.isPresent()) {
      threadLimit -= reservedCores.get();
    }

    if (scaledValue > threadLimit) {
      scaledValue = threadLimit;
    }

    Optional<Long> minThreads = getThreadCoreRatioMinThreads();
    if (minThreads.isPresent()) {
      scaledValue = Math.max(scaledValue, minThreads.get().intValue());
    }

    Optional<Long> maxThreads = getThreadCoreRatioMaxThreads();
    if (maxThreads.isPresent()) {
      long maxThreadsValue = maxThreads.get();

      if (minThreads.isPresent() && minThreads.get() > maxThreadsValue) {
        throw new HumanReadableException(
            "thread_core_ratio_max_cores must be larger than thread_core_ratio_min_cores"
        );
      }

      if (maxThreadsValue > threadLimit) {
        throw new HumanReadableException(
            "thread_core_ratio_max_cores is larger than thread_core_ratio_reserved_cores allows"
        );
      }

      scaledValue = Math.min(scaledValue, (int) maxThreadsValue);
    }


    if (scaledValue <= 0) {
      throw new HumanReadableException(
          "Configuration resulted in an invalid number of build threads (" +
              scaledValue +
              ").");
    }

    return scaledValue;
  }

  private Optional<Long> getNumberOfReservedCores() {
    Optional<Long> reservedCores = config.getLong("build", "thread_core_ratio_reserved_cores");
    if (reservedCores.isPresent() && reservedCores.get() < 0) {
      throw new HumanReadableException("thread_core_ratio_reserved_cores must be larger than zero");
    }
    return reservedCores;
  }

  private Optional<Long> getThreadCoreRatioMaxThreads() {
    Optional<Long> maxThreads = config.getLong("build", "thread_core_ratio_max_threads");
    if (maxThreads.isPresent() && maxThreads.get() < 0) {
      throw new HumanReadableException("thread_core_ratio_max_threads must be larger than zero");
    }
    return maxThreads;
  }


  private Optional<Long> getThreadCoreRatioMinThreads() {
    Optional<Long> minThreads = config.getLong("build", "thread_core_ratio_min_threads");
    if (minThreads.isPresent() && minThreads.get() <= 0) {
      throw new HumanReadableException("thread_core_ratio_min_threads must be larger than zero");
    }
    return minThreads;
  }

  /**
   * @return the number of threads Buck should use or the specified defaultValue if it is not set.
   */
  public int getNumThreads(int defaultValue) {
    return config.getLong("build", "threads").orElse((long) defaultValue)
        .intValue();
  }

  public Optional<ImmutableList<String>> getAllowedJavaSpecificationVersions() {
    return getOptionalListWithoutComments("project", "allowed_java_specification_versions");
  }

  /**
   * @return the maximum load limit that Buck should stay under on the system.
   */
  public float getLoadLimit() {
    return config.getFloat("build", "load_limit").orElse(Float.POSITIVE_INFINITY);
  }

  public long getCountersFirstFlushIntervalMillis() {
    return config.getLong("counters", "first_flush_interval_millis").orElse(5000L);
  }

  public long getCountersFlushIntervalMillis() {
    return config.getLong("counters", "flush_interval_millis").orElse(30000L);
  }

  public Optional<Path> getPath(String sectionName, String name, boolean isCellRootRelative) {
    Optional<String> pathString = getValue(sectionName, name);
    return pathString.isPresent() ?
        Optional.of(convertPathWithError(
            pathString.get(),
            isCellRootRelative,
            String.format("Overridden %s:%s path not found: ", sectionName, name))) :
        Optional.empty();
  }

  /**
   * Return a {@link Path} from the underlying {@link java.nio.file.FileSystem} implementation. This
   * allows to safely call {@link Path#resolve(Path)} and similar calls without exceptions caused by
   * mis-matched underlying filesystem implementations causing grief. This is particularly useful
   * for those times where we're using (eg) JimFs for our testing.
   */
  private Path getPathFromVfs(String path, String... extra) {
    return projectFilesystem.getPath(path, extra);
  }

  private Path getPathFromVfs(Path path) {
    return projectFilesystem.getPath(path.toString());
  }

  private Path convertPathWithError(String pathString, boolean isCellRootRelative, String error) {
    return isCellRootRelative ?
        checkPathExists(
            pathString,
            error).get() :
        getPathFromVfs(pathString);
  }

  private Path convertPath(String pathString, boolean isCellRootRelative) {
    return convertPathWithError(
        pathString,
        isCellRootRelative,
        isCellRootRelative ? "Cell-relative path not found: " : "Path not found: ");
  }

  public Optional<Path> checkPathExists(String pathString, String errorMsg) {
    Path path = getPathFromVfs(pathString);
    if (projectFilesystem.exists(path)) {
      return Optional.of(projectFilesystem.getPathForRelativePath(path));
    }
    throw new HumanReadableException(errorMsg + path);
  }

  public ImmutableSet<String> getSections() {
    return config.getSectionToEntries().keySet();
  }

  public ImmutableMap<String, ImmutableMap<String, String>> getRawConfigForDistBuild() {
    return config.getSectionToEntries();
  }

  public ImmutableMap<String, ImmutableMap<String, String>> getRawConfigForParser() {
    ImmutableMap<String, ImmutableMap<String, String>> rawSections =
        config.getSectionToEntries();

    // If the raw config doesn't have sections which have ignored fields, then just return it as-is.
    ImmutableSet<String> sectionsWithIgnoredFields = IGNORE_FIELDS_FOR_DAEMON_RESTART.keySet();
    if (Sets.intersection(rawSections.keySet(), sectionsWithIgnoredFields).isEmpty()) {
      return rawSections;
    }

    // Otherwise, iterate through the config to do finer-grain filtering.
    ImmutableMap.Builder<String, ImmutableMap<String, String>> filtered = ImmutableMap.builder();
    for (Map.Entry<String, ImmutableMap<String, String>> sectionEnt : rawSections.entrySet()) {
      String sectionName = sectionEnt.getKey();

      // If this section doesn't have a corresponding ignored section, then just add it as-is.
      if (!sectionsWithIgnoredFields.contains(sectionName)) {
        filtered.put(sectionEnt);
        continue;
      }

      // If none of this section's entries are ignored, then add it as-is.
      ImmutableMap<String, String> fields = sectionEnt.getValue();
      ImmutableSet<String> ignoredFieldNames =
          IGNORE_FIELDS_FOR_DAEMON_RESTART.getOrDefault(sectionName, ImmutableSet.of());
      if (Sets.intersection(fields.keySet(), ignoredFieldNames).isEmpty()) {
        filtered.put(sectionEnt);
        continue;
      }

      // Otherwise, filter out the ignored fields.
      ImmutableMap<String, String> remainingKeys = ImmutableMap.copyOf(
          Maps.filterKeys(fields, Predicates.not(ignoredFieldNames::contains)));

      if (!remainingKeys.isEmpty()) {
        filtered.put(sectionName, remainingKeys);
      }
    }

    return filtered.build();
  }

  public Optional<ImmutableList<String>> getExternalTestRunner() {
    Optional<String> value = getValue("test", "external_runner");
    if (!value.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(ImmutableList.copyOf(Splitter.on(' ').splitToList(value.get())));
  }

  /**
   * @return whether to symlink the default output location (`buck-out`) to the user-provided
   *         override for compatibility.
   */
  public boolean getBuckOutCompatLink() {
    return getBooleanValue("project", "buck_out_compat_link", false);
  }

  public ResourceAllocationFairness getResourceAllocationFairness() {
    return config.getEnum(
        RESOURCES_SECTION_HEADER,
        "resource_allocation_fairness",
        ResourceAllocationFairness.class).orElse(ResourceAllocationFairness.FAIR);
  }

  public boolean isResourceAwareSchedulingEnabled() {
    return config.getBooleanValue(
        RESOURCES_SECTION_HEADER,
        "resource_aware_scheduling_enabled",
        false);
  }

  public boolean isGrayscaleImageProcessingEnabled() {
    return config.getBooleanValue(
        RESOURCES_SECTION_HEADER,
        "resource_grayscale_enabled",
        false);
  }

  public ImmutableMap<String, ResourceAmounts> getResourceAmountsPerRuleType() {
    ImmutableMap.Builder<String, ResourceAmounts> result = ImmutableMap.builder();
    ImmutableMap<String, String> entries = getEntriesForSection(RESOURCES_PER_RULE_SECTION_HEADER);
    for (String ruleName : entries.keySet()) {
      ImmutableList<String> configAmounts = getListWithoutComments(
          RESOURCES_PER_RULE_SECTION_HEADER,
          ruleName);
      Preconditions.checkArgument(
          configAmounts.size() == ResourceAmounts.RESOURCE_TYPE_COUNT,
          "Buck config entry [%s].%s contains %s values, but expected to contain %s values " +
              "in the following order: cpu, memory, disk_io, network_io",
          RESOURCES_PER_RULE_SECTION_HEADER,
          ruleName,
          configAmounts.size(),
          ResourceAmounts.RESOURCE_TYPE_COUNT);
      ResourceAmounts amounts = ResourceAmounts.of(
          Integer.valueOf(configAmounts.get(0)),
          Integer.valueOf(configAmounts.get(1)),
          Integer.valueOf(configAmounts.get(2)),
          Integer.valueOf(configAmounts.get(3)));
      result.put(ruleName, amounts);
    }
    return result.build();
  }

  public int getManagedThreadCount() {
    if (!isResourceAwareSchedulingEnabled()) {
      return getNumThreads();
    }
    return config.getLong(
        RESOURCES_SECTION_HEADER,
        "managed_thread_count").orElse((long) getNumThreads() + getDefaultMaximumNumberOfThreads())
        .intValue();
  }

  public ResourceAmounts getDefaultResourceAmounts() {
    if (!isResourceAwareSchedulingEnabled()) {
      return ResourceAmounts.of(1, 0, 0, 0);
    }
    return ResourceAmounts.of(
        config.getInteger(RESOURCES_SECTION_HEADER, "default_cpu_amount").orElse(
            ResourceAmountsEstimator.DEFAULT_CPU_AMOUNT),
        config.getInteger(RESOURCES_SECTION_HEADER, "default_memory_amount").orElse(
            ResourceAmountsEstimator.DEFAULT_MEMORY_AMOUNT),
        config.getInteger(RESOURCES_SECTION_HEADER, "default_disk_io_amount").orElse(
            ResourceAmountsEstimator.DEFAULT_DISK_IO_AMOUNT),
        config.getInteger(RESOURCES_SECTION_HEADER, "default_network_io_amount").orElse(
            ResourceAmountsEstimator.DEFAULT_NETWORK_IO_AMOUNT));
  }

  public ResourceAmounts getMaximumResourceAmounts() {
    ResourceAmounts estimated = ResourceAmountsEstimator.getEstimatedAmounts();
    return ResourceAmounts.of(
        getNumThreads(estimated.getCpu()),
        getInteger(
            BuckConfig.RESOURCES_SECTION_HEADER,
            "max_memory_resource").orElse(estimated.getMemory()),
        getInteger(
            BuckConfig.RESOURCES_SECTION_HEADER,
            "max_disk_io_resource").orElse(estimated.getDiskIO()),
        getInteger(
            BuckConfig.RESOURCES_SECTION_HEADER,
            "max_network_io_resource").orElse(estimated.getNetworkIO()));
  }

  public boolean getIncludeAutodepsSignature() {
    return getBooleanValue("autodeps", "include_signature", true);
  }

  /**
   * @return whether to enabled versions on build/test command.
   */
  public boolean getBuildVersions() {
    return getBooleanValue("build", "versions", false);
  }

  /**
   * @return whether to enabled versions on targets command.
   */
  public boolean getTargetsVersions() {
    return getBooleanValue("targets", "versions", false);
  }

  /**
   * @return whether to enable caching of rule key calculations between builds.
   */
  public boolean getRuleKeyCaching() {
    return getBooleanValue("build", "rule_key_caching", false);
  }

  public Config getConfig() {
    return config;
  }

}
