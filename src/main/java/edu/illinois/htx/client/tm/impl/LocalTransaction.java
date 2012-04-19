package edu.illinois.htx.client.tm.impl;

import java.io.IOException;

import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.util.Bytes;

import edu.illinois.htx.client.tm.Transaction;
import edu.illinois.htx.tm.TransactionAbortedException;
import edu.illinois.htx.tm.region.HRegionTransactionManager;
import edu.illinois.htx.tm.region.RTM;

public class LocalTransaction implements Transaction {

  private HTable table;
  private byte[] row;
  private long id;
  private boolean completed = false;

  LocalTransaction() {
    super();
  }

  @Override
  public long enlist(HTable table, byte[] row) throws IOException {
    if (completed)
      throw new IllegalStateException("Already completed");

    // if this is the first enlist -> begin transaction
    if (this.table == null) {
      RTM rtm = table.coprocessorProxy(RTM.class, row);
      this.id = rtm.begin();
      this.table = table;
      this.row = row;
    }
    // otherwise ensure this transaction remains local
    else {
      // check same table
      if (!Bytes.equals(this.table.getTableName(), table.getTableName()))
        throw new IllegalArgumentException(
            "Local transaction cannot span tables");
      // check same row group
      byte[] rootRow = HRegionTransactionManager.getSplitRow(table, row);
      if (!Bytes.equals(this.row, rootRow))
        throw new IllegalArgumentException(
            "Local transaction cannot span row groups");
    }
    return id;
  }

  @Override
  public void rollback() {
    if (table != null) {
      RTM rtm = table.coprocessorProxy(RTM.class, row);
      try {
        rtm.abort(id);
      } catch (IOException e) {
        throw new RuntimeException("Failed to rollback", e);
      }
    }
    completed = true;
  }

  @Override
  public void commit() throws TransactionAbortedException {
    if (table == null)
      throw new IllegalStateException("No data to commit");
    RTM rtm = table.coprocessorProxy(RTM.class, row);
    try {
      rtm.commit(id);
    } catch (IOException e) {
      throw new RuntimeException("Failed to commit", e);
    }
    completed = true;
  }

}