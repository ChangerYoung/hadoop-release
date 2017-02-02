/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import junit.framework.AssertionFailedError;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.ToolRunner;
import org.junit.Assert;
import org.junit.Test;

public class TestFsShell {

  @Test
  public void testConfWithInvalidFile() throws Throwable {
    String[] args = new String[1];
    args[0] = "--conf=invalidFile";
    Throwable th = null;
    try {
      FsShell.main(args);
    } catch (Exception e) {
      th = e;
    }

    if (!(th instanceof RuntimeException)) {
      throw new AssertionFailedError("Expected Runtime exception, got: " + th)
          .initCause(th);
    }
  }

  @Test
  public void testDFSWithInvalidCommmand() throws Throwable {
    Configuration conf = new Configuration();
    FsShell shell = new FsShell(conf);
    String[] args = new String[1];
    args[0] = "dfs -mkdirs";
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes);
    final PrintStream oldErr = System.err;
    try {
      System.setErr(out);
      ToolRunner.run(shell, args);
      String errorValue=new String(bytes.toString());
      Assert
      .assertTrue(
          "FSShell dfs command did not print the error " +
          "message when invalid command is passed",
          errorValue.contains("-mkdirs: Unknown command"));
      Assert
          .assertTrue(
              "FSShell dfs command did not print help " +
              "message when invalid command is passed",
          errorValue.contains("Usage: hadoop fs [generic options]"));
    } finally {
      IOUtils.closeStream(out);
      System.setErr(oldErr);
    }
  }
}
