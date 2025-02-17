/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsFlavors {
  public static final InternalFlavor ANDROID = InternalFlavor.of("android");
  public static final InternalFlavor IOS = InternalFlavor.of("ios");
  public static final InternalFlavor RELEASE = InternalFlavor.of("release");
  public static final InternalFlavor RAM_BUNDLE_FILES = InternalFlavor.of("rambundle-files");
  public static final InternalFlavor RAM_BUNDLE_INDEXED = InternalFlavor.of("rambundle-indexed");

  private static final ImmutableSet<Flavor> libraryFlavors = ImmutableSet.of(RELEASE);
  private static final ImmutableSet<Flavor> bundleFlavors =
      ImmutableSet.of(RAM_BUNDLE_FILES, RAM_BUNDLE_INDEXED, RELEASE);
  private static final ImmutableSet<Flavor> platforms = ImmutableSet.of(ANDROID, IOS);
  private static final String fileFlavorPrefix = "file-";

  public static boolean validateLibraryFlavors(ImmutableSet<Flavor> flavors) {
    return validateFlavors(flavors, libraryFlavors);
  }

  public static boolean validateBundleFlavors(ImmutableSet<Flavor> flavors) {
    return validateFlavors(flavors, bundleFlavors);
  }

  private static boolean validateFlavors(
      ImmutableSet<Flavor> flavors, ImmutableSet<Flavor> allowableNonPlatformFlavors) {
    return Sets.intersection(flavors, platforms).size() < 2 &&
        allowableNonPlatformFlavors.containsAll(Sets.difference(flavors, platforms));
  }

  public static String getPlatform(ImmutableSet<Flavor> flavors) {
    return flavors.contains(IOS) ? "ios" : "android";
  }

  public static Flavor fileFlavorForSourcePath(final Path path) {
    final String hash = Hashing.sha1()
        .hashString(MorePaths.pathWithUnixSeparators(path), Charsets.UTF_8)
        .toString()
        .substring(0, 10);
    final String safeFileName = Flavor.replaceInvalidCharacters(path.getFileName().toString());
    return InternalFlavor.of(fileFlavorPrefix + safeFileName + "-" + hash);
  }

  public static Optional<Either<SourcePath, Pair<SourcePath, String>>> extractSourcePath(
      ImmutableBiMap<Flavor, Either<SourcePath, Pair<SourcePath, String>>> flavorsToSources,
      Stream<Flavor> flavors) {
    return flavors
        .filter(JsFlavors::isFileFlavor)
        .findFirst()
        .map(flavorsToSources::get);
  }

  public static boolean isFileFlavor(Flavor flavor) {
    return flavor.toString().startsWith(fileFlavorPrefix);
  }

  private JsFlavors() {}

  public static String bundleJobArgs(Set<Flavor> flavors) {
    final String ramFlag =
        flavors.contains(RAM_BUNDLE_INDEXED) ? "--indexed-rambundle" :
        flavors.contains(RAM_BUNDLE_FILES) ? "--files-rambundle" :
        null;
    final String releaseFlag = flavors.contains(RELEASE) ? "--release" : null;

    return Stream.of(ramFlag, releaseFlag)
        .filter(Objects::nonNull)
        .collect(Collectors.joining(" "));
  }
}
