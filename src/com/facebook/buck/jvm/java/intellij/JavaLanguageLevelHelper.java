/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.infer.annotation.PropagatesNullable;

final class JavaLanguageLevelHelper {

  private JavaLanguageLevelHelper() {
  }

  /**
   * Ensures that source level has format "majorVersion.minorVersion".
   */
  public static String normalizeSourceLevel(String jdkVersion) {
    if (jdkVersion.length() == 1) {
      return "1." + jdkVersion;
    } else {
      return jdkVersion;
    }
  }

  public static String convertLanguageLevelToIjFormat(@PropagatesNullable String languageLevel) {
    if (languageLevel == null) {
      return null;
    }

    return "JDK_" + normalizeSourceLevel(languageLevel).replace('.', '_');
  }
}
