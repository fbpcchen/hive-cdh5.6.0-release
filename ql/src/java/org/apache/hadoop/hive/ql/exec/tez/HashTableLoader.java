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
package org.apache.hadoop.hive.ql.exec.tez;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.MapJoinOperator;
import org.apache.hadoop.hive.ql.exec.MapredContext;
import org.apache.hadoop.hive.ql.exec.mapjoin.MapJoinMemoryExhaustionHandler;
import org.apache.hadoop.hive.ql.exec.mr.ExecMapperContext;
import org.apache.hadoop.hive.ql.exec.persistence.HashMapWrapper;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinBytesTableContainer;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinKey;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinObjectSerDeContext;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinTableContainer;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinTableContainerSerDe;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.MapJoinDesc;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.Writable;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.library.api.KeyValueReader;

/**
 * HashTableLoader for Tez constructs the hashtable from records read from
 * a broadcast edge.
 */
public class HashTableLoader implements org.apache.hadoop.hive.ql.exec.HashTableLoader {

  private static final Log LOG = LogFactory.getLog(HashTableLoader.class.getName());

  private ExecMapperContext context;
  private Configuration hconf;
  private MapJoinDesc desc;

  @Override
  public void init(ExecMapperContext context, Configuration hconf, MapJoinOperator joinOp) {
    this.context = context;
    this.hconf = hconf;
    this.desc = joinOp.getConf();
  }

  @Override
  public void load(
      MapJoinTableContainer[] mapJoinTables,
      MapJoinTableContainerSerDe[] mapJoinTableSerdes, long memUsage) throws HiveException {

    TezContext tezContext = (TezContext) MapredContext.get();
    Map<Integer, String> parentToInput = desc.getParentToInput();
    Map<Integer, Long> parentKeyCounts = desc.getParentKeyCounts();

    boolean useOptimizedTables = HiveConf.getBoolVar(
        hconf, HiveConf.ConfVars.HIVEMAPJOINUSEOPTIMIZEDTABLE);
    boolean isFirstKey = true;
    TezCacheAccess tezCacheAccess = TezCacheAccess.createInstance(hconf);
    for (int pos = 0; pos < mapJoinTables.length; pos++) {
      if (pos == desc.getPosBigTable()) {
        continue;
      }

      String inputName = parentToInput.get(pos);
      LogicalInput input = tezContext.getInput(inputName);

      try {
        KeyValueReader kvReader = (KeyValueReader) input.getReader();
        MapJoinObjectSerDeContext keyCtx = mapJoinTableSerdes[pos].getKeyContext(),
          valCtx = mapJoinTableSerdes[pos].getValueContext();
        if (useOptimizedTables) {
          ObjectInspector keyOi = keyCtx.getSerDe().getObjectInspector();
          if (!MapJoinBytesTableContainer.isSupportedKey(keyOi)) {
            if (isFirstKey) {
              useOptimizedTables = false;
            } else {
              throw new HiveException(describeOi(
                  "Only a subset of mapjoin keys is supported. Unsupported key: ", keyOi));
            }
          }
        }
        isFirstKey = false;
        Long keyCountObj = parentKeyCounts.get(pos);
        long keyCount = (keyCountObj == null) ? -1 : keyCountObj.longValue();
        MapJoinTableContainer tableContainer = useOptimizedTables
            ? new MapJoinBytesTableContainer(hconf, valCtx, keyCount, memUsage)
            : new HashMapWrapper(hconf, keyCount);

        while (kvReader.next()) {
          tableContainer.putRow(keyCtx, (Writable)kvReader.getCurrentKey(),
              valCtx, (Writable)kvReader.getCurrentValue());
        }

        tableContainer.seal();
        mapJoinTables[pos] = tableContainer;
      } catch (IOException e) {
        throw new HiveException(e);
      } catch (SerDeException e) {
        throw new HiveException(e);
      } catch (Exception e) {
        throw new HiveException(e);
      }
      // Register that the Input has been cached.
      LOG.info("Is this a bucket map join: " + desc.isBucketMapJoin());
      // cache is disabled for bucket map join because of the same reason
      // given in loadHashTable in MapJoinOperator.
      if (!desc.isBucketMapJoin()) {
        tezCacheAccess.registerCachedInput(inputName);
        LOG.info("Setting Input: " + inputName + " as cached");
      }
    }
  }

  private String describeOi(String desc, ObjectInspector keyOi) {
    for (StructField field : ((StructObjectInspector)keyOi).getAllStructFieldRefs()) {
      ObjectInspector oi = field.getFieldObjectInspector();
      String cat = oi.getCategory().toString();
      if (oi.getCategory() == Category.PRIMITIVE) {
        cat = ((PrimitiveObjectInspector)oi).getPrimitiveCategory().toString();
      }
      desc += field.getFieldName() + ":" + cat + ", ";
    }
    return desc;
  }
}
