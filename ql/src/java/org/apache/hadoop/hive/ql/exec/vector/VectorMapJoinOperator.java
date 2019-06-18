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

package org.apache.hadoop.hive.ql.exec.vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.exec.ExprNodeEvaluator;
import org.apache.hadoop.hive.ql.exec.MapJoinOperator;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinTableContainer.ReusableGetAdaptor;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpression;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpressionWriter;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpressionWriterFactory;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.MapJoinDesc;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;

/**
 * The vectorized version of the MapJoinOperator.
 */
public class VectorMapJoinOperator extends MapJoinOperator implements VectorizationContextRegion {

  private static final Log LOG = LogFactory.getLog(
      VectorMapJoinOperator.class.getName());

   /**
   *
   */
  private static final long serialVersionUID = 1L;

  private VectorExpression[] keyExpressions;

  private VectorExpression[] bigTableFilterExpressions;
  private VectorExpression[] bigTableValueExpressions;
  
  private VectorizationContext vOutContext;

  // The above members are initialized by the constructor and must not be
  // transient.
  //---------------------------------------------------------------------------

  private transient VectorizedRowBatch outputBatch;
  private transient VectorExpressionWriter[] valueWriters;
  private transient Map<ObjectInspector, VectorColumnAssign[]> outputVectorAssigners;

  // These members are used as out-of-band params
  // for the inner-loop supper.processOp callbacks
  //
  private transient int batchIndex;
  private transient VectorHashKeyWrapper[] keyValues;
  private transient VectorHashKeyWrapperBatch keyWrapperBatch;
  private transient VectorExpressionWriter[] keyOutputWriters;

  private transient VectorizedRowBatchCtx vrbCtx = null;
  
  public VectorMapJoinOperator() {
    super();
  }


  public VectorMapJoinOperator (VectorizationContext vContext, OperatorDesc conf)
    throws HiveException {
    this();

    MapJoinDesc desc = (MapJoinDesc) conf;
    this.conf = desc;

    order = desc.getTagOrder();
    numAliases = desc.getExprs().size();
    posBigTable = (byte) desc.getPosBigTable();
    filterMaps = desc.getFilterMap();
    noOuterJoin = desc.isNoOuterJoin();

    Map<Byte, List<ExprNodeDesc>> filterExpressions = desc.getFilters();
    bigTableFilterExpressions = vContext.getVectorExpressions(filterExpressions.get(posBigTable),
        VectorExpressionDescriptor.Mode.FILTER);

    List<ExprNodeDesc> keyDesc = desc.getKeys().get(posBigTable);
    keyExpressions = vContext.getVectorExpressions(keyDesc);

    // We're only going to evaluate the big table vectorized expressions,
    Map<Byte, List<ExprNodeDesc>> exprs = desc.getExprs();
    bigTableValueExpressions = vContext.getVectorExpressions(exprs.get(posBigTable));

    // We are making a new output vectorized row batch.
    vOutContext = new VectorizationContext(desc.getOutputColumnNames());
    vOutContext.setFileKey(vContext.getFileKey() + "/MAP_JOIN_" + desc.getBigTableAlias());
  }

  @Override
  public void initializeOp(Configuration hconf) throws HiveException {
    super.initializeOp(hconf);
    
    List<ExprNodeDesc> keyDesc = conf.getKeys().get(posBigTable);
    keyOutputWriters = VectorExpressionWriterFactory.getExpressionWriters(keyDesc);

    vrbCtx = new VectorizedRowBatchCtx();
    vrbCtx.init(vOutContext.getScratchColumnTypeMap(), (StructObjectInspector) this.outputObjInspector);

    outputBatch = vrbCtx.createVectorizedRowBatch();

    keyWrapperBatch =VectorHashKeyWrapperBatch.compileKeyWrapperBatch(keyExpressions);

    Map<Byte, List<ExprNodeDesc>> valueExpressions = conf.getExprs();
    List<ExprNodeDesc> bigTableExpressions = valueExpressions.get(posBigTable);

    VectorExpressionWriterFactory.processVectorExpressions(
        bigTableExpressions,
        new VectorExpressionWriterFactory.ListOIDClosure() {
          @Override
          public void assign(VectorExpressionWriter[] writers, List<ObjectInspector> oids) {
            valueWriters = writers;
            joinValuesObjectInspectors[posBigTable] = oids;
          }
        });

    // We're hijacking the big table evaluators an replace them with our own custom ones
    // which are going to return values from the input batch vector expressions
    List<ExprNodeEvaluator> vectorNodeEvaluators = new ArrayList<ExprNodeEvaluator>(bigTableExpressions.size());

    for(int i=0; i<bigTableExpressions.size(); ++i) {
      ExprNodeDesc desc = bigTableExpressions.get(i);
      VectorExpression vectorExpr = bigTableValueExpressions[i];

      // This is a vectorized aware evaluator
      ExprNodeEvaluator eval = new ExprNodeEvaluator<ExprNodeDesc>(desc) {
        int columnIndex;;
        int writerIndex;

        public ExprNodeEvaluator initVectorExpr(int columnIndex, int writerIndex) {
          this.columnIndex = columnIndex;
          this.writerIndex = writerIndex;
          return this;
        }

        @Override
        public ObjectInspector initialize(ObjectInspector rowInspector) throws HiveException {
          throw new HiveException("should never reach here");
        }

        @Override
        protected Object _evaluate(Object row, int version) throws HiveException {
          VectorizedRowBatch inBatch = (VectorizedRowBatch) row;
          int rowIndex = inBatch.selectedInUse ? inBatch.selected[batchIndex] : batchIndex;
          return valueWriters[writerIndex].writeValue(inBatch.cols[columnIndex], rowIndex);
        }
      }.initVectorExpr(vectorExpr.getOutputColumn(), i);
      vectorNodeEvaluators.add(eval);
    }
    // Now replace the old evaluators with our own
    joinValues[posBigTable] = vectorNodeEvaluators;

    // Filtering is handled in the input batch processing
    filterMaps[posBigTable] = null;

    outputVectorAssigners = new HashMap<ObjectInspector, VectorColumnAssign[]>();
  }

  /**
   * 'forwards' the (row-mode) record into the (vectorized) output batch
   */
  @Override
  protected void internalForward(Object row, ObjectInspector outputOI) throws HiveException {
    Object[] values = (Object[]) row;
    VectorColumnAssign[] vcas = outputVectorAssigners.get(outputOI);
    if (null == vcas) {
      vcas = VectorColumnAssignFactory.buildAssigners(
          outputBatch, outputOI, vOutContext.getProjectionColumnMap(), conf.getOutputColumnNames());
      outputVectorAssigners.put(outputOI, vcas);
    }
    for (int i=0; i<values.length; ++i) {
      vcas[i].assignObjectValue(values[i], outputBatch.size);
    }
    ++outputBatch.size;
    if (outputBatch.size == VectorizedRowBatch.DEFAULT_SIZE) {
      flushOutput();
    }
  }

  private void flushOutput() throws HiveException {
    forward(outputBatch, null);
    outputBatch.reset();
  }

  @Override
  public void closeOp(boolean aborted) throws HiveException {
    if (!aborted && 0 < outputBatch.size) {
      flushOutput();
    }
  }

  @Override
  protected void setMapJoinKey(ReusableGetAdaptor dest, Object row, byte alias)
      throws HiveException {
    dest.setFromVector(keyValues[batchIndex], keyOutputWriters, keyWrapperBatch);
  }

  @Override
  public void processOp(Object row, int tag) throws HiveException {
    byte alias = (byte) tag;
    VectorizedRowBatch inBatch = (VectorizedRowBatch) row;

    if (null != bigTableFilterExpressions) {
      for(VectorExpression ve:bigTableFilterExpressions) {
        ve.evaluate(inBatch);
      }
    }

    if (null != bigTableValueExpressions) {
      for(VectorExpression ve: bigTableValueExpressions) {
        ve.evaluate(inBatch);
      }
    }

    keyWrapperBatch.evaluateBatch(inBatch);
    keyValues = keyWrapperBatch.getVectorHashKeyWrappers();

    // This implementation of vectorized JOIN is delegating all the work
    // to the row-mode implementation by hijacking the big table node evaluators
    // and calling the row-mode join processOp for each row in the input batch.
    // Since the JOIN operator is not fully vectorized anyway atm (due to the use
    // of row-mode small-tables) this is a reasonable trade-off.
    //
    for(batchIndex=0; batchIndex < inBatch.size; ++batchIndex) {
      super.processOp(row, tag);
    }

    // Set these two to invalid values so any attempt to use them
    // outside the inner loop results in NPE/OutOfBounds errors
    batchIndex = -1;
    keyValues = null;
  }

  @Override
  public VectorizationContext getOuputVectorizationContext() {
    return vOutContext;
  }
}
