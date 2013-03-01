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
package org.apache.hadoop.hdfs.server.namenode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSClient.DFSOutputStream;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.SnapshottableDirectoryStatus;
import org.apache.hadoop.hdfs.server.namenode.FSImage.NameNodeFile;
import org.apache.log4j.Level;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test FSImage save/load when Snapshot is supported
 */
public class TestFSImageWithSnapshot {
  {
    ((Log4JLogger)INode.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)NameNode.LOG).getLogger().setLevel(Level.ALL);
  }

  static final long seed = 0;
  static final short REPLICATION = 3;
  static final int BLOCKSIZE = 1024;
  static final long txid = 1;

  private final Path dir = new Path("/TestSnapshot");
  private static String testDir =
      System.getProperty("test.build.data", "build/test/data");
  
  Configuration conf;
  MiniDFSCluster cluster;
  FSNamesystem fsn;
  DistributedFileSystem hdfs;
  
  @Before
  public void setUp() throws Exception {
    conf = new Configuration();
    cluster = new MiniDFSCluster(conf, REPLICATION, true, null);
    cluster.waitActive();
    fsn = cluster.getNameNode().getNamesystem();
    hdfs = (DistributedFileSystem) cluster.getFileSystem();
  }

  @After
  public void tearDown() throws Exception {
    if (cluster != null) {
      cluster.shutdown();
    }
  }
  
  /**
   * Create a temp fsimage file for testing.
   * @param dir The directory where the fsimage file resides
   * @param imageTxId The transaction id of the fsimage
   * @return The file of the image file
   */
  private File getImageFile(String dir, long imageTxId) {
    return new File(dir, String.format("%s_%019d", NameNodeFile.IMAGE,
        imageTxId));
  }
  
  /** 
   * Create a temp file for dumping the fsdir
   * @param dir directory for the temp file
   * @param suffix suffix of of the temp file
   * @return the temp file
   */
  private File getDumpTreeFile(String dir, String suffix) {
    return new File(dir, String.format("dumpTree_%s", suffix));
  }
  
  /** 
   * Dump the fsdir tree to a temp file
   * @param fileSuffix suffix of the temp file for dumping
   * @return the temp file
   */
  private File dumpTree2File(String fileSuffix) throws IOException {
    File file = getDumpTreeFile(testDir, fileSuffix);
    SnapshotTestHelper.dumpTree2File(fsn.getFSDirectory(), file);
    return file;
  }
  
  /** Save the fsimage to a temp file */
  private File saveFSImageToTempFile() throws IOException {
    FSImageFormat.Saver saver = new FSImageFormat.Saver(
        fsn.getFSImage().namespaceID);
    File imageFile = getImageFile(testDir, txid);
    synchronized (fsn) {
      saver.save(imageFile);
    }
    return imageFile;
  }
  
  /** Load the fsimage from a temp file */
  private void loadFSImageFromTempFile(File imageFile) throws IOException {
    FSImageFormat.Loader loader = new FSImageFormat.Loader(fsn.getFSImage());
    synchronized (fsn) {
      synchronized (fsn.dir) {
        loader.load(imageFile);
        fsn.dir.updateCountForINodeWithQuota();
      }
    }
  }
  
  /**
   * Testing steps:
   * <pre>
   * 1. Creating/modifying directories/files while snapshots are being taken.
   * 2. Dump the FSDirectory tree of the namesystem.
   * 3. Save the namesystem to a temp file (FSImage saving).
   * 4. Restart the cluster and format the namesystem.
   * 5. Load the namesystem from the temp file (FSImage loading).
   * 6. Dump the FSDirectory again and compare the two dumped string.
   * </pre>
   */
  @Test
  public void testSaveLoadImage() throws Exception {
    int s = 0;
    // make changes to the namesystem
    hdfs.mkdirs(dir);
    SnapshotTestHelper.createSnapshot(hdfs, dir, "s" + ++s);
    Path sub1 = new Path(dir, "sub1");
    hdfs.mkdirs(sub1);
    hdfs.setPermission(sub1, new FsPermission((short)0777));
    Path sub11 = new Path(sub1, "sub11");
    hdfs.mkdirs(sub11);
    checkImage(s);

    hdfs.mkdirs(dir);
    SnapshotTestHelper.createSnapshot(hdfs, dir, "s" + ++s);
    //hdfs.createSnapshot(dir, "s" + ++s);
    Path sub1file1 = new Path(sub1, "sub1file1");
    Path sub1file2 = new Path(sub1, "sub1file2");
    DFSTestUtil.createFile(hdfs, sub1file1, BLOCKSIZE, REPLICATION, seed);
    DFSTestUtil.createFile(hdfs, sub1file2, BLOCKSIZE, REPLICATION, seed);
    checkImage(s);
    
    hdfs.createSnapshot(dir, "s" + ++s);
    Path sub2 = new Path(dir, "sub2");
    Path sub2file1 = new Path(sub2, "sub2file1");
    Path sub2file2 = new Path(sub2, "sub2file2");
    DFSTestUtil.createFile(hdfs, sub2file1, BLOCKSIZE, REPLICATION, seed);
    DFSTestUtil.createFile(hdfs, sub2file2, BLOCKSIZE, REPLICATION, seed);
    checkImage(s);

    hdfs.createSnapshot(dir, "s" + ++s);
    hdfs.setReplication(sub1file1, (short) (REPLICATION - 1));
    hdfs.delete(sub1file2, true);
    hdfs.setOwner(sub2, "dr.who", "unknown");
    hdfs.delete(sub2file2, true);
    checkImage(s);
  }

  void checkImage(int s) throws IOException {
    final String name = "s" + s;

    // dump the fsdir tree
    File fsnBefore = dumpTree2File(name + "_before");
    
    // save the namesystem to a temp file
    File imageFile = saveFSImageToTempFile();
    
    long numSdirBefore = fsn.getNumSnapshottableDirs();
    long numSnapshotBefore = fsn.getNumSnapshots();
    SnapshottableDirectoryStatus[] dirBefore = hdfs.getSnapshottableDirListing();

    // restart the cluster, and format the cluster
    hdfs.close();
    cluster.shutdown();

    cluster = new MiniDFSCluster(conf, REPLICATION, true, null);
    cluster.waitActive();
    fsn = cluster.getNameNode().getNamesystem();
    hdfs = (DistributedFileSystem) cluster.getFileSystem();
    
    // load the namesystem from the temp file
    loadFSImageFromTempFile(imageFile);

    // dump the fsdir tree again
    File fsnAfter = dumpTree2File(name + "_after");

    // compare two dumped tree
    SnapshotTestHelper.compareDumpedTreeInFile(fsnBefore, fsnAfter);

    long numSdirAfter = fsn.getNumSnapshottableDirs();
    long numSnapshotAfter = fsn.getNumSnapshots();
    SnapshottableDirectoryStatus[] dirAfter = hdfs.getSnapshottableDirListing();

    Assert.assertEquals(numSdirBefore, numSdirAfter);
    Assert.assertEquals(numSnapshotBefore, numSnapshotAfter);
    Assert.assertEquals(dirBefore.length, dirAfter.length);
    List<String> pathListBefore = new ArrayList<String>();
    for (SnapshottableDirectoryStatus sBefore : dirBefore) {
      pathListBefore.add(sBefore.getFullPath().toString());
    }
    for (SnapshottableDirectoryStatus sAfter : dirAfter) {
      Assert.assertTrue(pathListBefore
          .contains(sAfter.getFullPath().toString()));
    }
  }
  
  /** Write to a file without closing the output stream */
  private FSDataOutputStream writeFileWithoutClosing(Path file, int length)
      throws IOException {
    byte[] toWrite = new byte[length];
    Random random = new Random();
    random.nextBytes(toWrite);
    FSDataOutputStream out = hdfs.create(file);
    out.write(toWrite);
    return out;
  }
  
  /**
   * Test the fsimage saving/loading while writing to file.
   */
  @Test
  public void testSaveLoadImageWithWriting() throws Exception {
    Path sub1 = new Path(dir, "sub1");
    Path sub1file1 = new Path(sub1, "sub1file1");
    Path sub1file2 = new Path(sub1, "sub1file2");
    Path sub1file3 = new Path(sub1, "sub1file3");
    
    DFSTestUtil.createFile(hdfs, sub1file1, BLOCKSIZE, REPLICATION, seed);
    FSDataOutputStream out = writeFileWithoutClosing(sub1file2, BLOCKSIZE);
    out.sync();
    
    // create snapshot s0
    SnapshotTestHelper.createSnapshot(hdfs, dir, "s0");
    out.close();
    out = writeFileWithoutClosing(sub1file3, BLOCKSIZE);
    ((DFSOutputStream) out.getWrappedStream()).sync(true);
    
    // dump fsdir
    File fsnBefore = dumpTree2File("before");
    // save the namesystem to a temp file
    File imageFile = saveFSImageToTempFile();
    
    // load fsimage and compare
    // first restart the cluster, and format the cluster
    out.close();
    hdfs.close();
    cluster.shutdown();
    cluster = new MiniDFSCluster(conf, REPLICATION, true, null);
    cluster.waitActive();
    fsn = cluster.getNameNode().getNamesystem();
    hdfs = (DistributedFileSystem) cluster.getFileSystem();
    
    // then load the fsimage
    loadFSImageFromTempFile(imageFile);
    // dump the fsdir tree again
    File fsnAfter = dumpTree2File("after");
    
    // compare two dumped tree
    SnapshotTestHelper.compareDumpedTreeInFile(fsnBefore, fsnAfter);
  }
}