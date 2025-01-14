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

package org.apache.sysds.runtime.transform.encode;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.common.Types;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.matrix.data.FrameBlock;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.util.IndexRange;

public class MultiColumnEncoder implements Encoder {

	protected static final Log LOG = LogFactory.getLog(MultiColumnEncoder.class.getName());
	private List<ColumnEncoderComposite> _columnEncoders;
	// These encoders are deprecated and will be fazed out soon.
	private EncoderMVImpute _legacyMVImpute = null;
	private EncoderOmit _legacyOmit = null;
	private int _colOffset = 0; // offset for federated Workers who are using subrange encoders
	private FrameBlock _meta = null;

	public MultiColumnEncoder(List<ColumnEncoderComposite> columnEncoders) {
		_columnEncoders = columnEncoders;
	}

	public MultiColumnEncoder() {
		_columnEncoders = new ArrayList<>();
	}

	public MatrixBlock encode(FrameBlock in) {
		MatrixBlock out;
		try {
			build(in);
			_meta = getMetaData(new FrameBlock(in.getNumColumns(), Types.ValueType.STRING));
			initMetaData(_meta);
			// apply meta data
			out = apply(in);
		}
		catch(Exception ex) {
			LOG.error("Failed transform-encode frame with \n" + this);
			throw ex;
		}
		return out;
	}

	public void build(FrameBlock in) {
		for(ColumnEncoder columnEncoder : _columnEncoders)
			columnEncoder.build(in);
		legacyBuild(in);
	}

	public void legacyBuild(FrameBlock in) {
		if(_legacyOmit != null)
			_legacyOmit.build(in);
		if(_legacyMVImpute != null)
			_legacyMVImpute.build(in);
	}

	public MatrixBlock apply(FrameBlock in) {
		int numCols = in.getNumColumns() + getNumExtraCols();
		MatrixBlock out = new MatrixBlock(in.getNumRows(), numCols, false);
		return apply(in, out, 0);
	}

	public MatrixBlock apply(FrameBlock in, MatrixBlock out, int outputCol) {
		// There should be a encoder for every column
		int numEncoders = getFromAll(ColumnEncoderComposite.class, ColumnEncoder::getColID).size();
		if(in.getNumColumns() != numEncoders)
			throw new DMLRuntimeException("Not every column in has a CompositeEncoder. Please make sure every column "
				+ "has a encoder or slice the input accordingly");

		try {
			int offset = outputCol;
			for(ColumnEncoderComposite columnEncoder : _columnEncoders) {
				columnEncoder.apply(in, out, columnEncoder._colID - 1 + offset);
				if(columnEncoder.hasEncoder(ColumnEncoderDummycode.class))
					offset += columnEncoder.getEncoder(ColumnEncoderDummycode.class)._domainSize - 1;
			}
			if(_legacyOmit != null)
				out = _legacyOmit.apply(in, out);
			if(_legacyMVImpute != null)
				out = _legacyMVImpute.apply(in, out);
		}
		catch(Exception ex) {
			LOG.error("Failed to transform-apply frame with \n" + this);
			throw ex;
		}
		return out;
	}

	@Override
	public FrameBlock getMetaData(FrameBlock meta) {
		if(_meta != null)
			return _meta;
		for(ColumnEncoder columnEncoder : _columnEncoders)
			columnEncoder.getMetaData(meta);
		if(_legacyOmit != null)
			_legacyOmit.getMetaData(meta);
		if(_legacyMVImpute != null)
			_legacyMVImpute.getMetaData(meta);
		return meta;
	}

	@Override
	public void initMetaData(FrameBlock meta) {
		for(ColumnEncoder columnEncoder : _columnEncoders)
			columnEncoder.initMetaData(meta);
		if(_legacyOmit != null)
			_legacyOmit.initMetaData(meta);
		if(_legacyMVImpute != null)
			_legacyMVImpute.initMetaData(meta);
	}

	@Override
	public void prepareBuildPartial() {
		for(Encoder encoder : _columnEncoders)
			encoder.prepareBuildPartial();
	}

	@Override
	public void buildPartial(FrameBlock in) {
		for(Encoder encoder : _columnEncoders)
			encoder.buildPartial(in);
	}

	/**
	 * Obtain the column mapping of encoded frames based on the passed meta data frame.
	 *
	 * @param meta meta data frame block
	 * @return matrix with column mapping (one row per attribute)
	 */
	public MatrixBlock getColMapping(FrameBlock meta) {
		MatrixBlock out = new MatrixBlock(meta.getNumColumns(), 3, false);
		List<ColumnEncoderDummycode> dc = getColumnEncoders(ColumnEncoderDummycode.class);

		for(int i = 0, ni = 0; i < out.getNumRows(); i++) {
			final int colID = i + 1; // 1-based
			int nColID = ni + 1;
			List<ColumnEncoderDummycode> encoder = dc.stream().filter(e -> e.getColID() == colID)
				.collect(Collectors.toList());
			assert encoder.size() <= 1;
			if(encoder.size() == 1) {
				ni += meta.getColumnMetadata(i).getNumDistinct();
			}
			else {
				ni++;
			}
			out.quickSetValue(i, 0, colID);
			out.quickSetValue(i, 1, nColID);
			out.quickSetValue(i, 2, ni);
		}
		return out;
	}

	@Override
	public void updateIndexRanges(long[] beginDims, long[] endDims, int offset) {
		_columnEncoders.forEach(encoder -> encoder.updateIndexRanges(beginDims, endDims, offset));
		if(_legacyOmit != null)
			_legacyOmit.updateIndexRanges(beginDims, endDims);
		if(_legacyMVImpute != null)
			_legacyMVImpute.updateIndexRanges(beginDims, endDims);
	}

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeBoolean(_legacyMVImpute != null);
		if(_legacyMVImpute != null)
			_legacyMVImpute.writeExternal(out);
		out.writeBoolean(_legacyOmit != null);
		if(_legacyOmit != null)
			_legacyOmit.writeExternal(out);

		out.writeInt(_colOffset);
		out.writeInt(_columnEncoders.size());
		for(ColumnEncoder columnEncoder : _columnEncoders) {
			out.writeInt(columnEncoder._colID);
			columnEncoder.writeExternal(out);
		}
		out.writeBoolean(_meta != null);
		if(_meta != null)
			_meta.write(out);
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		if(in.readBoolean()) {
			_legacyMVImpute = new EncoderMVImpute();
			_legacyMVImpute.readExternal(in);
		}
		if(in.readBoolean()) {
			_legacyOmit = new EncoderOmit();
			_legacyOmit.readExternal(in);
		}

		_colOffset = in.readInt();
		int encodersSize = in.readInt();
		_columnEncoders = new ArrayList<>();
		for(int i = 0; i < encodersSize; i++) {
			int colID = in.readInt();
			ColumnEncoderComposite columnEncoder = new ColumnEncoderComposite();
			columnEncoder.readExternal(in);
			columnEncoder.setColID(colID);
			_columnEncoders.add(columnEncoder);
		}
		if(in.readBoolean()) {
			FrameBlock meta = new FrameBlock();
			meta.readFields(in);
			_meta = meta;
		}
	}

	public <T extends ColumnEncoder> List<T> getColumnEncoders(Class<T> type) {
		// TODO cache results for faster access
		List<T> ret = new ArrayList<>();
		for(ColumnEncoder encoder : _columnEncoders) {
			if(encoder.getClass().equals(ColumnEncoderComposite.class) && type != ColumnEncoderComposite.class) {
				encoder = ((ColumnEncoderComposite) encoder).getEncoder(type);
			}
			if(encoder != null && encoder.getClass().equals(type)) {
				ret.add(type.cast(encoder));
			}
		}
		return ret;
	}

	public <T extends ColumnEncoder> T getColumnEncoder(int colID, Class<T> type) {
		for(T encoder : getColumnEncoders(type)) {
			if(encoder._colID == colID) {
				return encoder;
			}
		}
		return null;
	}

	public <T extends ColumnEncoder, E> List<E> getFromAll(Class<T> type, Function<? super T, ? extends E> mapper) {
		return getColumnEncoders(type).stream().map(mapper).collect(Collectors.toList());
	}

	public <T extends ColumnEncoder> int[] getFromAllIntArray(Class<T> type,
		Function<? super T, ? extends Integer> mapper) {
		return getFromAll(type, mapper).stream().mapToInt(i -> i).toArray();
	}

	public <T extends ColumnEncoder> double[] getFromAllDoubleArray(Class<T> type,
		Function<? super T, ? extends Double> mapper) {
		return getFromAll(type, mapper).stream().mapToDouble(i -> i).toArray();
	}

	public List<ColumnEncoderComposite> getColumnEncoders() {
		return _columnEncoders;
	}

	public List<ColumnEncoderComposite> getCompositeEncodersForID(int colID) {
		return _columnEncoders.stream().filter(encoder -> encoder._colID == colID).collect(Collectors.toList());
	}

	public List<Class<? extends ColumnEncoder>> getEncoderTypes(int colID) {
		HashSet<Class<? extends ColumnEncoder>> set = new HashSet<>();
		for(ColumnEncoderComposite encoderComp : _columnEncoders) {
			if(encoderComp._colID != colID && colID != -1)
				continue;
			for(ColumnEncoder encoder : encoderComp.getEncoders()) {
				set.add(encoder.getClass());
			}
		}
		return new ArrayList<>(set);
	}

	public List<Class<? extends ColumnEncoder>> getEncoderTypes() {
		return getEncoderTypes(-1);
	}

	public int getNumExtraCols() {
		List<ColumnEncoderDummycode> dc = getColumnEncoders(ColumnEncoderDummycode.class);
		if(dc.isEmpty()) {
			return 0;
		}
		return dc.stream().map(ColumnEncoderDummycode::getDomainSize).mapToInt(i -> i).sum() - dc.size();
	}

	public int getNumExtraCols(IndexRange ixRange) {
		List<ColumnEncoderDummycode> dc = getColumnEncoders(ColumnEncoderDummycode.class).stream()
			.filter(dce -> ixRange.inColRange(dce._colID)).collect(Collectors.toList());
		if(dc.isEmpty()) {
			return 0;
		}
		return dc.stream().map(ColumnEncoderDummycode::getDomainSize).mapToInt(i -> i).sum() - dc.size();
	}

	public <T extends ColumnEncoder> boolean containsEncoderForID(int colID, Class<T> type) {
		return getColumnEncoders(type).stream().anyMatch(encoder -> encoder.getColID() == colID);
	}

	public <T extends ColumnEncoder, E> void applyToAll(Class<T> type, Consumer<? super T> function) {
		getColumnEncoders(type).forEach(function);
	}

	public <T extends ColumnEncoder, E> void applyToAll(Consumer<? super ColumnEncoderComposite> function) {
		getColumnEncoders().forEach(function);
	}

	public MultiColumnEncoder subRangeEncoder(IndexRange ixRange) {
		List<ColumnEncoderComposite> encoders = new ArrayList<>();
		for(long i = ixRange.colStart; i < ixRange.colEnd; i++) {
			encoders.addAll(getCompositeEncodersForID((int) i));
		}
		MultiColumnEncoder subRangeEncoder = new MultiColumnEncoder(encoders);
		subRangeEncoder._colOffset = (int) -ixRange.colStart + 1;
		if(_legacyOmit != null)
			subRangeEncoder.addReplaceLegacyEncoder(_legacyOmit.subRangeEncoder(ixRange));
		if(_legacyMVImpute != null)
			subRangeEncoder.addReplaceLegacyEncoder(_legacyMVImpute.subRangeEncoder(ixRange));
		return subRangeEncoder;
	}

	public <T extends ColumnEncoder> MultiColumnEncoder subRangeEncoder(IndexRange ixRange, Class<T> type) {
		List<T> encoders = new ArrayList<>();
		for(long i = ixRange.colStart; i < ixRange.colEnd; i++) {
			encoders.add(getColumnEncoder((int) i, type));
		}
		if(type.equals(ColumnEncoderComposite.class))
			return new MultiColumnEncoder(
				encoders.stream().map(e -> ((ColumnEncoderComposite) e)).collect(Collectors.toList()));
		else
			return new MultiColumnEncoder(
				encoders.stream().map(ColumnEncoderComposite::new).collect(Collectors.toList()));
	}

	public void mergeReplace(MultiColumnEncoder multiEncoder) {
		for(ColumnEncoderComposite otherEncoder : multiEncoder._columnEncoders) {
			ColumnEncoderComposite encoder = getColumnEncoder(otherEncoder._colID, otherEncoder.getClass());
			if(encoder != null) {
				_columnEncoders.remove(encoder);
			}
			_columnEncoders.add(otherEncoder);
		}
	}

	public void mergeAt(Encoder other, int columnOffset, int row) {
		if(other instanceof MultiColumnEncoder) {
			for(ColumnEncoder encoder : ((MultiColumnEncoder) other)._columnEncoders) {
				addEncoder(encoder, columnOffset);
			}
			// +1 since legacy function uses 1-based
			legacyMergeAt((MultiColumnEncoder) other, row, columnOffset + 1);
		}
		else {
			addEncoder((ColumnEncoder) other, columnOffset);
		}
	}

	private void legacyMergeAt(MultiColumnEncoder other, int row, int col) {
		if(other._legacyOmit != null)
			other._legacyOmit.shiftCols(col - 1);
		if(other._legacyOmit != null) {
			if(_legacyOmit == null)
				_legacyOmit = new EncoderOmit();
			_legacyOmit.mergeAt(other._legacyOmit, row, col);
		}

		if(other._legacyMVImpute != null)
			other._legacyMVImpute.shiftCols(col - 1);
		if(_legacyMVImpute != null && other._legacyMVImpute != null)
			_legacyMVImpute.mergeAt(other._legacyMVImpute, row, col);
		else if(_legacyMVImpute == null)
			_legacyMVImpute = other._legacyMVImpute;

	}

	private void addEncoder(ColumnEncoder encoder, int columnOffset) {
		// Check if same encoder exists
		int colId = encoder._colID + columnOffset;
		ColumnEncoder presentEncoder = getColumnEncoder(colId, encoder.getClass());
		if(presentEncoder != null) {
			encoder.shiftCol(columnOffset);
			presentEncoder.mergeAt(encoder);
		}
		else {
			// Check if CompositeEncoder for this colID exists
			ColumnEncoderComposite presentComposite = getColumnEncoder(colId, ColumnEncoderComposite.class);
			if(presentComposite != null) {
				// if here encoder can never be a CompositeEncoder
				encoder.shiftCol(columnOffset);
				presentComposite.mergeAt(encoder);
			}
			else {
				encoder.shiftCol(columnOffset);
				if(encoder instanceof ColumnEncoderComposite) {
					_columnEncoders.add((ColumnEncoderComposite) encoder);
				}
				else {
					_columnEncoders.add(new ColumnEncoderComposite(encoder));
				}
			}
		}
	}

	public <T extends LegacyEncoder> void addReplaceLegacyEncoder(T encoder) {
		if(encoder.getClass() == EncoderMVImpute.class) {
			_legacyMVImpute = (EncoderMVImpute) encoder;
		}
		else if(encoder.getClass().equals(EncoderOmit.class)) {
			_legacyOmit = (EncoderOmit) encoder;
		}
		else {
			throw new DMLRuntimeException("Tried to add non legacy Encoder");
		}
	}

	public <T extends LegacyEncoder> boolean hasLegacyEncoder(Class<T> type) {
		if(type.equals(EncoderMVImpute.class))
			return _legacyMVImpute != null;
		if(type.equals(EncoderOmit.class))
			return _legacyOmit != null;
		assert false;
		return false;
	}

	public <T extends LegacyEncoder> T getLegacyEncoder(Class<T> type) {
		if(type.equals(EncoderMVImpute.class))
			return type.cast(_legacyMVImpute);
		if(type.equals(EncoderOmit.class))
			return type.cast(_legacyOmit);
		assert false;
		return null;
	}

	/*
	 * This function applies the _columOffset to all encoders. Used in federated env.
	 */
	public void applyColumnOffset() {
		applyToAll(e -> e.shiftCol(_colOffset));
		if(_legacyOmit != null)
			_legacyOmit.shiftCols(_colOffset);
		if(_legacyMVImpute != null)
			_legacyMVImpute.shiftCols(_colOffset);
	}
}
