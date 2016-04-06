/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.common.lang;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;

import java.io.File;
import java.io.IOException;

/**
 * ClassLoader that implements bundle jar feature, in which the application jar contains
 * its dependency jars inside.
 */
public class ProgramClassLoader extends DirectoryClassLoader {

  private final File dir;

  /**
   * Constructs an instance that load classes from the given directory for the given program type.
   * See {@link ProgramResources#getVisibleResources()} for details on system classes that
   * are visible to the returned ClassLoader.
   * <p/>
   * The URLs for class loading are:
   * <p/>
   * <pre>
   * [dir]
   * [dir]/*.jar
   * [dir]/lib/*.jar
   * </pre>
   */
  public static ProgramClassLoader create(CConfiguration cConf, File unpackedJarDir,
                                          ClassLoader unfilteredParentClassLoader) throws IOException {
    ClassLoader filteredParent = FilterClassLoader.create(unfilteredParentClassLoader);
    return new ProgramClassLoader(cConf, unpackedJarDir, filteredParent);
  }

  public ProgramClassLoader(CConfiguration cConf, File dir, ClassLoader parent) {
    super(dir, cConf.get(Constants.AppFabric.PROGRAM_EXTRA_CLASSPATH, ""), parent, "lib");
    this.dir = dir;
  }

  public File getDir() {
    return dir;
  }
}
