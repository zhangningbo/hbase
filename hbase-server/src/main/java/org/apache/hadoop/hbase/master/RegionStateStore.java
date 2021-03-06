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
package org.apache.hadoop.hbase.master;

import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.master.RegionState.State;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.MultiHConnection;
import org.apache.hadoop.hbase.zookeeper.MetaTableLocator;
import org.apache.zookeeper.KeeperException;

/**
 * A helper to persist region state in meta. We may change this class
 * to StateStore later if we also use it to store other states in meta
 */
@InterfaceAudience.Private
public class RegionStateStore {
  private static final Log LOG = LogFactory.getLog(RegionStateStore.class);

  /** The delimiter for meta columns for replicaIds &gt; 0 */
  protected static final char META_REPLICA_ID_DELIMITER = '_';

  private volatile Region metaRegion;
  private volatile boolean initialized;
  private MultiHConnection multiHConnection;
  private final MasterServices server;

  /**
   * Returns the {@link ServerName} from catalog table {@link Result}
   * where the region is transitioning. It should be the same as
   * {@link MetaTableAccessor#getServerName(Result,int)} if the server is at OPEN state.
   * @param r Result to pull the transitioning server name from
   * @return A ServerName instance or {@link MetaTableAccessor#getServerName(Result,int)}
   * if necessary fields not found or empty.
   */
  static ServerName getRegionServer(final Result r, int replicaId) {
    Cell cell = r.getColumnLatestCell(HConstants.CATALOG_FAMILY, getServerNameColumn(replicaId));
    if (cell == null || cell.getValueLength() == 0) {
      RegionLocations locations = MetaTableAccessor.getRegionLocations(r);
      if (locations != null) {
        HRegionLocation location = locations.getRegionLocation(replicaId);
        if (location != null) {
          return location.getServerName();
        }
      }
      return null;
    }
    return ServerName.parseServerName(Bytes.toString(cell.getValueArray(),
      cell.getValueOffset(), cell.getValueLength()));
  }

  private static byte[] getServerNameColumn(int replicaId) {
    return replicaId == 0
        ? HConstants.SERVERNAME_QUALIFIER
        : Bytes.toBytes(HConstants.SERVERNAME_QUALIFIER_STR + META_REPLICA_ID_DELIMITER
          + String.format(HRegionInfo.REPLICA_ID_FORMAT, replicaId));
  }

  /**
   * Pull the region state from a catalog table {@link Result}.
   * @param r Result to pull the region state from
   * @return the region state, or OPEN if there's no value written.
   */
  static State getRegionState(final Result r, int replicaId) {
    Cell cell = r.getColumnLatestCell(HConstants.CATALOG_FAMILY, getStateColumn(replicaId));
    if (cell == null || cell.getValueLength() == 0) return State.OPEN;
    return State.valueOf(Bytes.toString(cell.getValueArray(),
      cell.getValueOffset(), cell.getValueLength()));
  }

  private static byte[] getStateColumn(int replicaId) {
    return replicaId == 0
        ? HConstants.STATE_QUALIFIER
        : Bytes.toBytes(HConstants.STATE_QUALIFIER_STR + META_REPLICA_ID_DELIMITER
          + String.format(HRegionInfo.REPLICA_ID_FORMAT, replicaId));
  }

  /**
   * Check if we should persist a state change in meta. Generally it's
   * better to persist all state changes. However, we should not do that
   * if the region is not in meta at all. Based on the state and the
   * previous state, we can identify if a user region has an entry
   * in meta. For example, merged regions are deleted from meta;
   * New merging parents, or splitting daughters are
   * not created in meta yet.
   */
  private boolean shouldPersistStateChange(
      HRegionInfo hri, RegionState state, RegionState oldState) {
    return !hri.isMetaRegion() && !RegionStates.isOneOfStates(
      state, State.MERGING_NEW, State.SPLITTING_NEW, State.MERGED)
      && !(RegionStates.isOneOfStates(state, State.OFFLINE)
        && RegionStates.isOneOfStates(oldState, State.MERGING_NEW,
          State.SPLITTING_NEW, State.MERGED));
  }

  RegionStateStore(final MasterServices server) {
    this.server = server;
    initialized = false;
  }

  void start() throws IOException {
    if (server instanceof RegionServerServices) {
      metaRegion = ((RegionServerServices)server).getFromOnlineRegions(
        HRegionInfo.FIRST_META_REGIONINFO.getEncodedName());
    }
    // When meta is not colocated on master
    if (metaRegion == null) {
      Configuration conf = server.getConfiguration();
      // Config to determine the no of HConnections to META.
      // A single Connection should be sufficient in most cases. Only if
      // you are doing lot of writes (>1M) to META,
      // increasing this value might improve the write throughput.
      multiHConnection =
          new MultiHConnection(conf, conf.getInt("hbase.regionstatestore.meta.connection", 1));
    }
    initialized = true;
  }

  void stop() {
    initialized = false;
    if (multiHConnection != null) {
      multiHConnection.close();
    }
  }

  void updateRegionState(long openSeqNum,
      RegionState newState, RegionState oldState) {
    try {
      HRegionInfo hri = newState.getRegion();

      // Update meta before checking for initialization. Meta state stored in zk.
      if (hri.isMetaRegion()) {
        // persist meta state in MetaTableLocator (which in turn is zk storage currently)
        try {
          MetaTableLocator.setMetaLocation(server.getZooKeeper(),
            newState.getServerName(), hri.getReplicaId(), newState.getState());
          return; // Done
        } catch (KeeperException e) {
          throw new IOException("Failed to update meta ZNode", e);
        }
      }

      if (!initialized
          || !shouldPersistStateChange(hri, newState, oldState)) {
        return;
      }

      ServerName oldServer = oldState != null ? oldState.getServerName() : null;
      ServerName serverName = newState.getServerName();
      State state = newState.getState();

      int replicaId = hri.getReplicaId();
      Put metaPut = new Put(MetaTableAccessor.getMetaKeyForRegion(hri));
      StringBuilder info = new StringBuilder("Updating hbase:meta row ");
      info.append(hri.getRegionNameAsString()).append(" with state=").append(state);
      if (serverName != null && !serverName.equals(oldServer)) {
        metaPut.addImmutable(HConstants.CATALOG_FAMILY, getServerNameColumn(replicaId),
          Bytes.toBytes(serverName.getServerName()));
        info.append(", sn=").append(serverName);
      }
      if (openSeqNum >= 0) {
        Preconditions.checkArgument(state == State.OPEN
          && serverName != null, "Open region should be on a server");
        MetaTableAccessor.addLocation(metaPut, serverName, openSeqNum, -1, replicaId);
        info.append(", openSeqNum=").append(openSeqNum);
        info.append(", server=").append(serverName);
      }
      metaPut.addImmutable(HConstants.CATALOG_FAMILY, getStateColumn(replicaId),
        Bytes.toBytes(state.name()));
      LOG.info(info);
      HTableDescriptor descriptor = server.getTableDescriptors().get(hri.getTable());
      boolean serial = false;
      if (descriptor != null) {
        serial = server.getTableDescriptors().get(hri.getTable()).hasSerialReplicationScope();
      }
      boolean shouldPutBarrier = serial && state == State.OPEN;
      // Persist the state change to meta
      if (metaRegion != null) {
        try {
          // Assume meta is pinned to master.
          // At least, that's what we want.
          metaRegion.put(metaPut);
          if (shouldPutBarrier) {
            Put barrierPut = MetaTableAccessor.makeBarrierPut(hri.getEncodedNameAsBytes(),
                openSeqNum, hri.getTable().getName());
            metaRegion.put(barrierPut);
          }
          return; // Done here
        } catch (Throwable t) {
          // In unit tests, meta could be moved away by intention
          // So, the shortcut is gone. We won't try to establish the
          // shortcut any more because we prefer meta to be pinned
          // to the master
          synchronized (this) {
            if (metaRegion != null) {
              LOG.info("Meta region shortcut failed", t);
              if (multiHConnection == null) {
                multiHConnection = new MultiHConnection(server.getConfiguration(), 1);
              }
              metaRegion = null;
            }
          }
        }
      }
      // Called when meta is not on master
      List<Put> list = shouldPutBarrier ?
          Arrays.asList(metaPut, MetaTableAccessor.makeBarrierPut(hri.getEncodedNameAsBytes(),
              openSeqNum, hri.getTable().getName())) : Collections.singletonList(metaPut);
      multiHConnection.processBatchCallback(list, TableName.META_TABLE_NAME, null, null);

    } catch (IOException ioe) {
      LOG.error("Failed to persist region state " + newState, ioe);
      server.abort("Failed to update region location", ioe);
    }
  }

  void splitRegion(HRegionInfo p,
      HRegionInfo a, HRegionInfo b, ServerName sn, int regionReplication) throws IOException {
    MetaTableAccessor.splitRegion(server.getConnection(), p, a, b, sn, regionReplication,
        server.getTableDescriptors().get(p.getTable()).hasSerialReplicationScope());
  }

  void mergeRegions(HRegionInfo p,
      HRegionInfo a, HRegionInfo b, ServerName sn, int regionReplication) throws IOException {
    MetaTableAccessor.mergeRegions(server.getConnection(), p, a, b, sn, regionReplication,
        EnvironmentEdgeManager.currentTime(),
        server.getTableDescriptors().get(p.getTable()).hasSerialReplicationScope());
  }
}
