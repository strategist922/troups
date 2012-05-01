package edu.illinois.troups.perf;

import static org.apache.hadoop.hbase.util.Bytes.toBytes;

import java.io.IOException;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.util.Bytes;

import edu.illinois.troups.client.Get;
import edu.illinois.troups.client.HTable;
import edu.illinois.troups.client.Put;
import edu.illinois.troups.client.tm.Transaction;
import edu.illinois.troups.client.tm.TransactionManager;
import edu.illinois.troups.tm.TransactionAbortedException;
import edu.illinois.troups.tm.region.HRegionTransactionManager;

public class RandomRowTransactions {

  private static final byte[] tableName = toBytes("account");
  private static final byte[] familyName = toBytes("balance");
  private static final byte[] qualifierName = toBytes("main");
  private static final String logFamily = "log";

  private final HBaseAdmin admin;

  public RandomRowTransactions(HBaseAdmin admin) {
    this.admin = admin;
  }

  void start() throws Exception {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    desc.addFamily(new HColumnDescriptor(familyName));
    desc.addFamily(new HColumnDescriptor(logFamily));
    desc.addCoprocessor(HRegionTransactionManager.class.getName());
//    desc.setValue(Constants.TM_LOG_FAMILY_NAME, logFamily);
    try {
      admin.createTable(desc);
    } catch (TableExistsException e) {
      e.printStackTrace(System.out);
      // ignore
    }

    org.apache.hadoop.hbase.client.HTable t = new org.apache.hadoop.hbase.client.HTable(
        tableName);
    org.apache.hadoop.hbase.client.Put put = new org.apache.hadoop.hbase.client.Put(
        toBytes(1L));
    put.add(familyName, qualifierName, Bytes.toBytes(1));
    t.put(put);
    t.close();
  }

  void stop() throws Exception {
    admin.disableTable(tableName);
    admin.deleteTable(tableName);
  }

  void run() throws IOException {
    Configuration conf = admin.getConfiguration();
    HTable table = new HTable(conf, tableName);
    TransactionManager tm = TransactionManager.get(conf);
    Random rand = new Random();

    long gett = 0, putt = 0, tt = 0, begint = 0, committ = 0;
    int num = 1000;
    long now;
    long before, beforeOp, beforeCommit;
    int abortCount = 0;
    long failureCount = 0;
    for (int i = 0; i < num; i++) {
      long rowID = rand.nextLong();
      byte[] row = Bytes.toBytes(rowID);

      now = System.currentTimeMillis();
      before = now;
      Transaction ta = tm.begin();
      now = System.currentTimeMillis();
      begint += (now - before);
      try {
        beforeOp = now;
        Get get = new Get(row);
        get.addColumn(familyName, qualifierName);
        table.get(ta, get);
        now = System.currentTimeMillis();
        gett += (now - beforeOp);

        beforeOp = now;
        Put put = new Put(row);
        put.add(familyName, qualifierName, new byte[1024]);
        table.put(ta, put);
        now = System.currentTimeMillis();
        putt += (now - beforeOp);

        beforeCommit = now;
        ta.commit();
        now = System.currentTimeMillis();
        committ += (now - beforeCommit);

        tt += (now - before);
      } catch (TransactionAbortedException e) {
        abortCount++;
      } catch (Exception e) {
        e.printStackTrace(System.out);
        failureCount++;
        ta.rollback();
      }

      if (i % 100 == 0)
        System.out.println("100 times");
    }

    System.out.println("Average total: " + (tt / num));
    System.out.println("Average get: " + (gett / num));
    System.out.println("Average put: " + (putt / num));
    System.out.println("Average begin: " + (begint / num));
    System.out.println("Average commit: " + (committ / num));
    System.out.println("Count abort: " + abortCount);
    System.out.println("Count failure: " + failureCount);
  }

  public static void main(String[] args) throws Exception,
      ZooKeeperConnectionException {
    Configuration conf = HBaseConfiguration.create();
    HBaseAdmin admin = new HBaseAdmin(conf);
    RandomRowTransactions rr = new RandomRowTransactions(admin);
    rr.start();
    try {
      rr.run();
    } finally {
      rr.stop();
    }
  }
}