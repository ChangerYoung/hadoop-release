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
package org.apache.hadoop.hdfs.server.namenode.snapshot;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.server.namenode.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;

/**
 * Represent an {@link INodeFile} that is snapshotted.
 * Note that snapshot files are represented by {@link INodeFileSnapshot}.
 */
@InterfaceAudience.Private
public class INodeFileWithSnapshot extends INodeFile
    implements FileWithSnapshot {
  private final FileDiffList diffs;
  private boolean isCurrentFileDeleted = false;

  public INodeFileWithSnapshot(INodeFile f) {
    this(f, f instanceof FileWithSnapshot?
        ((FileWithSnapshot)f).getDiffs(): null);
  }

  public INodeFileWithSnapshot(INodeFile f, FileDiffList diffs) {
    super(f);
    this.diffs = diffs != null? diffs: new FileDiffList();
    this.diffs.setFactory(FileDiffFactory.INSTANCE);
  }

  @Override
  public INodeFileUnderConstructionWithSnapshot toUnderConstruction(
      final String clientName,
      final String clientMachine,
      final DatanodeDescriptor clientNode) {
    return new INodeFileUnderConstructionWithSnapshot(this,
        clientName, clientMachine, clientNode, getDiffs());
  }

  @Override
  public boolean isCurrentFileDeleted() {
    return isCurrentFileDeleted;
  }

  @Override
  public INodeFile getSnapshotINode(Snapshot snapshot) {
    return diffs.getSnapshotINode(snapshot, this);
  }

  @Override
  public INodeFileWithSnapshot recordModification(final Snapshot latest) {
    if (isInLatestSnapshot(latest)) {
      diffs.saveSelf2Snapshot(latest, this, null);
    }
    return this;
  }

  @Override
  public INodeFile asINodeFile() {
    return this;
  }

  @Override
  public short getBlockReplication() {
    return Util.getBlockReplication(this);
  }

  @Override
  public long computeFileSize(Snapshot snapshot) {
    final FileDiff diff = diffs.getDiff(snapshot);
    return diff != null? diff.fileSize
        : super.computeFileSize(null);
  }
  
  @Override
  public FileDiffList getDiffs() {
    return diffs;
  }

  @Override
  public int destroySubtreeAndCollectBlocks(final Snapshot snapshot,
      final BlocksMapUpdateInfo collectedBlocks) {
    if (snapshot == null) {
      isCurrentFileDeleted = true;
    } else {
      if (diffs.deleteSnapshotDiff(snapshot, this, collectedBlocks) == null) {
        //snapshot diff not found and nothing is deleted.
        return 0;
      }
    }

    Util.collectBlocksAndClear(this, collectedBlocks);
    return 1;
  }

  @Override
  public String toDetailString() {
    return super.toDetailString()
        + (isCurrentFileDeleted()? "(DELETED), ": ", ") + diffs;
  }
}
