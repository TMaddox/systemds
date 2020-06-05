/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.compress;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.lops.MapMultChain.ChainType;
import org.apache.sysds.runtime.compress.CompressedMatrixBlock;
import org.apache.sysds.runtime.compress.CompressedMatrixBlockFactory;
import org.apache.sysds.runtime.compress.CompressionSettings;
import org.apache.sysds.runtime.compress.CompressionSettingsBuilder;
import org.apache.sysds.runtime.compress.CompressionStatistics;
import org.apache.sysds.runtime.compress.colgroup.ColGroup.CompressionType;
import org.apache.sysds.runtime.functionobjects.Multiply;
import org.apache.sysds.runtime.functionobjects.Plus;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.AggregateBinaryOperator;
import org.apache.sysds.runtime.matrix.operators.AggregateOperator;
import org.apache.sysds.runtime.util.DataConverter;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.test.component.compress.TestConstants.MatrixTypology;
import org.apache.sysds.test.component.compress.TestConstants.SparsityType;
import org.apache.sysds.test.component.compress.TestConstants.ValueRange;
import org.apache.sysds.test.component.compress.TestConstants.ValueType;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

public abstract class CompressedTestBase extends TestBase {
	protected static final Log LOG = LogFactory.getLog(CompressedTestBase.class.getName());
	protected static SparsityType[] usedSparsityTypes = new SparsityType[] { // Sparsity 0.9, 0.1, 0.01 and 0.0
		// SparsityType.FULL,
		SparsityType.DENSE,
		SparsityType.SPARSE, 
		// SparsityType.ULTRA_SPARSE,
		// SparsityType.EMPTY
	};

	protected static ValueType[] usedValueTypes = new ValueType[] {
		// ValueType.RAND,
		// ValueType.CONST,
		ValueType.RAND_ROUND,
		//  ValueType.OLE_COMPRESSIBLE,
		// ValueType.RLE_COMPRESSIBLE,
	};

	protected static ValueRange[] usedValueRanges = new ValueRange[] {
		// ValueRange.SMALL, 
		ValueRange.LARGE,
		// ValueRange.BYTE
	};

	private static final int compressionSeed = 7;

	protected static CompressionSettings[] usedCompressionSettings = new CompressionSettings[] {
		// new CompressionSettingsBuilder().setSamplingRatio(0.1).setAllowSharedDDCDictionary(false)
		// .setSeed(compressionSeed).setValidCompressions(EnumSet.of(CompressionType.DDC)).setInvestigateEstimate(true).create(),
		new CompressionSettingsBuilder().setSamplingRatio(0.1)//.setAllowSharedDDCDictionary(true)
			.setSeed(compressionSeed).setValidCompressions(EnumSet.of(CompressionType.DDC)).setInvestigateEstimate(true)
			.create(),
		new CompressionSettingsBuilder().setSamplingRatio(0.1).setSeed(compressionSeed)
			.setValidCompressions(EnumSet.of(CompressionType.OLE)).setInvestigateEstimate(true).create(),
		new CompressionSettingsBuilder().setSamplingRatio(0.1).setSeed(compressionSeed)
			.setValidCompressions(EnumSet.of(CompressionType.RLE)).setInvestigateEstimate(true).create(),
		new CompressionSettingsBuilder().setSamplingRatio(0.1).setSeed(compressionSeed).setInvestigateEstimate(true)
			.create(),
		// new CompressionSettingsBuilder().setSamplingRatio(1.0).setSeed(compressionSeed).setInvestigateEstimate(true)
		// .addValidCompression(CompressionType.QUAN).create(),
		new CompressionSettingsBuilder().setSamplingRatio(1.0).setSeed(compressionSeed).setInvestigateEstimate(true)
		.setAllowSharedDDCDictionary(false).setmaxStaticColGroupCoCode(1).create(),
		new CompressionSettingsBuilder().setSamplingRatio(1.0).setSeed(compressionSeed).setInvestigateEstimate(true)
		.setAllowSharedDDCDictionary(false).setmaxStaticColGroupCoCode(1).setLossy(true).create(),
		// new CompressionSettingsBuilder().setSamplingRatio(1.0).setSeed(compressionSeed).setInvestigateEstimate(true)
		// .setAllowSharedDDCDictionary(false).setmaxStaticColGroupCoCode(20).create(),
		// new CompressionSettingsBuilder().setSamplingRatio(1.0).setSeed(compressionSeed).setInvestigateEstimate(true)
		// .setAllowSharedDDCDictionary(false).setmaxStaticColGroupCoCode(20).setLossy(true).create()
	};

	protected static MatrixTypology[] usedMatrixTypology = new MatrixTypology[] { // Selected Matrix Types
		// MatrixTypology.SMALL,
		// MatrixTypology.FEW_COL,
		// MatrixTypology.FEW_ROW,
		MatrixTypology.LARGE,
		// MatrixTypology.SINGLE_COL,
		// MatrixTypology.SINGLE_ROW,
		// MatrixTypology.L_ROWS,
		// MatrixTypology.XL_ROWS,
	};

	// Compressed Block
	protected MatrixBlock cmb;
	protected CompressionStatistics cmbStats;

	// Decompressed Result
	protected MatrixBlock cmbDeCompressed;
	protected double[][] deCompressed;

	/** Method returning the number of threads used for the operation */
	protected final int _k;

	protected int sampleTolerance = 1024;

	protected double lossyTolerance;

	public CompressedTestBase(SparsityType sparType, ValueType valType, ValueRange valueRange,
		CompressionSettings compSettings, MatrixTypology MatrixTypology, int parallelism) {
		super(sparType, valType, valueRange, compSettings, MatrixTypology);
		_k = parallelism;

		try {
			if(compSettings.lossy)
				setLossyTolerance(valueRange);
			Pair<MatrixBlock, CompressionStatistics> pair = CompressedMatrixBlockFactory
				.compress(mb, _k, compressionSettings);
			cmb = pair.getLeft();
			cmbStats = pair.getRight();
			if(cmb instanceof CompressedMatrixBlock) {
				cmbDeCompressed = ((CompressedMatrixBlock) cmb).decompress();
				if(cmbDeCompressed != null) {

					deCompressed = DataConverter.convertToDoubleMatrix(cmbDeCompressed);
				}
			}
			else {
				cmbDeCompressed = null;
				deCompressed = null;
			}

		}
		catch(Exception e) {
			e.printStackTrace();
			assertTrue("\nCompressionTest Init failed with settings: " + this.toString(), false);
		}

	}

	private void setLossyTolerance(ValueRange valueRange) {
		/**
		 * Tolerance for encoding values is the maximum value in dataset divided by number distinct values available in
		 * a single Byte (since we encode our quntization in Byte)
		 */
		lossyTolerance = (double) Math.max(TestConstants.getMaxRangeValue(valueRange),
			Math.abs(TestConstants.getMinRangeValue(valueRange))) / 127.0;

	}

	@Parameters
	public static Collection<Object[]> data() {
		ArrayList<Object[]> tests = new ArrayList<>();

		for(SparsityType st : usedSparsityTypes) {
			for(ValueType vt : usedValueTypes) {
				for(ValueRange vr : usedValueRanges) {
					for(CompressionSettings cs : usedCompressionSettings) {
						for(MatrixTypology mt : usedMatrixTypology) {
							tests.add(new Object[] {st, vt, vr, cs, mt});
						}
					}
				}
			}
		}

		return tests;
	}

	// %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
	// %%%%%%%%%%%%%%%%% TESTS START! %%%%%%%%%%%%%%%%%
	// %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%

	@Test
	public void testConstruction() {
		try {
			if(!(cmb instanceof CompressedMatrixBlock)) {
				return; // Input was not compressed then just pass test
				// Assert.assertTrue("Compression Failed \n" + this.toString(), false);
			}
			if(compressionSettings.lossy) {
				TestUtils.compareMatrices(input, deCompressed, lossyTolerance);
			}
			else {
				TestUtils.compareMatricesBitAvgDistance(input, deCompressed, 0, 0, compressionSettings.toString());
			}
		}
		catch(Exception e) {
			e.printStackTrace();
			throw new RuntimeException(this.toString() + "\n" + e.getMessage(), e);
		}
	}

	@Test
	public void testDecompress() {
		try {
			if(!(cmb instanceof CompressedMatrixBlock)) {
				return; // Input was not compressed then just pass test
				// Assert.assertTrue("Compression Failed \n" + this.toString(), false);
			}
			double[][] deCompressed = DataConverter.convertToDoubleMatrix(((CompressedMatrixBlock) cmb).decompress(_k));
			if(compressionSettings.lossy) {
				TestUtils.compareMatrices(input, deCompressed, lossyTolerance);
			}
			else {
				TestUtils.compareMatricesBitAvgDistance(input, deCompressed, 0, 0, compressionSettings.toString());
			}
		}
		catch(Exception e) {
			e.printStackTrace();
			throw new RuntimeException(this.toString() + "\n" + e.getMessage(), e);
		}
	}

	@Test
	public void testMatrixMultChain() {
		try {
			if(!(cmb instanceof CompressedMatrixBlock))
				return; // Input was not compressed then just pass test

			MatrixBlock vector1 = DataConverter
				.convertToMatrixBlock(TestUtils.generateTestMatrix(cols, 1, 0.5, 1.5, 1.0, 3));

			// ChainType ctype = ChainType.XtwXv;
			// Linear regression .
			for(ChainType ctype : new ChainType[] {ChainType.XtwXv, ChainType.XtXv,
				// ChainType.XtXvy
			}) {

				MatrixBlock vector2 = (ctype == ChainType.XtwXv) ? DataConverter
					.convertToMatrixBlock(TestUtils.generateTestMatrix(rows, 1, 0.5, 1.5, 1.0, 3)) : null;

				// matrix-vector uncompressed
				MatrixBlock ret1 = mb.chainMatrixMultOperations(vector1, vector2, new MatrixBlock(), ctype, _k);

				// matrix-vector compressed
				MatrixBlock ret2 = cmb.chainMatrixMultOperations(vector1, vector2, new MatrixBlock(), ctype, _k);

				// compare result with input
				double[][] d1 = DataConverter.convertToDoubleMatrix(ret1);
				double[][] d2 = DataConverter.convertToDoubleMatrix(ret2);

				if(compressionSettings.lossy) {
					// TODO Make actual calculation to know the tolerance
					// double scaledTolerance = lossyTolerance * d1.length * d1.length * 1.5;
					// if(ctype == ChainType.XtwXv){
					// scaledTolerance *= d1.length * d1.length * 0.5;
					// }
					// TestUtils.compareMatrices(d1, d2, d1.length, d1[0].length, scaledTolerance );
					TestUtils.compareMatricesPercentageDistance(d1, d2, 0.95, 0.95, compressionSettings.toString());
				}
				else {
					TestUtils.compareMatricesBitAvgDistance(d1, d2, 2048, 350, compressionSettings.toString());
				}
			}
		}
		catch(Exception e) {
			e.printStackTrace();
			throw new RuntimeException(this.toString() + "\n" + e.getMessage(), e);
		}
	}

	@Test
	public void testMatrixVectorMult01() {
		testMatrixVectorMult(1.0, 1.1);
	}

	@Test
	public void testMatrixVectorMult02() {
		testMatrixVectorMult(0.7, 1.0);
	}

	@Test
	public void testMatrixVectorMult03() {
		testMatrixVectorMult(-1.0, 1.0);
	}

	@Test
	public void testMatrixVectorMult04() {
		testMatrixVectorMult(1.0, 5.0);
	}

	public void testMatrixVectorMult(double min, double max) {
		try {
			if(!(cmb instanceof CompressedMatrixBlock))
				return; // Input was not compressed then just pass test

			MatrixBlock vector = DataConverter
				.convertToMatrixBlock(TestUtils.generateTestMatrix(cols, 1, min, max, 1.0, 3));

			// Make Operator // matrix-vector uncompressed
			// AggregateBinaryOperator abop = InstructionUtils.getMatMultOperator(_k);
			AggregateOperator aop = new AggregateOperator(0, Plus.getPlusFnObject());
			AggregateBinaryOperator abop = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), aop);

			// matrix-vector uncompressed
			MatrixBlock ret1 = mb.aggregateBinaryOperations(mb, vector, new MatrixBlock(), abop);

			// matrix-vector compressed
			MatrixBlock ret2 = cmb.aggregateBinaryOperations(cmb, vector, new MatrixBlock(), abop);

			// compare result with input
			double[][] d1 = DataConverter.convertToDoubleMatrix(ret1);
			double[][] d2 = DataConverter.convertToDoubleMatrix(ret2);

			if(compressionSettings.lossy) {
				// TODO Make actual calculation to know the actual tolerance
				double scaledTolerance = lossyTolerance * 30 * max;
				TestUtils.compareMatrices(d1, d2, scaledTolerance);
			}
			else {
				TestUtils.compareMatricesBitAvgDistance(d1, d2, 120000, 128, compressionSettings.toString());
			}
		}
		catch(Exception e) {
			e.printStackTrace();
			throw new RuntimeException(this.toString() + "\n" + e.getMessage(), e);
		}
	}
}
