package edu.illinois.htx.tm.mvto;

import java.util.Iterator;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import edu.illinois.htx.test.InMemoryTransactionLog;
import edu.illinois.htx.test.SequentialExecutorService;
import edu.illinois.htx.test.StringKey;
import edu.illinois.htx.test.StringKeyValueStore;
import edu.illinois.htx.test.StringKeyVersion;
import edu.illinois.htx.tm.TransactionAbortedException;

public class MVTOTransactionManagerTest {

  private MVTOTransactionManager<StringKey> tm;
  private StringKeyValueStore kvs;
  private SequentialExecutorService ses;
  private InMemoryTransactionLog log;

  @Before
  public void before() {
    kvs = new StringKeyValueStore();
    ses = new SequentialExecutorService();
    log = new InMemoryTransactionLog();
    tm = new MVTOTransactionManager<StringKey>(kvs, ses, log);
  }

  /**
   * scenario: transaction 0 has written version 0 to key x
   * 
   */
  @Test
  public void testWriteConflict() {
    // state in the data store
    StringKey key = new StringKey("x");
    long version = 0;
    kvs.writeVersion(key, version);

    tm.begin(1);
    tm.begin(2);

    // both transactions read the initial version
    Iterable<StringKeyVersion> versions = kvs.readVersions(key);
    tm.filterReads(1, versions);
    tm.filterReads(2, versions);

    kvs.writeVersion(key, 1);
    try {
      tm.checkWrite(1, key, false);
      Assert.fail("transaction 1 should have failed write check");
    } catch (TransactionAbortedException e) {
      // expected
    }

    kvs.writeVersion(key, 2);
    try {
      tm.checkWrite(2, key, false);
    } catch (TransactionAbortedException e) {
      e.printStackTrace();
      Assert.fail("tran 2 aborted unexpectedly");
    }

    ses.executedScheduledTasks();

    // at this point we expect only version 2 of x to be present
    versions = kvs.readVersions(key);
    Iterator<StringKeyVersion> it = versions.iterator();
    Assert.assertTrue(it.hasNext());
    Assert.assertEquals(new StringKeyVersion(key, 0), it.next());
    Assert.assertTrue(it.hasNext());
    Assert.assertEquals(new StringKeyVersion(key, 2), it.next());
    Assert.assertFalse(it.hasNext());
  }
}