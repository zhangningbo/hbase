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

import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.coordination.ZkCoordinatedStateManager;
import org.apache.hadoop.util.ReflectionUtils;

/**
 * Creates instance of {@link CoordinatedStateManager}
 * based on configuration.
 */
@InterfaceAudience.Private
public final class CoordinatedStateManagerFactory {

  /**
   * Private to keep this class from being accidentally instantiated.
   */
  private CoordinatedStateManagerFactory(){}

  /**
   * Creates consensus provider from the given configuration.
   * @param conf Configuration
   * @return Implementation of  {@link CoordinatedStateManager}
   */
  public static CoordinatedStateManager getCoordinatedStateManager(Configuration conf) {
    Class<? extends CoordinatedStateManager> coordinatedStateMgrKlass =
      conf.getClass(HConstants.HBASE_COORDINATED_STATE_MANAGER_CLASS,
        ZkCoordinatedStateManager.class, CoordinatedStateManager.class);
    return ReflectionUtils.newInstance(coordinatedStateMgrKlass, conf);
  }
}
