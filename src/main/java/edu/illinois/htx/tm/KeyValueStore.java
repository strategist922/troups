package edu.illinois.htx.tm;

import java.io.IOException;

public interface KeyValueStore<K extends Key> {

  /**
   * Deletes a specific version from this KeyValueStore. Operation is expected
   * to be idempotent.
   * 
   * @param key
   * @param version
   */
  void deleteVersion(K key, long version) throws IOException;

  /**
   * Deletes all versions older than the given version (inclusive). If operation
   * is not atomic, it must start with the oldest. Operation is expected to be
   * idempotent.
   * 
   * @param key
   * @param version
   */
  void deleteVersions(K key, long version) throws IOException;

}