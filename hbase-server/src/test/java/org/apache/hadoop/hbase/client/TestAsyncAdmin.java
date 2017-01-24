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
package org.apache.hadoop.hbase.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Class to test AsyncAdmin.
 */
@Category({LargeTests.class, ClientTests.class})
public class TestAsyncAdmin {

  private static final Log LOG = LogFactory.getLog(TestAdmin1.class);
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static byte [] FAMILY = Bytes.toBytes("testFamily");

  private static AsyncConnection ASYNC_CONN;
  private AsyncAdmin admin;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.getConfiguration().setInt("hbase.client.pause", 10);
    TEST_UTIL.getConfiguration().setInt("hbase.client.retries.number", 3);
    TEST_UTIL.getConfiguration().setInt("hbase.rpc.timeout", 1000);
    TEST_UTIL.startMiniCluster(1);
    ASYNC_CONN = ConnectionFactory.createAsyncConnection(TEST_UTIL.getConfiguration());
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    IOUtils.closeQuietly(ASYNC_CONN);
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    this.admin = ASYNC_CONN.getAdmin();
  }

  @Test
  public void testListTables() throws Exception {
    TableName t1 = TableName.valueOf("testListTables1");
    TableName t2 = TableName.valueOf("testListTables2");
    TableName t3 = TableName.valueOf("testListTables3");
    TableName[] tables = new TableName[] { t1, t2, t3 };
    for (int i = 0; i < tables.length; i++) {
      TEST_UTIL.createTable(tables[i], FAMILY);
    }

    HTableDescriptor[] tableDescs = admin.listTables().get();
    int size = tableDescs.length;
    assertTrue(size >= tables.length);
    for (int i = 0; i < tables.length && i < size; i++) {
      boolean found = false;
      for (int j = 0; j < tableDescs.length; j++) {
        if (tableDescs[j].getTableName().equals(tables[i])) {
          found = true;
          break;
        }
      }
      assertTrue("Not found: " + tables[i], found);
    }

    TableName[] tableNames = admin.listTableNames().get();
    size = tableNames.length;
    assertTrue(size >= tables.length);
    for (int i = 0; i < tables.length && i < size; i++) {
      boolean found = false;
      for (int j = 0; j < tableNames.length; j++) {
        if (tableNames[j].equals(tables[i])) {
          found = true;
          break;
        }
      }
      assertTrue("Not found: " + tables[i], found);
    }

    for (int i = 0; i < tables.length; i++) {
      TEST_UTIL.deleteTable(tables[i]);
    }
    tableDescs = admin.listTables().get();
    assertEquals(0, tableDescs.length);
    tableNames = admin.listTableNames().get();
    assertEquals(0, tableNames.length);

    tableDescs = admin.listTables((Pattern) null, true).get();
    assertTrue("Not found system tables", tableDescs.length > 0);
    tableNames = admin.listTableNames((Pattern) null, true).get();
    assertTrue("Not found system tables", tableNames.length > 0);
  }

  @Test
  public void testTableExist() throws Exception {
    final TableName table = TableName.valueOf("testTableExist");
    boolean exist;
    exist = admin.tableExists(table).get();
    assertEquals(false, exist);
    TEST_UTIL.createTable(table, FAMILY);
    exist = admin.tableExists(table).get();
    assertEquals(true, exist);
    exist = admin.tableExists(TableName.META_TABLE_NAME).get();
    assertEquals(true, exist);
  }

  @Test(timeout = 30000)
  public void testBalancer() throws Exception {
    boolean initialState = admin.isBalancerEnabled().get();

    // Start the balancer, wait for it.
    boolean prevState = admin.setBalancerRunning(!initialState).get();

    // The previous state should be the original state we observed
    assertEquals(initialState, prevState);

    // Current state should be opposite of the original
    assertEquals(!initialState, admin.isBalancerEnabled().get());

    // Reset it back to what it was
    prevState = admin.setBalancerRunning(initialState).get();

    // The previous state should be the opposite of the initial state
    assertEquals(!initialState, prevState);
    // Current state should be the original state again
    assertEquals(initialState, admin.isBalancerEnabled().get());
  }
}
