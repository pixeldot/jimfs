/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.jimfs;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.locks.ReadWriteLock;
import org.jspecify.annotations.Nullable;

/**
 * A file object, containing both the file's metadata and content.
 *
 * @author Colin Decker
 */
public abstract class File {

  private final int id;

  private int links;

  private FileTime creationTime;
  private FileTime lastAccessTime;
  private FileTime lastModifiedTime;

  // null when only the basic view is used (default)
  private @Nullable Table<String, String, Object> attributes;

  File(int id, FileTime creationTime) {
    this.id = id;

    this.creationTime = creationTime;
    this.lastAccessTime = creationTime;
    this.lastModifiedTime = creationTime;
  }

  /** Returns the ID of this file. */
  public int id() {
    return id;
  }

  /**
   * Returns the size, in bytes, of this file's content. Directories and symbolic links have a size
   * of 0.
   */
  public long size() {
    return 0;
  }

  /** Returns whether or not this file is a directory. */
  public final boolean isDirectory() {
    return this instanceof Directory;
  }

  /** Returns whether or not this file is a regular file. */
  public final boolean isRegularFile() {
    return this instanceof RegularFile;
  }

  /** Returns whether or not this file is a symbolic link. */
  public final boolean isSymbolicLink() {
    return this instanceof SymbolicLink;
  }

  /**
   * Creates a new file of the same type as this file with the given ID and creation time. Does not
   * copy the content of this file unless the cost of copying the content is minimal. This is
   * because this method is called with a hold on the file system's lock.
   */
  abstract File copyWithoutContent(int id, FileTime creationTime);

  /**
   * Copies the content of this file to the given file. The given file must be the same type of file
   * as this file and should have no content.
   *
   * <p>This method is used for copying the content of a file after copying the file itself. Does
   * nothing by default.
   */
  void copyContentTo(File file) throws IOException {}

  /**
   * Returns the read-write lock for this file's content, or {@code null} if there is no content
   * lock.
   */
  @Nullable ReadWriteLock contentLock() {
    return null;
  }

  /** Called when a stream or channel to this file is opened. */
  void opened() {}

  /**
   * Called when a stream or channel to this file is closed. If there are no more streams or
   * channels open to the file and it has been deleted, its contents may be deleted.
   */
  void closed() {}

  /**
   * Called when (a single link to) this file is deleted. There may be links remaining. Does nothing
   * by default.
   */
  void deleted() {}

  /** Returns whether or not this file is a root directory of the file system. */
  final boolean isRootDirectory() {
    // only root directories have their parent link pointing to themselves
    return isDirectory() && equals(((Directory) this).parent());
  }

  /** Returns the current count of links to this file. */
  public final synchronized int links() {
    return links;
  }

  /**
   * Called when this file has been linked in a directory. The given entry is the new directory
   * entry that links to this file.
   */
  void linked(DirectoryEntry entry) {
    checkNotNull(entry);
  }

  /** Called when this file has been unlinked from a directory, either for a move or delete. */
  void unlinked() {}

  /** Increments the link count for this file. */
  final synchronized void incrementLinkCount() {
    links++;
  }

  /** Decrements the link count for this file. */
  final synchronized void decrementLinkCount() {
    links--;
  }

  /** Gets the creation time of the file. */
  public final synchronized FileTime getCreationTime() {
    return creationTime;
  }

  /** Gets the last access time of the file. */
  public final synchronized FileTime getLastAccessTime() {
    return lastAccessTime;
  }

  /** Gets the last modified time of the file. */
  public final synchronized FileTime getLastModifiedTime() {
    return lastModifiedTime;
  }

  /** Sets the creation time of the file. */
  final synchronized void setCreationTime(FileTime creationTime) {
    this.creationTime = creationTime;
  }

  /** Sets the last access time of the file. */
  final synchronized void setLastAccessTime(FileTime lastAccessTime) {
    this.lastAccessTime = lastAccessTime;
  }

  /** Sets the last modified time of the file. */
  final synchronized void setLastModifiedTime(FileTime lastModifiedTime) {
    this.lastModifiedTime = lastModifiedTime;
  }

  /**
   * Returns the names of the attributes contained in the given attribute view in the file's
   * attributes table.
   */
  public final synchronized ImmutableSet<String> getAttributeNames(String view) {
    if (attributes == null) {
      return ImmutableSet.of();
    }
    return ImmutableSet.copyOf(attributes.row(view).keySet());
  }

  /** Returns the attribute keys contained in the attributes map for the file. */
  @VisibleForTesting
  final synchronized ImmutableSet<String> getAttributeKeys() {
    if (attributes == null) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (Table.Cell<String, String, Object> cell : attributes.cellSet()) {
      builder.add(cell.getRowKey() + ':' + cell.getColumnKey());
    }
    return builder.build();
  }

  /** Gets the value of the given attribute in the given view. */
  public final synchronized @Nullable Object getAttribute(String view, String attribute) {
    if (attributes == null) {
      return null;
    }
    return attributes.get(view, attribute);
  }

  /** Sets the given attribute in the given view to the given value. */
  public final synchronized void setAttribute(String view, String attribute, Object value) {
    if (attributes == null) {
      attributes = HashBasedTable.create();
    }
    attributes.put(view, attribute, value);
  }

  /** Deletes the given attribute from the given view. */
  public final synchronized void deleteAttribute(String view, String attribute) {
    if (attributes != null) {
      attributes.remove(view, attribute);
    }
  }

  /** Copies basic attributes (file times) from this file to the given file. */
  final synchronized void copyBasicAttributes(File target) {
    target.setFileTimes(creationTime, lastModifiedTime, lastAccessTime);
  }

  private synchronized void setFileTimes(
      FileTime creationTime, FileTime lastModifiedTime, FileTime lastAccessTime) {
    this.creationTime = creationTime;
    this.lastModifiedTime = lastModifiedTime;
    this.lastAccessTime = lastAccessTime;
  }

  /** Copies the attributes from this file to the given file. */
  final synchronized void copyAttributes(File target) {
    copyBasicAttributes(target);
    target.putAll(attributes);
  }

  private synchronized void putAll(@Nullable Table<String, String, Object> attributes) {
    if (attributes != null && this.attributes != attributes) {
      if (this.attributes == null) {
        this.attributes = HashBasedTable.create();
      }
      this.attributes.putAll(attributes);
    }
  }

  @Override
  public final String toString() {
    return MoreObjects.toStringHelper(this).add("id", id()).toString();
  }
}
