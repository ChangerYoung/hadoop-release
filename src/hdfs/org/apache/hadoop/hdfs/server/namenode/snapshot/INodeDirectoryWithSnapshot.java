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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectoryWithQuota;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.server.namenode.snapshot.diff.Diff;
import org.apache.hadoop.hdfs.server.namenode.snapshot.diff.Diff.Container;
import org.apache.hadoop.hdfs.server.namenode.snapshot.diff.Diff.UndoInfo;
import org.apache.hadoop.hdfs.util.ReadOnlyList;

/**
 * The directory with snapshots. It maintains a list of snapshot diffs for
 * storing snapshot data. When there are modifications to the directory, the old
 * data is stored in the latest snapshot, if there is any.
 */
public class INodeDirectoryWithSnapshot extends INodeDirectoryWithQuota {
  /**
   * The difference between the current state and a previous snapshot
   * of the children list of an INodeDirectory.
   */
  static class ChildrenDiff extends Diff<byte[], INode> {
    ChildrenDiff() {}
  }

  /**
   * The difference between two snapshots. {@link INodeDirectoryWithSnapshot}
   * maintains a list of snapshot diffs,
   * <pre>
   *   d_1 -> d_2 -> ... -> d_n -> null,
   * </pre>
   * where -> denotes the {@link SnapshotDiff#posteriorDiff} reference. The
   * current directory state is stored in the field of {@link INodeDirectory}.
   * The snapshot state can be obtained by applying the diffs one-by-one in
   * reversed chronological order.  Let s_1, s_2, ..., s_n be the corresponding
   * snapshots.  Then,
   * <pre>
   *   s_n                     = (current state) - d_n;
   *   s_{n-1} = s_n - d_{n-1} = (current state) - d_n - d_{n-1};
   *   ...
   *   s_k     = s_{k+1} - d_k = (current state) - d_n - d_{n-1} - ... - d_k.
   * </pre>
   */
  class SnapshotDiff implements Comparable<Snapshot> {
    /** The snapshot will be obtained after this diff is applied. */
    final Snapshot snapshot;
    /** The size of the children list at snapshot creation time. */
    final int childrenSize;
    /**
     * Posterior diff is the diff happened after this diff.
     * The posterior diff should be first applied to obtain the posterior
     * snapshot and then apply this diff in order to obtain this snapshot.
     * If the posterior diff is null, the posterior state is the current state. 
     */
    private SnapshotDiff posteriorDiff;
    /** The children list diff. */
    private final ChildrenDiff diff;
    /** The snapshot inode data.  It is null when there is no change. */
    private INodeDirectory snapshotINode = null;

    private SnapshotDiff(Snapshot snapshot, INodeDirectory dir) {
      if (snapshot == null) {
        throw new NullPointerException("snapshot is null");
      }

      this.snapshot = snapshot;
      this.childrenSize = dir.getChildrenList(null).size();
      this.diff = new ChildrenDiff();
    }

    /** Compare diffs with snapshot ID. */
    @Override
    public int compareTo(final Snapshot that) {
      return Snapshot.ID_COMPARATOR.compare(this.snapshot, that);
    }
    
    /** Is the inode the root of the snapshot? */
    boolean isSnapshotRoot() {
      return snapshotINode == snapshot.getRoot();
    }

    /** Copy the INode state to the snapshot if it is not done already. */
    private void checkAndInitINode(INodeDirectory snapshotCopy) {
      if (snapshotINode == null) {
        if (snapshotCopy == null) {
          snapshotCopy = new INodeDirectory(INodeDirectoryWithSnapshot.this,
              false);
        }
        snapshotINode = snapshotCopy;
      }
    }

    /** @return the snapshot object of this diff. */
    Snapshot getSnapshot() {
      return snapshot;
    }

    private INodeDirectory getSnapshotINode() {
      // get from this diff, then the posterior diff and then the current inode
      for(SnapshotDiff d = this; ; d = d.posteriorDiff) {
        if (d.snapshotINode != null) {
          return d.snapshotINode;
        } else if (d.posteriorDiff == null) {
          return INodeDirectoryWithSnapshot.this;
        }
      }
    }

    /**
     * @return The children list of a directory in a snapshot.
     *         Since the snapshot is read-only, the logical view of the list is
     *         never changed although the internal data structure may mutate.
     */
    ReadOnlyList<INode> getChildrenList() {
      return new ReadOnlyList<INode>() {
        private List<INode> children = null;

        private List<INode> initChildren() {
          if (children == null) {
            final ChildrenDiff combined = new ChildrenDiff();
            for(SnapshotDiff d = SnapshotDiff.this; d != null; d = d.posteriorDiff) {
              combined.combinePosterior(d.diff, null);
            }
            children = combined.apply2Current(ReadOnlyList.Util.asList(
                INodeDirectoryWithSnapshot.this.getChildrenList(null)));
          }
          return children;
        }

        @Override
        public Iterator<INode> iterator() {
          return initChildren().iterator();
        }
    
        @Override
        public boolean isEmpty() {
          return childrenSize == 0;
        }
    
        @Override
        public int size() {
          return childrenSize;
        }
    
        @Override
        public INode get(int i) {
          return initChildren().get(i);
        }
      };
    }

    /** @return the child with the given name. */
    INode getChild(byte[] name, boolean checkPosterior) {
      for(SnapshotDiff d = this; ; d = d.posteriorDiff) {
        final Container<INode> returned = d.diff.accessPrevious(name);
        if (returned != null) {
          // the diff is able to determine the inode
          return returned.getElement(); 
        } else if (!checkPosterior) {
          // Since checkPosterior is false, return null, i.e. not found.   
          return null;
        } else if (d.posteriorDiff == null) {
          // no more posterior diff, get from current inode.
          return INodeDirectoryWithSnapshot.this.getChild(name, null);
        }
      }
    }
    
    @Override
    public String toString() {
      return "\n  " + snapshot + " (-> "
          + (posteriorDiff == null? null: posteriorDiff.snapshot)
          + ") childrenSize=" + childrenSize + ", " + diff;
    }

    ChildrenDiff getDiff() {
      return diff;
    }
  }
  
  /** Create an {@link INodeDirectoryWithSnapshot} with the given snapshot.*/
  public static INodeDirectoryWithSnapshot newInstance(INodeDirectory dir,
      Snapshot latest) {
    final INodeDirectoryWithSnapshot withSnapshot
        = new INodeDirectoryWithSnapshot(dir, true, null);
    if (latest != null) {
      // add a diff for the latest snapshot
      withSnapshot.addSnapshotDiff(latest, dir, false);
    }
    return withSnapshot;
  }

  /** Diff list sorted by snapshot IDs, i.e. in chronological order. */
  private final List<SnapshotDiff> diffs;

  INodeDirectoryWithSnapshot(INodeDirectory that, boolean adopt,
      List<SnapshotDiff> diffs) {
    super(that, adopt, that.getNsQuota(), that.getDsQuota());
    this.diffs = diffs != null? diffs: new ArrayList<SnapshotDiff>();
  }
  
  /**
   * Delete the snapshot with the given name. The synchronization of the diff
   * list will be done outside.
   * 
   * If the diff to remove is not the first one in the diff list, we need to 
   * combine the diff with its previous one:
   * 
   * @param snapshot The snapshot to be deleted
   * @param collectedBlocks Used to collect information for blocksMap update
   * @return The SnapshotDiff containing the deleted snapshot. 
   *         Null if the snapshot with the given name does not exist. 
   */
  SnapshotDiff deleteSnapshotDiff(Snapshot snapshot,
      final BlocksMapUpdateInfo collectedBlocks) {
    int snapshotIndex = Collections.binarySearch(diffs, snapshot);
    if (snapshotIndex == -1) {
      return null;
    } else {
      SnapshotDiff diffToRemove = null;
      diffToRemove = diffs.remove(snapshotIndex);
      if (snapshotIndex > 0) {
        // combine the to-be-removed diff with its previous diff
        SnapshotDiff previousDiff = diffs.get(snapshotIndex - 1);
        previousDiff.diff.combinePosterior(diffToRemove.diff,
            new Diff.Processor<INode>() {
          /** Collect blocks for deleted files. */
          @Override
          public void process(INode inode) {
            if (inode != null && inode instanceof INodeFile) {
              ((INodeFile) inode).collectSubtreeBlocksAndClear(collectedBlocks);
            }
          }
        });
          
        previousDiff.posteriorDiff = diffToRemove.posteriorDiff;
        diffToRemove.posteriorDiff = null;
      }
      return diffToRemove;
    }
  }

  /** Add a {@link SnapshotDiff} for the given snapshot and directory. */
  SnapshotDiff addSnapshotDiff(Snapshot snapshot, INodeDirectory dir,
      boolean isSnapshotCreation) {
    final SnapshotDiff last = getLastSnapshotDiff();
    final SnapshotDiff d = new SnapshotDiff(snapshot, dir); 

    if (isSnapshotCreation) {
      //for snapshot creation, snapshotINode is the same as the snapshot root
      d.snapshotINode = snapshot.getRoot();
    }
    diffs.add(d);
    if (last != null) {
      last.posteriorDiff = d;
    }
    return d;
  }

  SnapshotDiff getLastSnapshotDiff() {
    final int n = diffs.size();
    return n == 0? null: diffs.get(n - 1);
  }

  /** @return the last snapshot. */
  public Snapshot getLastSnapshot() {
    final SnapshotDiff last = getLastSnapshotDiff();
    return last == null? null: last.getSnapshot();
  }

  /**
   * Check if the latest snapshot diff exists.  If not, add it.
   * @return the latest snapshot diff, which is never null.
   */
  private SnapshotDiff checkAndAddLatestSnapshotDiff(Snapshot latest) {
    final SnapshotDiff last = getLastSnapshotDiff();
    return last != null && last.snapshot.equals(latest)? last
        : addSnapshotDiff(latest, this, false);
  }
  
  /**
   * Check if the latest {@link ChildrenDiff} exists.  If not, add it.
   * @return the latest {@link ChildrenDiff}, which is never null.
   */
  ChildrenDiff checkAndAddLatestDiff(Snapshot latest) {
    return checkAndAddLatestSnapshotDiff(latest).diff;
  }

  /**
   * @return {@link #snapshots}
   */
  List<SnapshotDiff> getSnapshotDiffs() {
    return diffs;
  }

  /**
   * @return the diff corresponding to the given snapshot.
   *         When the diff is null, it means that the current state and
   *         the corresponding snapshot state are the same. 
   */
  SnapshotDiff getSnapshotDiff(Snapshot snapshot) {
    if (snapshot == null) {
      // snapshot == null means the current state, therefore, return null.
      return null;
    }
    final int i = Collections.binarySearch(diffs, snapshot);
    if (i >= 0) {
      // exact match
      return diffs.get(i);
    } else {
      // Exact match not found means that there were no changes between
      // given snapshot and the next state so that the diff for the given
      // snapshot was not recorded.  Thus, return the next state.
      final int j = -i - 1;
      return j < diffs.size()? diffs.get(j): null;
    }
  }

  @Override
  public INodeDirectoryWithSnapshot recordModification(Snapshot latest) {
    saveSelf2Snapshot(latest, null);
    return this;
  }

  /** Save the snapshot copy to the latest snapshot. */
  public void saveSelf2Snapshot(Snapshot latest, INodeDirectory snapshotCopy) {
    if (latest != null) {
      checkAndAddLatestSnapshotDiff(latest).checkAndInitINode(snapshotCopy);
    }
  }

  @Override
  public INode saveChild2Snapshot(INode child, Snapshot latest) {
    if (child.isDirectory()) {
      throw new IllegalStateException("child is a directory, child=" + child);
    }
    if (latest == null) {
      return child;
    }

    final SnapshotDiff diff = checkAndAddLatestSnapshotDiff(latest);
    if (diff.getChild(child.getLocalNameBytes(), false) != null) {
      // it was already saved in the latest snapshot earlier.  
      return child;
    }

    final Pair<? extends INode, ? extends INode> p = child.createSnapshotCopy();
    if (p.left != p.right) {
      final UndoInfo<INode> undoIndo = diff.diff.modify(p.right, p.left);
      if (undoIndo.getTrashedElement() != null
          && p.left instanceof FileWithSnapshot) {
        // also should remove oldinode from the circular list
        FileWithSnapshot newNodeWithLink = (FileWithSnapshot) p.left;
        FileWithSnapshot oldNodeWithLink = (FileWithSnapshot) p.right;
        newNodeWithLink.setNext(oldNodeWithLink.getNext());
        oldNodeWithLink.setNext(null);
      }
    }
    return p.left;
  }

  @Override
  public boolean addChild(INode inode, boolean inheritPermission,
      Snapshot latest) {
    ChildrenDiff diff = null;
    Integer undoInfo = null;
    if (latest != null) {
      diff = checkAndAddLatestDiff(latest);
      undoInfo = diff.create(inode);
    }
    final boolean added = super.addChild(inode, inheritPermission, null);
    if (!added && undoInfo != null) {
      diff.undoCreate(inode, undoInfo);
    }
    return added; 
  }

  @Override
  public INode removeChild(INode child, Snapshot latest) {
    ChildrenDiff diff = null;
    UndoInfo<INode> undoInfo = null;
    if (latest != null) {
      diff = checkAndAddLatestDiff(latest);
      undoInfo = diff.delete(child);
    }
    final INode removed = super.removeChild(child, null);
    if (removed == null && undoInfo != null) {
      diff.undoDelete(child, undoInfo);
    }
    if (undoInfo != null) {
      if (removed == null) {
        //remove failed, undo
        diff.undoDelete(child, undoInfo);
      } else {
        //clean up the previously created file, if there is any.
        final INode trashed = undoInfo.getTrashedElement();
        if (trashed != null && trashed instanceof FileWithSnapshot) {
          ((FileWithSnapshot)trashed).removeSelf();
        }
      }
    }
    return removed;
  }

  @Override
  public ReadOnlyList<INode> getChildrenList(Snapshot snapshot) {
    final SnapshotDiff diff = getSnapshotDiff(snapshot);
    return diff != null? diff.getChildrenList(): super.getChildrenList(null);
  }

  @Override
  public INode getChild(byte[] name, Snapshot snapshot) {
    final SnapshotDiff diff = getSnapshotDiff(snapshot);
    return diff != null? diff.getChild(name, true): super.getChild(name, null);
  }

  @Override
  public String getUserName(Snapshot snapshot) {
    final SnapshotDiff diff = getSnapshotDiff(snapshot);
    return diff != null? diff.getSnapshotINode().getUserName()
        : super.getUserName(null);
  }

  @Override
  public String getGroupName(Snapshot snapshot) {
    final SnapshotDiff diff = getSnapshotDiff(snapshot);
    return diff != null? diff.getSnapshotINode().getGroupName()
        : super.getGroupName(null);
  }

  @Override
  public FsPermission getFsPermission(Snapshot snapshot) {
    final SnapshotDiff diff = getSnapshotDiff(snapshot);
    return diff != null? diff.getSnapshotINode().getFsPermission()
        : super.getFsPermission(null);
  }

  @Override
  public long getAccessTime(Snapshot snapshot) {
    final SnapshotDiff diff = getSnapshotDiff(snapshot);
    return diff != null? diff.getSnapshotINode().getAccessTime()
        : super.getAccessTime(null);
  }

  @Override
  public long getModificationTime(Snapshot snapshot) {
    final SnapshotDiff diff = getSnapshotDiff(snapshot);
    return diff != null? diff.getSnapshotINode().getModificationTime()
        : super.getModificationTime(null);
  }
  
  @Override
  public String toString() {
    return super.toString() + ", diffs=" + getSnapshotDiffs();
  }
}