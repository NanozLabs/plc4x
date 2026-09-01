/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.plc4x.java.cjt188.readwrite;

import org.apache.plc4x.java.spi.buffers.api.Message;
import org.apache.plc4x.java.spi.buffers.api.ReadBuffer;
import org.apache.plc4x.java.spi.buffers.api.WithOption;
import org.apache.plc4x.java.spi.buffers.api.WriteBuffer;
import org.apache.plc4x.java.spi.buffers.api.exceptions.BufferException;
import org.apache.plc4x.java.spi.buffers.bytebased.WithByteBasedOption;
import org.apache.plc4x.java.spi.fields.data.reader.DataReaderFactory;
import org.apache.plc4x.java.spi.fields.data.writer.DataWriterFactory;
import org.apache.plc4x.java.spi.fields.fields.reader.FieldReaderFactory;
import org.apache.plc4x.java.spi.fields.fields.writer.FieldWriterFactory;
import org.apache.plc4x.java.spi.fields.utils.ThreadLocalHelper;

/**
 * Main frame type per CJ/T 188-2004 Section 6. Checksum CS = mod-256 sum of all bytes from first
 * 0x68 to before CS (standard-compliant). Data field is stored decoded (-0x33) in memory; checksum
 * uses STATIC_CALL with parsed fields. Address is 7 bytes.
 */
public class Cjt188Frame implements Message {

  // Constant values.
  public static final Short START1 = 0x68;
  public static final Short START2 = 0x68;
  public static final Short END = 0x16;

  // Properties.
  protected final byte[] address;
  protected final ControlCode control;
  protected final short length;
  protected final byte[] dataPlain;

  public Cjt188Frame(byte[] address, ControlCode control, short length, byte[] dataPlain) {
    super();
    this.address = address;
    this.control = control;
    this.length = length;
    this.dataPlain = dataPlain;
  }

  public byte[] getAddress() {
    return address;
  }

  public ControlCode getControl() {
    return control;
  }

  public short getLength() {
    return length;
  }

  public byte[] getDataPlain() {
    return dataPlain;
  }

  public short getStart1() {
    return START1;
  }

  public short getStart2() {
    return START2;
  }

  public short getEnd() {
    return END;
  }

  public void serialize(WriteBuffer writeBuffer) throws BufferException {
    writeBuffer.pushContext(WithOption.WithName("Cjt188Frame"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));
    int startPos = writeBuffer.getPositionInBits();
    boolean _lastItem = ThreadLocalHelper.lastItemThreadLocal.get();

    // Const Field (start1)
    FieldWriterFactory.writeConstField((short) START1, DataWriterFactory.writeUnsignedShort(writeBuffer, 8), WithOption.WithName("start1"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    // Array Field (address)
    FieldWriterFactory.writeByteArrayField(address, DataWriterFactory.writeByteArray(writeBuffer, (int) ((address != null) ? address.length : 0)), WithOption.WithName("address"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    // Const Field (start2)
    FieldWriterFactory.writeConstField((short) START2, DataWriterFactory.writeUnsignedShort(writeBuffer, 8), WithOption.WithName("start2"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    // Simple Field (control)
    FieldWriterFactory.writeSimpleEnumField((ControlCode) control, DataWriterFactory.writeEnum(ControlCode::getValue, ControlCode::name, DataWriterFactory.writeUnsignedShort(writeBuffer, 8)), WithOption.WithName("control"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    // Simple Field (length)
    FieldWriterFactory.writeSimpleField((short) length, DataWriterFactory.writeUnsignedShort(writeBuffer, 8), WithOption.WithName("length"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    // Manual Array Field (dataPlain)
    FieldWriterFactory.writeManualArrayField(dataPlain, (Byte _value) -> org.apache.plc4x.java.cjt188.readwrite.utils.StaticHelper.serializeDataBytePlus33(writeBuffer, _value), writeBuffer, WithOption.WithName("dataPlain"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    // Checksum Field (checksum) (Calculated)
    FieldWriterFactory.writeChecksumField((short) (org.apache.plc4x.java.cjt188.readwrite.utils.StaticHelper.calcCs(address, control, length, dataPlain)), DataWriterFactory.writeUnsignedShort(writeBuffer, 8), WithOption.WithName("cs"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    // Const Field (end)
    FieldWriterFactory.writeConstField((short) END, DataWriterFactory.writeUnsignedShort(writeBuffer, 8), WithOption.WithName("end"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    writeBuffer.popContext();
  }

  @Override
  public int getLengthInBytes() {
    return (int) Math.ceil((float) getLengthInBits() / 8.0);
  }

  @Override
  public int getLengthInBits() {
    int lengthInBits = 0;
    Cjt188Frame _value = this;
    boolean _lastItem = ThreadLocalHelper.lastItemThreadLocal.get();

    // Const Field (start1)
    lengthInBits += 8;

    // Array field
    if (address != null) {
      lengthInBits += 8 * address.length;
    }

    // Const Field (start2)
    lengthInBits += 8;

    // Simple field (control)
    lengthInBits += 8;

    // Simple field (length)
    lengthInBits += 8;

    // Manual Array Field (dataPlain)
    lengthInBits += length * 8;

    // Checksum Field (checksum)
    lengthInBits += 8;

    // Const Field (end)
    lengthInBits += 8;

    return lengthInBits;
  }

  public static Cjt188Frame staticParse(ReadBuffer readBuffer, boolean response)
      throws BufferException {
    readBuffer.pushContext(WithOption.WithName("Cjt188Frame"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));
    int startPos = readBuffer.getPositionInBits();
    boolean _lastItem = ThreadLocalHelper.lastItemThreadLocal.get();

    short start1 =
        FieldReaderFactory.readConstField(
            DataReaderFactory.readUnsignedShort(readBuffer, 8),
            Cjt188Frame.START1,
            WithOption.WithName("start1"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    byte[] address =
        readBuffer.readBits(
            Math.toIntExact(7 * 8),
            WithOption.WithName("address"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    short start2 =
        FieldReaderFactory.readConstField(
            DataReaderFactory.readUnsignedShort(readBuffer, 8),
            Cjt188Frame.START2,
            WithOption.WithName("start2"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    ControlCode control =
        FieldReaderFactory.readEnumField(
            DataReaderFactory.readEnum(ControlCode::enumForValue, DataReaderFactory.readUnsignedShort(readBuffer, 8)),
            WithOption.WithName("control"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    short length =
        FieldReaderFactory.readSimpleField(
            DataReaderFactory.readUnsignedShort(readBuffer, 8),
            WithOption.WithName("length"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    byte[] dataPlain =
        FieldReaderFactory.readManualByteArrayField(
            readBuffer,
            (java.util.List<Byte> _values) -> (boolean) ((_values.size()) == (length)),
            () -> (byte) (org.apache.plc4x.java.cjt188.readwrite.utils.StaticHelper.parseDataByteMinus33(readBuffer)),
            WithOption.WithName("dataPlain"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    short cs =
        FieldReaderFactory.readChecksumField(
            DataReaderFactory.readUnsignedShort(readBuffer, 8),
            (short) (org.apache.plc4x.java.cjt188.readwrite.utils.StaticHelper.calcCs(address, control, length, dataPlain)),
            WithOption.WithName("cs"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    short end =
        FieldReaderFactory.readConstField(
            DataReaderFactory.readUnsignedShort(readBuffer, 8),
            Cjt188Frame.END,
            WithOption.WithName("end"), WithOption.WithFloatEncoding("IEEE754"), WithOption.WithSignedIntegerEncoding("twos-complement"), WithOption.WithUnsignedIntegerEncoding("unsigned-binary"), WithByteBasedOption.WithByteOrder("BIG_ENDIAN"), WithOption.WithStringEncoding("UTF8"));

    readBuffer.popContext();
    // Create the instance
    Cjt188Frame _cjt188Frame;
    _cjt188Frame = new Cjt188Frame(address, control, length, dataPlain);
    return _cjt188Frame;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Cjt188Frame)) {
      return false;
    }
    Cjt188Frame that = (Cjt188Frame) o;
    return (getAddress() == that.getAddress())
        && (getControl() == that.getControl())
        && (getLength() == that.getLength())
        && (getDataPlain() == that.getDataPlain())
        && true;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(getAddress(), getControl(), getLength(), getDataPlain());
  }

  @Override
  public String toString() {
    return "Cjt188Frame{" +
        "address=" + java.util.Arrays.toString(address) +
        ", control=" + control +
        ", length=" + length +
        ", dataPlain=" + java.util.Arrays.toString(dataPlain) +
        '}';
  }
}
