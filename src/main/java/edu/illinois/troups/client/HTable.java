package edu.illinois.troups.client;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.util.Bytes;

import edu.illinois.troups.client.tm.RowGroupPolicy;
import edu.illinois.troups.client.tm.Transaction;

public class HTable implements Closeable {

  final org.apache.hadoop.hbase.client.HTable hTable;
  final RowGroupPolicy groupPolicy;

  public HTable(Configuration conf, byte[] tableName) throws IOException {
    this.hTable = new org.apache.hadoop.hbase.client.HTable(conf, tableName);
    this.groupPolicy = RowGroupPolicy.newInstance(hTable);
  }

  public HTable(Configuration conf, String tableName) throws IOException {
    this(conf, Bytes.toBytes(tableName));
  }

  public Result get(Transaction ta, Get get) throws IOException {
    org.apache.hadoop.hbase.client.Get hGet = ta.enlistGet(hTable, groupPolicy,
        get.getRow());
    for (Entry<byte[], ? extends Iterable<byte[]>> entry : get.getFamilyMap()
        .entrySet())
      for (byte[] column : entry.getValue())
        hGet.addColumn(entry.getKey(), column);
    // convert result into non-versioned result
    org.apache.hadoop.hbase.client.Result result = hTable.get(hGet);
    return new Result(result.getNoVersionMap());
  }

  public void put(Transaction ta, Put put) throws IOException {
    org.apache.hadoop.hbase.client.Put hPut = ta.enlistPut(hTable, groupPolicy,
        put.getRow());
    for (List<KeyValue> kvl : put.getFamilyMap().values())
      for (KeyValue kv : kvl)
        hPut.add(kv.getFamily(), kv.getQualifier(), kv.getValue());
    hTable.put(hPut);
  }

  @Override
  public void close() throws IOException {
    hTable.close();
  }
}
