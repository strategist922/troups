package edu.illinois.troups.tm;

import java.io.IOException;

import edu.illinois.troups.tm.Key;
import edu.illinois.troups.tm.KeyVersions;
import edu.illinois.troups.tm.TID;
import edu.illinois.troups.tm.TransactionAbortedException;

public interface TransactionOperationObserver<K extends Key> {

  void beforeGet(TID tid, Iterable<? extends K> keys)
      throws TransactionAbortedException, IOException;

  void failedGet(TID tid, Iterable<? extends K> keys, Throwable t)
      throws TransactionAbortedException, IOException;

  void afterGet(TID tid, int maxVersions, Iterable<? extends KeyVersions<K>> kvs)
      throws TransactionAbortedException, IOException;

  void beforePut(TID tid, Iterable<? extends K> keys)
      throws TransactionAbortedException, IOException;

  void failedPut(TID tid, Iterable<? extends K> keys, Throwable t)
      throws TransactionAbortedException, IOException;

  void afterPut(TID tid, Iterable<? extends K> keys)
      throws TransactionAbortedException, IOException;

}