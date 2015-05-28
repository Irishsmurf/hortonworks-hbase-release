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

package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.hbase.chaos.factories.MonkeyFactory;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.math.RandomUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.testclassification.IntegrationTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.HBaseFsck;
import org.apache.hadoop.hbase.util.IdLock;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.util.hbck.HbckTestingUtil;
import org.apache.hadoop.util.ToolRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 *
 * Integration test that verifies Procedure V2. DDL operations should go through
 * (rollforward or rollback) when primary master is killed by ChaosMonkey (default MASTER_KILLING)<br/>
 *
 * Multiple Worker threads are started to randomly do the following Actions:<br/>
 * Actions generating and populating tables:
 * <ul>
 *     <li>CreateTableAction</li>
 *     <li>DisableTableAction</li>
 *     <li>EnableTableAction</li>
 *     <li>DeleteTableAction</li>
 *     <li>AddRowAction</li>
 * </ul>
 * Actions performing DDL operations:
 * <ul>
 *     <li>AddColumnFamilyAction</li>
 *     <li>AlterColumnFamilyVersionsAction</li>
 *     <li>AlterColumnFamilyEncodingAction</li>
 *     <li>DeleteColumnFamilyAction</li>
 * </ul>
 *
 * The threads runs for a period of time (default 20 minutes). Then stop the threads and do verification.
 * <ol>
 *     <li>No Actions throw Exceptions.</li>
 *     <li>hbck throw no errors. Regions are consistent.</li>
 * </ol>
 */

@Category(IntegrationTests.class) public class IntegrationTestDDLMasterFailover
    extends IntegrationTestBase {

  private static final Log LOG = LogFactory.getLog(IntegrationTestDDLMasterFailover.class);

  private static final int SERVER_COUNT = 1; // number of slaves for the smallest cluster

  protected static final long DEFAULT_RUN_TIME = 20 * 60 * 1000;

  protected static final int DEFAULT_NUM_THREADS = 20;

  protected static final int DEFAULT_NUM_REGIONS = 50; // number of regions in pre-split tables

  protected HBaseCluster cluster;

  protected Connection connection;

  protected IdLock idLock = new IdLock();

  /**
   * A soft limit on how long we should run
   */
  protected static final String RUN_TIME_KEY = "hbase.%s.runtime";
  protected static final String NUM_THREADS_KEY = "hbase.%s.numThreads";
  protected static final String NUM_REGIONS_KEY = "hbase.%s.numRegions";

  protected AtomicBoolean running = new AtomicBoolean(true);

  protected AtomicBoolean create_table = new AtomicBoolean(true);

  protected int numThreads, numRegions;

  ConcurrentHashMap<TableName, HTableDescriptor> enabledTables =
      new ConcurrentHashMap<TableName, HTableDescriptor>();

  ConcurrentHashMap<TableName, HTableDescriptor> disabledTables =
      new ConcurrentHashMap<TableName, HTableDescriptor>();

  ConcurrentHashMap<TableName, HTableDescriptor> deletedTables =
      new ConcurrentHashMap<TableName, HTableDescriptor>();

  @Override public void setUpCluster() throws Exception {
    util = getTestingUtil(getConf());
    LOG.debug("Initializing/checking cluster has " + SERVER_COUNT + " servers");
    util.initializeCluster(getMinServerCount());
    LOG.debug("Done initializing/checking cluster");
    cluster = util.getHBaseClusterInterface();
  }

  @Override public void cleanUpCluster() throws Exception {
    Admin admin = util.getHBaseAdmin();
    admin.disableTables("ittable-\\d+");
    admin.deleteTables("ittable-\\d+");
    Connection connection = getConnection();
    connection.close();
    super.cleanUpCluster();
  }

  protected int getMinServerCount() {
    return SERVER_COUNT;
  }

  protected synchronized void setConnection(Connection connection){
    this.connection = connection;
  }

  protected synchronized Connection getConnection(){
    if (this.connection == null) {
      try {
        Connection connection = ConnectionFactory.createConnection(getConf());
        setConnection(connection);
      } catch (IOException e) {
        LOG.fatal("Failed to establish connection.", e);
      }
    }
    return connection;
  }

  protected void verifyTables() throws  IOException{
    Connection connection = getConnection();
    Admin admin = connection.getAdmin();
    // iterating concurrent map
    for (TableName tableName : enabledTables.keySet()){
      Assert.assertTrue("Table: " + tableName + " in enabledTables is not enabled",
          admin.isTableEnabled(tableName));
    }
    for (TableName tableName : disabledTables.keySet()){
      Assert.assertTrue("Table: " + tableName + " in disabledTables is not disabled",
          admin.isTableDisabled(tableName));
    }
    for (TableName tableName : deletedTables.keySet()){
      Assert.assertFalse("Table: " + tableName + " in deletedTables is not deleted",
          admin.tableExists(tableName));
    }
    admin.close();
  }

  @Test public void testAsUnitTest() throws Exception {
    runTest();
  }

  @Override public int runTestFromCommandLine() throws Exception {
    int ret = runTest();
    return ret;
  }

  private abstract class MasterAction{
    Connection connection = getConnection();

    abstract void perform() throws IOException;
  }

  private abstract class TableAction extends  MasterAction{
    // TableAction has implemented selectTable() shared by multiple table Actions
    protected HTableDescriptor selectTable(ConcurrentHashMap<TableName, HTableDescriptor> tableMap)
    {
      // randomly select table from tableMap
      if (tableMap.isEmpty()){
        return null;
      }
      // synchronization to prevent removal from multiple threads
      synchronized (tableMap){
        ArrayList<TableName> tableList = new ArrayList<TableName>(tableMap.keySet());
        TableName randomKey = tableList.get(RandomUtils.nextInt(tableList.size()));
        HTableDescriptor randomHtd = tableMap.get(randomKey);
        // remove from tableMap
        tableMap.remove(randomKey);
        return randomHtd;
      }
    }
  }

  private class CreateTableAction extends TableAction {

    @Override void perform() throws IOException {
      Admin admin = connection.getAdmin();
      try {
        HTableDescriptor htd = createTableDesc();
        TableName tableName = htd.getTableName();
        if ( admin.tableExists(tableName)){
          return;
        }
        String numRegionKey = String.format(NUM_REGIONS_KEY, this.getClass().getSimpleName());
        numRegions = getConf().getInt(numRegionKey, DEFAULT_NUM_REGIONS);
        byte[] startKey = Bytes.toBytes("row-0000000000");
        byte[] endKey = Bytes.toBytes("row-" + Integer.MAX_VALUE);
        LOG.info("Creating table:" + htd);
        admin.createTable(htd, startKey, endKey, numRegions);
        Assert.assertTrue("Table: " + htd + " was not created", admin.tableExists(tableName));
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        enabledTables.put(tableName, freshTableDesc);
        LOG.info("Created table:" + freshTableDesc);
      } catch (Exception e){
        LOG.warn("Caught exception in action: " + this.getClass());
        throw e;
      } finally {
        admin.close();
      }
      verifyTables();
    }

    private HTableDescriptor createTableDesc() {
      String tableName = "ittable-" + String.format("%010d", RandomUtils.nextInt(Integer.MAX_VALUE));
      String familyName = "cf-" + Math.abs(RandomUtils.nextInt());
      HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(tableName));
      // add random column family
      htd.addFamily(new HColumnDescriptor(familyName));
      return htd;
    }
  }

  private class DisableTableAction extends TableAction {

    @Override void perform() throws IOException {

      HTableDescriptor selected = selectTable(enabledTables);
      if (selected == null) {
        return;
      }

      Admin admin = connection.getAdmin();
      try {
        TableName tableName = selected.getTableName();
        LOG.info("Disabling table :" + selected);
        admin.disableTable(tableName);
        Assert.assertTrue("Table: " + selected + " was not disabled",
            admin.isTableDisabled(tableName));
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        disabledTables.put(tableName, freshTableDesc);
        LOG.info("Disabled table :" + freshTableDesc);
      } catch (Exception e){
        LOG.warn("Caught exception in action: " + this.getClass());
        throw e;
      } finally {
        admin.close();
      }
      verifyTables();
    }
  }

  private class EnableTableAction extends TableAction {

    @Override void perform() throws IOException {

      HTableDescriptor selected = selectTable(disabledTables);
      if (selected == null ) {
        return;
      }

      Admin admin = connection.getAdmin();
      try {
        TableName tableName = selected.getTableName();
        LOG.info("Enabling table :" + selected);
        admin.enableTable(tableName);
        Assert.assertTrue("Table: " + selected + " was not enabled",
            admin.isTableEnabled(tableName));
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        enabledTables.put(tableName, freshTableDesc);
        LOG.info("Enabled table :" + freshTableDesc);
      } catch (Exception e){
        LOG.warn("Caught exception in action: " + this.getClass());
        throw e;
      } finally {
        admin.close();
      }
      verifyTables();
    }
  }

  private class DeleteTableAction extends TableAction {

    @Override void perform() throws IOException {

      HTableDescriptor selected = selectTable(disabledTables);
      if (selected == null) {
        return;
      }

      Admin admin = connection.getAdmin();
      try {
        TableName tableName = selected.getTableName();
        LOG.info("Deleting table :" + selected);
        admin.deleteTable(tableName);
        Assert.assertFalse("Table: " + selected + " was not deleted",
                admin.tableExists(tableName));
        deletedTables.put(tableName, selected);
        LOG.info("Deleted table :" + selected);
      } catch (Exception e){
        LOG.warn("Caught exception in action: " + this.getClass());
        throw e;
      } finally {
        admin.close();
      }
      verifyTables();
    }
  }


  private abstract class ColumnAction extends TableAction{
    // ColumnAction has implemented selectFamily() shared by multiple family Actions
    protected HColumnDescriptor selectFamily(HTableDescriptor htd) {
      if (htd == null) {
        return null;
      }
      HColumnDescriptor[] families = htd.getColumnFamilies();
      if (families.length == 0){
        LOG.info("No column families in table: " + htd);
        return null;
      }
      HColumnDescriptor randomCfd = families[RandomUtils.nextInt(families.length)];
      return randomCfd;
    }
  }

  private class AddColumnFamilyAction extends ColumnAction {

    @Override void perform() throws IOException {
      HTableDescriptor selected = selectTable(disabledTables);
      if (selected == null) {
        return;
      }

      Admin admin = connection.getAdmin();
      try {
        HColumnDescriptor cfd = createFamilyDesc();
        if (selected.hasFamily(cfd.getName())){
          LOG.info(new String(cfd.getName()) + " already exists in table " + selected.getTableName());
          return;
        }
        TableName tableName = selected.getTableName();
        LOG.info("Adding column family: " + cfd + " to table: " + tableName);
        admin.addColumn(tableName, cfd);
        // assertion
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        Assert.assertTrue("Column family: " + cfd + " was not added",
            freshTableDesc.hasFamily(cfd.getName()));
        LOG.info("Added column family: " + cfd + " to table: " + tableName);
        disabledTables.put(tableName, freshTableDesc);
      } catch (Exception e){
        LOG.warn("Caught exception in action: " + this.getClass());
        // TODO BUG-37526
        // loose restriction for InvalidFamilyOperationException thrown in async operations before HBASE-13415 completes
        // when failover happens, multiple procids may be created from the same request
        // when 1 procedure succeeds, the others would complain about family already exists
        if (e instanceof InvalidFamilyOperationException) {
          LOG.warn("Caught InvalidFamilyOperationException in action: " + this.getClass());
          e.printStackTrace();
        } else {
          throw e;
        }
      } finally {
        admin.close();
      }
      verifyTables();
    }

    private HColumnDescriptor createFamilyDesc() {
      String familyName = "cf-" + String.format("%010d", RandomUtils.nextInt(Integer.MAX_VALUE));
      HColumnDescriptor cfd = new HColumnDescriptor(familyName);
      return cfd;
    }
  }

  private class AlterFamilyVersionsAction extends ColumnAction {

    @Override void perform() throws IOException {
      HTableDescriptor selected = selectTable(disabledTables);
      if (selected == null) {
        return;
      }
      HColumnDescriptor columnDesc = selectFamily(selected);
      if (columnDesc == null){
        return;
      }

      Admin admin = connection.getAdmin();
      int versions = RandomUtils.nextInt(10) + 3;
      try {
        TableName tableName = selected.getTableName();
        LOG.info("Altering versions of column family: " + columnDesc + " to: " + versions +
            " in table: " + tableName);
        columnDesc.setMinVersions(versions);
        columnDesc.setMaxVersions(versions);
        admin.modifyTable(tableName, selected);
        // assertion
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        HColumnDescriptor freshColumnDesc = freshTableDesc.getFamily(columnDesc.getName());
        Assert.assertEquals("Column family: " + columnDesc + " was not altered",
            freshColumnDesc.getMaxVersions(), versions);
        Assert.assertEquals("Column family: " + freshColumnDesc + " was not altered",
            freshColumnDesc.getMinVersions(), versions);
        LOG.info("Altered versions of column family: " + columnDesc + " to: " + versions +
            " in table: " + tableName);
        disabledTables.put(tableName, freshTableDesc);
      } catch (Exception e) {
        LOG.warn("Caught exception in action: " + this.getClass());
        throw e;
      } finally {
        admin.close();
      }
      verifyTables();
    }
  }

  private class AlterFamilyEncodingAction extends ColumnAction {

    @Override void perform() throws IOException {
      HTableDescriptor selected = selectTable(disabledTables);
      if (selected == null) {
        return;
      }
      HColumnDescriptor columnDesc = selectFamily(selected);
      if (columnDesc == null){
        return;
      }

      Admin admin = connection.getAdmin();
      try {
        TableName tableName = selected.getTableName();
        // possible DataBlockEncoding ids
        int[] possibleIds = {0, 2, 3, 4, 6};
        short id = (short) possibleIds[RandomUtils.nextInt(possibleIds.length)];
        LOG.info("Altering encoding of column family: " + columnDesc + " to: " + id +
            " in table: " + tableName);
        columnDesc.setDataBlockEncoding(DataBlockEncoding.getEncodingById(id));
        admin.modifyTable(tableName, selected);
        // assertion
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        HColumnDescriptor freshColumnDesc = freshTableDesc.getFamily(columnDesc.getName());
        Assert.assertEquals("Encoding of column family: " + columnDesc + " was not altered",
            freshColumnDesc.getDataBlockEncoding().getId(), id);
        LOG.info("Altered encoding of column family: " + freshColumnDesc + " to: " + id +
            " in table: " + tableName);
        disabledTables.put(tableName, freshTableDesc);
      } catch (Exception e) {
        LOG.warn("Caught exception in action: " + this.getClass());
        throw e;
      } finally {
        admin.close();
      }
      verifyTables();
    }
  }

  private class DeleteColumnFamilyAction extends ColumnAction {

    @Override void perform() throws IOException {
      HTableDescriptor selected = selectTable(disabledTables);
      HColumnDescriptor cfd = selectFamily(selected);
      if (selected == null || cfd == null) {
        return;
      }

      Admin admin = connection.getAdmin();
      try {
        if (selected.getColumnFamilies().length < 2) {
          LOG.info("No enough column families to delete in table " + selected.getTableName());
          return;
        }
        TableName tableName = selected.getTableName();
        LOG.info("Deleting column family: " + cfd + " from table: " + tableName);
        admin.deleteColumn(tableName, cfd.getName());
        // assertion
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        Assert.assertFalse("Column family: " + cfd + " was not added",
            freshTableDesc.hasFamily(cfd.getName()));
        LOG.info("Deleted column family: " + cfd + " from table: " + tableName);
        disabledTables.put(tableName, freshTableDesc);
      } catch (Exception e) {
        LOG.warn("Caught exception in action: " + this.getClass());
        // TODO BUG-37526
        // loose restriction for InvalidFamilyOperationException thrown in async operations before HBASE-13415 completes
        // when failover happens, multiple procids may be created from the same request
        // when 1 procedure succeeds, the others would complain about family not exists
        if (e instanceof InvalidFamilyOperationException) {
          LOG.warn("Caught InvalidFamilyOperationException in action: " + this.getClass());
          e.printStackTrace();
        } else {
          throw e;
        }
      } finally {
        admin.close();
      }
      verifyTables();
    }
  }

  private class AddRowAction extends ColumnAction {
    // populate tables for hbck
    @Override void perform() throws IOException {
      HTableDescriptor selected = selectTable(enabledTables);
      if (selected == null ) {
        return;
      }

      Admin admin = connection.getAdmin();
      TableName tableName = selected.getTableName();
      try (Table table = connection.getTable(tableName)){
        ArrayList<HRegionInfo> regionInfos = new ArrayList<HRegionInfo>(admin.getTableRegions(
            selected.getTableName()));
        int numRegions = regionInfos.size();
        // average number of rows to be added per action to each region
        int average_rows = 1;
        int numRows = average_rows * numRegions;
        LOG.info("Adding " + numRows + " rows to table: " + selected);
        for (int i = 0; i < numRows; i++){
          // nextInt(Integer.MAX_VALUE)) to return positive numbers only
          byte[] rowKey = Bytes.toBytes(
              "row-" + String.format("%010d", RandomUtils.nextInt(Integer.MAX_VALUE)));
          HColumnDescriptor cfd = selectFamily(selected);
          if (cfd == null){
            return;
          }
          byte[] family = cfd.getName();
          byte[] qualifier = Bytes.toBytes("col-" + RandomUtils.nextInt(Integer.MAX_VALUE) % 10);
          byte[] value = Bytes.toBytes("val-" + RandomStringUtils.randomAlphanumeric(10));
          Put put = new Put(rowKey);
          put.addColumn(family, qualifier, value);
          table.put(put);
        }
        HTableDescriptor freshTableDesc = admin.getTableDescriptor(tableName);
        enabledTables.put(tableName, freshTableDesc);
        LOG.info("Added " + numRows + " rows to table: " + selected);
      } catch (Exception e) {
        LOG.warn("Caught exception in action: " + this.getClass());
        throw e;
      } finally {
        admin.close();
      }
      verifyTables();
    }
  }

  private enum ACTION {
    CREATE_TABLE,
    DISABLE_TABLE,
    ENABLE_TABLE,
    DELETE_TABLE,
    ADD_COLUMNFAMILY,
    DELETE_COLUMNFAMILY,
    ALTER_FAMILYVERSIONS,
    ALTER_FAMILYENCODING,
    ADD_ROW
  }

  private class Worker extends Thread {

    private Exception savedException;

    private ACTION action;

    @Override public void run() {
      while (running.get()) {
        // select random action
        ACTION selectedAction = ACTION.values()[RandomUtils.nextInt() % ACTION.values().length];
        this.action = selectedAction;
        LOG.info("Performing Action: " + selectedAction);

        try {
          switch (selectedAction) {
          case CREATE_TABLE:
            // stop creating new tables in the later stage of the test to avoid too many empty tables
            if (create_table.get()) {
              new CreateTableAction().perform();
            }
          case DISABLE_TABLE:
            new DisableTableAction().perform();
          case ENABLE_TABLE:
            new EnableTableAction().perform();
          case DELETE_TABLE:
            // reduce probability of deleting table to 20%
            if (RandomUtils.nextInt(100) < 20) {
              new DeleteTableAction().perform();
            }
          case ADD_COLUMNFAMILY:
            new AddColumnFamilyAction().perform();
          case DELETE_COLUMNFAMILY:
            // reduce probability of deleting column family to 20%
            if (RandomUtils.nextInt(100) < 20) {
              new DeleteColumnFamilyAction().perform();
            }
          case ALTER_FAMILYVERSIONS:
            new AlterFamilyVersionsAction().perform();
          case ALTER_FAMILYENCODING:
            new AlterFamilyEncodingAction().perform();
          case ADD_ROW:
            new AddRowAction().perform();
          }
        } catch (Exception ex) {
          this.savedException = ex;
          return;
        }
      }
      LOG.info(this.getName() + " stopped");
    }

    public Exception getSavedException(){
      return this.savedException;
    }

    public ACTION getAction(){
      return this.action;
    }
  }

  private void checkException(List<Worker> workers){
    if(workers == null || workers.isEmpty())
      return;
    for (Worker worker : workers){
      Exception e = worker.getSavedException();
      if (e != null) {
        LOG.warn("Found exception in thread: " + worker.getName());
        e.printStackTrace();
      }
      Assert.assertNull("Action failed: " + worker.getAction() + " in thread: " + worker.getName(), e);
    }
  }

  private int runTest() throws Exception {
    LOG.info("Starting the test");

    String runtimeKey = String.format(RUN_TIME_KEY, this.getClass().getSimpleName());
    long runtime = util.getConfiguration().getLong(runtimeKey, DEFAULT_RUN_TIME);

    String numThreadKey = String.format(NUM_THREADS_KEY, this.getClass().getSimpleName());
    numThreads = util.getConfiguration().getInt(numThreadKey, DEFAULT_NUM_THREADS);

    ArrayList<Worker> workers = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      checkException(workers);
      Worker worker = new Worker();
      LOG.info("Launching worker thread " + worker.getName());
      workers.add(worker);
      worker.start();
    }

    Threads.sleep(runtime * 3 / 2);
    LOG.info("Runtime is up");
    running.set(false);
    Threads.sleep(runtime / 3);
    LOG.info("Stopping creating new tables");
    create_table.set(false);
    checkException(workers);

    for (Worker worker : workers) {
      worker.join();
    }
    LOG.info("All Worker threads stopped");

    // verify
    LOG.info("Verify actions of all threads succeeded");
    checkException(workers);
    LOG.info("Verify states of all tables");
    verifyTables();

    // RUN HBCK

    HBaseFsck hbck = null;
    try {
      LOG.info("Running hbck");
      hbck = HbckTestingUtil.doFsck(util.getConfiguration(), false);
      HbckTestingUtil.assertNoErrors(hbck);
      LOG.info("Finished hbck");
    } finally {
      if (hbck != null) {
        hbck.close();
      }
    }
     return 0;
  }

  @Override public TableName getTablename() {
    return null;
  }

  @Override protected Set<String> getColumnFamilies() {
    return null;
  }

  public static void main(String[] args) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    IntegrationTestingUtility.setUseDistributedCluster(conf);
    IntegrationTestDDLMasterFailover masterFailover = new IntegrationTestDDLMasterFailover();
    Connection connection = null;
    try {
      // Initialize connection once, then pass to Actions
      LOG.debug("Setting up connection ...");
      connection = ConnectionFactory.createConnection(conf);
      masterFailover.setConnection(connection);
      int ret = ToolRunner.run(conf, masterFailover, args);
      connection = masterFailover.getConnection();
      System.exit(ret);
    } catch (IOException e){
      LOG.fatal("Failed to establish connection. Aborting test ...", e);
    } finally {
      if (connection != null){
        connection.close();
      }
    }
  }
}