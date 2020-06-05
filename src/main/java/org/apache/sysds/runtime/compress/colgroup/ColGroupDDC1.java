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

package org.apache.sysds.runtime.compress.colgroup;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

import org.apache.sysds.runtime.compress.CompressionSettings;
import org.apache.sysds.runtime.compress.utils.AbstractBitmap;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.ScalarOperator;

/**
 * Class to encapsulate information about a column group that is encoded with dense dictionary encoding (DDC) using 1
 * byte codes.
 */
public class ColGroupDDC1 extends ColGroupDDC {
	private static final long serialVersionUID = 5204955589230760157L;

	private byte[] _data;

	protected ColGroupDDC1() {
		super();
	}

	protected ColGroupDDC1(int[] colIndices, int numRows, AbstractBitmap ubm, CompressionSettings cs) {
		super(colIndices, numRows, ubm, cs);

		int numVals = ubm.getNumValues();
		int numCols = ubm.getNumColumns();

		_data = new byte[numRows];

		// materialize zero values, if necessary
		if(ubm.getNumOffsets() < (long) numRows * numCols) {
			int zeroIx = containsAllZeroValue();
			if(zeroIx < 0) {
				zeroIx = numVals;
				_dict = IDictionary.materializeZeroValue(_dict, numCols);
			}
			Arrays.fill(_data, (byte) zeroIx);
		}

		// iterate over values and write dictionary codes
		for(int i = 0; i < numVals; i++) {
			int[] tmpList = ubm.getOffsetsList(i).extractValues();
			int tmpListSize = ubm.getNumOffsets(i);
			for(int k = 0; k < tmpListSize; k++)
				_data[tmpList[k]] = (byte) i;
		}
	}

	// Internal Constructor, to be used when copying a DDC Colgroup, and for scalar operations
	protected ColGroupDDC1(int[] colIndices, int numRows, double[] values, byte[] data) {
		super(colIndices, numRows, values);
		_data = data;
	}

	@Override
	protected ColGroupType getColGroupType() {
		return ColGroupType.DDC1;
	}

	/**
	 * Getter method to get the data, contained in The DDC ColGroup.
	 * 
	 * Not safe if modifications is made to the byte list.
	 * 
	 * @return The contained data
	 */
	public byte[] getData() {
		return _data;
	}

	@Override
	protected int getIndex(int r) {
		return _data[r] & 0xFF;
	}

	@Override
	protected int getIndex(int r, int colIx) {
		return _data[r] & 0xFF * getNumCols() + colIx;
	}

	@Override
	protected double getData(int r, double[] dictionary) {
		return dictionary[_data[r] & 0xFF];
	}

	@Override
	protected double getData(int r, int colIx, double[] values) {
		return values[(_data[r] & 0xFF) * getNumCols() + colIx];
	}

	@Override
	protected void setData(int r, int code) {
		_data[r] = (byte) code;
	}

	@Override
	protected int getCode(int r) {
		return(_data[r] & 0xFF);
	}

	public void recodeData(HashMap<Double, Integer> map) {
		// prepare translation table
		final int numVals = getNumValues();
		final double[] values = getValues();
		byte[] lookup = new byte[numVals];
		for(int k = 0; k < numVals; k++)
			lookup[k] = map.get(values[k]).byteValue();

		// recode the data
		for(int i = 0; i < _numRows; i++)
			_data[i] = lookup[_data[i] & 0xFF];
	}

	@Override
	public void write(DataOutput out) throws IOException {
		super.write(out);
		// write data
		// out.writeInt(_numRows);
		for(int i = 0; i < _numRows; i++)
			out.writeByte(_data[i]);
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		super.readFields(in);
		// read data
		_data = new byte[_numRows];
		for(int i = 0; i < _numRows; i++)
			_data[i] = in.readByte();
	}

	@Override
	public long getExactSizeOnDisk() {
		long ret = super.getExactSizeOnDisk();
		// data
		ret += _data.length;

		return ret;
	}

	@Override
	public long estimateInMemorySize() {
		return ColGroupSizes.estimateInMemorySizeDDC1(getNumCols(), getNumValues(), _data.length, isLossy());
	}

	@Override
	public void decompressToBlock(MatrixBlock target, int rl, int ru) {
		int ncol = getNumCols();
		double[] values = getValues();
		for(int i = rl; i < ru; i++)
			for(int j = 0; j < ncol; j++)
				target.appendValue(i, _colIndexes[j], values[(_data[i] & 0xFF) * ncol + j]);
		// note: append ok because final sort per row
	}

	@Override
	public void decompressToBlock(MatrixBlock target, int colpos) {
		int nrow = getNumRows();
		int ncol = getNumCols();
		double[] c = target.getDenseBlockValues();
		double[] values = getValues();
		int nnz = 0;
		for(int i = 0; i < nrow; i++)
			nnz += ((c[i] = values[(_data[i] & 0xFF) * ncol + colpos]) != 0) ? 1 : 0;
		target.setNonZeros(nnz);
	}

	@Override
	public int[] getCounts(int[] counts) {
		return getCounts(0, getNumRows(), counts);
	}

	@Override
	public int[] getCounts(int rl, int ru, int[] counts) {
		final int numVals = getNumValues();
		Arrays.fill(counts, 0, numVals, 0);
		for(int i = rl; i < ru; i++)
			counts[_data[i] & 0xFF]++;
		return counts;
	}

	@Override
	public void countNonZerosPerRow(int[] rnnz, int rl, int ru) {
		final int ncol = getNumCols();
		final int numVals = getNumValues();
		final double[] values = getValues();

		// pre-aggregate nnz per value tuple
		int[] counts = new int[numVals];
		for(int k = 0, valOff = 0; k < numVals; k++, valOff += ncol)
			for(int j = 0; j < ncol; j++)
				counts[k] += (values[valOff + j] != 0) ? 1 : 0;

		// scan data and add counts to output rows
		for(int i = rl; i < ru; i++)
			rnnz[i - rl] += counts[_data[i] & 0xFF];
	}

	@Override
	public void rightMultByVector(MatrixBlock vector, MatrixBlock result, int rl, int ru) {
		double[] b = ColGroupConverter.getDenseVector(vector);
		double[] c = result.getDenseBlockValues();
		final int numCols = getNumCols();
		final int numVals = getNumValues();

		// prepare reduced rhs w/ relevant values
		double[] sb = new double[numCols];
		for(int j = 0; j < numCols; j++) {
			sb[j] = b[_colIndexes[j]];
		}

		// pre-aggregate all distinct values (guaranteed <=255)
		double[] vals = preaggValues(numVals, sb);

		// iterative over codes and add to output
		for(int i = rl; i < ru; i++) {
			c[i] += vals[_data[i] & 0xFF];
		}
	}

	public static void rightMultByVector(ColGroupDDC1[] grps, MatrixBlock vector, MatrixBlock result, int rl, int ru) {
		double[] b = ColGroupConverter.getDenseVector(vector);
		double[] c = result.getDenseBlockValues();

		// prepare distinct values once
		double[][] vals = new double[grps.length][];
		for(int i = 0; i < grps.length; i++) {
			// prepare reduced rhs w/ relevant values
			double[] sb = new double[grps[i].getNumCols()];
			for(int j = 0; j < sb.length; j++) {
				sb[j] = b[grps[i]._colIndexes[j]];
			}
			// pre-aggregate all distinct values (guaranteed <=255)
			vals[i] = grps[i].preaggValues(grps[i].getNumValues(), sb, true);
		}

		// cache-conscious matrix-vector multiplication
		// iterative over codes of all groups and add to output
		int blksz = 2048; // 16KB
		for(int bi = rl; bi < ru; bi += blksz)
			for(int j = 0; j < grps.length; j++)
				for(int i = bi; i < Math.min(bi + blksz, ru); i++)
					c[i] += vals[j][grps[j]._data[i] & 0xFF];
	}

	@Override
	public void leftMultByRowVector(MatrixBlock vector, MatrixBlock result) {
		double[] a = ColGroupConverter.getDenseVector(vector);
		double[] c = result.getDenseBlockValues();
		// final int nrow = getNumRows();
		final int numVals = getNumValues();

		// iterative over codes and pre-aggregate inputs per code (guaranteed <=255)
		// temporary array also avoids false sharing in multi-threaded environments
		double[] vals = allocDVector(numVals, true);
		for(int i = 0; i < _numRows; i++) {
			int index = getIndex(i);
			vals[index] += a[i];
		}

		// post-scaling of pre-aggregate with distinct values
		postScaling(vals, c);
	}

	// @Override
	// public void leftMultByRowVector(ColGroupDDC a, MatrixBlock result) {
	// 	double[] c = result.getDenseBlockValues();
	// 	final int nrow = getNumRows();
	// 	final int numVals = getNumValues();
	// 	// final double[] dictionary = getValues();

	// 	// iterative over codes and pre-aggregate inputs per code (guaranteed <=255)
	// 	// temporary array also avoids false sharing in multi-threaded environments
	// 	double[] vals = allocDVector(numVals, true);
	// 	double[] aDict = a.getValues();
	// 	for(int i = 0; i < nrow; i++) {
	// 		int rowIdA = a.getIndex(i);
	// 		int rowIdThis = getIndex(i);
	// 		vals[rowIdThis] += aDict[rowIdA];
	// 	}
	// 	// vals[_data[i] & 0xFF] += a.getData(i, dictionary);

	// 	// post-scaling of pre-aggregate with distinct values
	// 	postScaling(vals, c);
	// }


	// public static void computeRowSums(ColGroupDDC1[] grps, MatrixBlock result, KahanFunction kplus, int rl, int ru) {
	// 	// note: due to corrections the output might be a large dense block
	// 	DenseBlock c = result.getDenseBlock();

	// 	if(grps[0]._dict instanceof QDictionary && !(kplus instanceof KahanPlusSq)) {


	// 		return; // early return if needed.
	// 	}

	// 	KahanObject kbuff = new KahanObject(0, 0);
	// 	KahanPlus kplus2 = KahanPlus.getKahanPlusFnObject();

	// 	// prepare distinct values once
	// 	double[][] vals = new double[grps.length][];
	// 	for(int i = 0; i < grps.length; i++) {
	// 		// pre-aggregate all distinct values (guaranteed <=255)
	// 		vals[i] = grps[i].sumAllValues(kplus, kbuff);
	// 	}

	// 	// cache-conscious row sums operations
	// 	// iterative over codes of all groups and add to output
	// 	// (use kahan plus not general KahanFunction for correctness in case of sqk+)
	// 	int blksz = 1024; // 16KB
	// 	double[] tmpAgg = new double[blksz];
	// 	for(int bi = rl; bi < ru; bi += blksz) {
	// 		Arrays.fill(tmpAgg, 0);
	// 		// aggregate all groups
	// 		for(int j = 0; j < grps.length; j++) {
	// 			double[] valsj = vals[j];
	// 			byte[] dataj = grps[j]._data;
	// 			for(int i = bi; i < Math.min(bi + blksz, ru); i++)
	// 				tmpAgg[i - bi] += valsj[dataj[i] & 0xFF];
	// 		}
	// 		// add partial results of all ddc groups
	// 		for(int i = bi; i < Math.min(bi + blksz, ru); i++) {
	// 			double[] cvals = c.values(i);
	// 			int cix = c.pos(i);
	// 			kbuff.set(cvals[cix], cvals[cix + 1]);
	// 			kplus2.execute2(kbuff, tmpAgg[i - bi]);
	// 			cvals[cix] = kbuff._sum;
	// 			cvals[cix + 1] = kbuff._correction;
	// 		}
	// 	}

	// }

	@Override
	public ColGroup scalarOperation(ScalarOperator op) {
		// fast path: sparse-safe and -unsafe operations
		// as zero are represented, it is sufficient to simply apply the scalar op
		return new ColGroupDDC1(_colIndexes, _numRows, applyScalarOp(op), _data);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toString());
		sb.append(" DataLength: " + this._data.length);
		return sb.toString();
	}
}
