/*
 * Copyright 2022 ST-Lab
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 */

package org.apache.calcite.avatica.util;

import org.apache.calcite.avatica.AvaticaSite;
import org.apache.calcite.avatica.AvaticaUtils;
import org.apache.calcite.avatica.ColumnMetaData;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Base class for implementing a cursor.
 *
 * <p>Derived class needs to provide {@link Getter} and can override
 * {@link org.apache.calcite.avatica.util.Cursor.Accessor} implementations if it
 * wishes.</p>
 */
public abstract class AbstractCursor implements Cursor {
    /**
     * Slot into which each accessor should write whether the
     * value returned was null.
     */
    protected final boolean[] wasNull = { false };

    protected AbstractCursor() {}

    public boolean wasNull() {
        return wasNull[0];
    }

    public List<Accessor> createAccessors(
        List<ColumnMetaData> types,
        Calendar localCalendar,
        ArrayImpl.Factory factory
    ) {
        List<Accessor> accessors = new ArrayList<>();
        for (ColumnMetaData type : types) {
            accessors.add(createAccessor(type, accessors.size(), localCalendar, factory));
        }
        return accessors;
    }

    protected Accessor createAccessor(
        ColumnMetaData columnMetaData,
        int ordinal,
        Calendar localCalendar,
        ArrayImpl.Factory factory
    ) {
        // Create an accessor appropriate to the underlying type; the accessor
        // can convert to any type in the same family.
        Getter getter = createGetter(ordinal);
        return createAccessor(columnMetaData, getter, localCalendar, factory);
    }

    protected Accessor createAccessor(
        ColumnMetaData columnMetaData,
        Getter getter,
        Calendar localCalendar,
        ArrayImpl.Factory factory
    ) {
        switch (columnMetaData.type.rep) {
            case NUMBER:
                switch (columnMetaData.type.id) {
                    case Types.TINYINT:
                    case Types.SMALLINT:
                    case Types.INTEGER:
                    case Types.BIGINT:
                    case Types.REAL:
                    case Types.FLOAT:
                    case Types.DOUBLE:
                    case Types.NUMERIC:
                    case Types.DECIMAL:
                        return new NumberAccessor(getter, columnMetaData.scale);
                }
        }
        switch (columnMetaData.type.id) {
            case Types.TINYINT:
                return new ByteAccessor(getter);
            case Types.SMALLINT:
                return new ShortAccessor(getter);
            case Types.INTEGER:
                return new IntAccessor(getter);
            case Types.BIGINT:
                return new LongAccessor(getter);
            case Types.BOOLEAN:
                return new BooleanAccessor(getter);
            case Types.REAL:
                return new FloatAccessor(getter);
            case Types.FLOAT:
            case Types.DOUBLE:
                return new DoubleAccessor(getter);
            case Types.DECIMAL:
                return new BigDecimalAccessor(getter);
            case Types.CHAR:
                switch (columnMetaData.type.rep) {
                    case PRIMITIVE_CHAR:
                    case CHARACTER:
                        return new StringFromCharAccessor(getter, columnMetaData.displaySize);
                    default:
                        return new FixedStringAccessor(getter, columnMetaData.displaySize);
                }
            case Types.VARCHAR:
                return new StringAccessor(getter);
            case Types.BINARY:
            case Types.VARBINARY:
                switch (columnMetaData.type.rep) {
                    case STRING:
                        return new BinaryFromStringAccessor(getter);
                    default:
                        return new BinaryAccessor(getter);
                }
            case Types.DATE:
                switch (columnMetaData.type.rep) {
                    case PRIMITIVE_INT:
                    case INTEGER:
                    case NUMBER:
                        return new DateFromNumberAccessor(getter, localCalendar);
                    case JAVA_SQL_DATE:
                        return new DateAccessor(getter);
                    default:
                        throw new AssertionError("bad " + columnMetaData.type.rep);
                }
            case Types.TIME:
                switch (columnMetaData.type.rep) {
                    case PRIMITIVE_INT:
                    case INTEGER:
                    case NUMBER:
                        return new TimeFromNumberAccessor(getter, localCalendar);
                    case JAVA_SQL_TIME:
                        return new TimeAccessor(getter);
                    default:
                        throw new AssertionError("bad " + columnMetaData.type.rep);
                }
            case Types.TIMESTAMP:
                switch (columnMetaData.type.rep) {
                    case PRIMITIVE_LONG:
                    case LONG:
                    case NUMBER:
                        return new TimestampFromNumberAccessor(getter, localCalendar);
                    case JAVA_SQL_TIMESTAMP:
                        return new TimestampAccessor(getter);
                    case JAVA_UTIL_DATE:
                        return new TimestampFromUtilDateAccessor(getter, localCalendar);
                    default:
                        throw new AssertionError("bad " + columnMetaData.type.rep);
                }
            case 2013: // TIME_WITH_TIMEZONE
                switch (columnMetaData.type.rep) {
                    case STRING:
                        return new StringAccessor(getter);
                    default:
                        throw new AssertionError("bad " + columnMetaData.type.rep);
                }
            case 2014: // TIMESTAMP_WITH_TIMEZONE
                switch (columnMetaData.type.rep) {
                    case STRING:
                        return new StringAccessor(getter);
                    default:
                        throw new AssertionError("bad " + columnMetaData.type.rep);
                }
            case Types.ARRAY:
                final ColumnMetaData.ArrayType arrayType =
                    (ColumnMetaData.ArrayType) columnMetaData.type;
                final SlotGetter componentGetter = new SlotGetter();
                final Accessor componentAccessor = createAccessor(
                    ColumnMetaData.dummy(arrayType.getComponent(), true),
                    componentGetter,
                    localCalendar,
                    factory
                );
                return new ArrayAccessor(
                    getter,
                    arrayType.getComponent(),
                    componentAccessor,
                    componentGetter,
                    factory
                );
            case Types.STRUCT:
                switch (columnMetaData.type.rep) {
                    case OBJECT:
                        final ColumnMetaData.StructType structType =
                            (ColumnMetaData.StructType) columnMetaData.type;
                        List<Accessor> accessors = new ArrayList<>();
                        for (ColumnMetaData column : structType.columns) {
                            accessors.add(
                                createAccessor(
                                    column,
                                    new StructGetter(getter, column),
                                    localCalendar,
                                    factory
                                )
                            );
                        }
                        return new StructAccessor(getter, accessors);
                    default:
                        throw new AssertionError("bad " + columnMetaData.type.rep);
                }
            case Types.JAVA_OBJECT:
            case Types.OTHER: // e.g. map
                if (columnMetaData.type.getName().startsWith("INTERVAL_")) {
                    int end = columnMetaData.type.getName().indexOf("(");
                    if (end < 0) {
                        end = columnMetaData.type.getName().length();
                    }
                    TimeUnitRange range = TimeUnitRange.valueOf(
                        columnMetaData.type.getName().substring("INTERVAL_".length(), end)
                    );
                    if (range.monthly()) {
                        return new IntervalYearMonthAccessor(getter, range);
                    } else {
                        return new IntervalDayTimeAccessor(getter, range, columnMetaData.scale);
                    }
                }
                return new ObjectAccessor(getter);
            default:
                throw new RuntimeException("unknown type " + columnMetaData.type.id);
        }
    }

    protected abstract Getter createGetter(int ordinal);

    public abstract boolean next();

    /**
     * Accesses a timestamp value as a string.
     * The timestamp is in SQL format (e.g. "2013-09-22 22:30:32"),
     * not Java format ("2013-09-22 22:30:32.123").
     */
    private static String timestampAsString(long v, Calendar calendar) {
        if (calendar != null) {
            v -= calendar.getTimeZone().getOffset(v);
        }
        return DateTimeUtils.unixTimestampToString(v);
    }

    /**
     * Accesses a date value as a string, e.g. "2013-09-22".
     */
    private static String dateAsString(int v, Calendar calendar) {
        AvaticaUtils.discard(calendar); // time zone shift doesn't make sense
        return DateTimeUtils.unixDateToString(v);
    }

    /**
     * Accesses a time value as a string, e.g. "22:30:32".
     */
    private static String timeAsString(int v, Calendar calendar) {
        if (calendar != null) {
            v -= calendar.getTimeZone().getOffset(v);
        }
        return DateTimeUtils.unixTimeToString(v);
    }

    private static Date longToDate(long v, Calendar calendar) {
        if (calendar != null) {
            v -= calendar.getTimeZone().getOffset(v);
        }
        return new Date(v);
    }

    static Time intToTime(int v, Calendar calendar) {
        if (calendar != null) {
            v -= calendar.getTimeZone().getOffset(v);
        }
        return new Time(v);
    }

    static Timestamp longToTimestamp(long v, Calendar calendar) {
        if (calendar != null) {
            v -= calendar.getTimeZone().getOffset(v);
        }
        return new Timestamp(v);
    }

    /**
     * Implementation of {@link Cursor.Accessor}.
     */
    static class AccessorImpl implements Accessor {
        protected final Getter getter;

        AccessorImpl(Getter getter) {
            assert getter != null;
            this.getter = getter;
        }

        public boolean wasNull() throws SQLException {
            return getter.wasNull();
        }

        public String getString() throws SQLException {
            final Object o = getObject();
            return o == null ? null : o.toString();
        }

        public boolean getBoolean() throws SQLException {
            return getLong() != 0L;
        }

        public byte getByte() throws SQLException {
            return (byte) getLong();
        }

        public short getShort() throws SQLException {
            return (short) getLong();
        }

        public int getInt() throws SQLException {
            return (int) getLong();
        }

        public long getLong() throws SQLException {
            throw cannotConvert("long");
        }

        public float getFloat() throws SQLException {
            return (float) getDouble();
        }

        public double getDouble() throws SQLException {
            throw cannotConvert("double");
        }

        public BigDecimal getBigDecimal() throws SQLException {
            throw cannotConvert("BigDecimal");
        }

        public BigDecimal getBigDecimal(int scale) throws SQLException {
            throw cannotConvert("BigDecimal with scale");
        }

        public byte[] getBytes() throws SQLException {
            throw cannotConvert("byte[]");
        }

        public InputStream getAsciiStream() throws SQLException {
            throw cannotConvert("InputStream (ascii)");
        }

        public InputStream getUnicodeStream() throws SQLException {
            throw cannotConvert("InputStream (unicode)");
        }

        public InputStream getBinaryStream() throws SQLException {
            throw cannotConvert("InputStream (binary)");
        }

        public Object getObject() throws SQLException {
            return getter.getObject();
        }

        public Reader getCharacterStream() throws SQLException {
            throw cannotConvert("Reader");
        }

        private SQLException cannotConvert(String targetType) throws SQLException {
            return new SQLDataException("cannot convert to " + targetType + " (" + this + ")");
        }

        public Object getObject(Map<String, Class<?>> map) throws SQLException {
            throw cannotConvert("Object (with map)");
        }

        public Ref getRef() throws SQLException {
            throw cannotConvert("Ref");
        }

        public Blob getBlob() throws SQLException {
            throw cannotConvert("Blob");
        }

        public Clob getClob() throws SQLException {
            throw cannotConvert("Clob");
        }

        public Array getArray() throws SQLException {
            throw cannotConvert("Array");
        }

        public Struct getStruct() throws SQLException {
            throw cannotConvert("Struct");
        }

        public Date getDate(Calendar calendar) throws SQLException {
            throw cannotConvert("Date");
        }

        public Time getTime(Calendar calendar) throws SQLException {
            throw cannotConvert("Time");
        }

        public Timestamp getTimestamp(Calendar calendar) throws SQLException {
            throw cannotConvert("Timestamp");
        }

        public URL getURL() throws SQLException {
            throw cannotConvert("URL");
        }

        public NClob getNClob() throws SQLException {
            throw cannotConvert("NClob");
        }

        public SQLXML getSQLXML() throws SQLException {
            throw cannotConvert("SQLXML");
        }

        public String getNString() throws SQLException {
            throw cannotConvert("NString");
        }

        public Reader getNCharacterStream() throws SQLException {
            throw cannotConvert("NCharacterStream");
        }

        public <T> T getObject(Class<T> type) throws SQLException {
            throw cannotConvert("Object (with type)");
        }
    }

    /**
     * Accessor of exact numeric values. The subclass must implement the
     * {@link #getLong()} method.
     */
    private abstract static class ExactNumericAccessor extends AccessorImpl {
        private ExactNumericAccessor(Getter getter) {
            super(getter);
        }

        public BigDecimal getBigDecimal(int scale) throws SQLException {
            final long v = getLong();
            if (v == 0 && getter.wasNull()) {
                return null;
            }
            return BigDecimal.valueOf(v).setScale(scale, RoundingMode.DOWN);
        }

        public BigDecimal getBigDecimal() throws SQLException {
            final long val = getLong();
            if (val == 0 && getter.wasNull()) {
                return null;
            }
            return BigDecimal.valueOf(val);
        }

        public double getDouble() throws SQLException {
            return getLong();
        }

        public float getFloat() throws SQLException {
            return getLong();
        }

        public abstract long getLong() throws SQLException;
    }

    /**
     * Accessor that assumes that the underlying value is a {@link Boolean};
     * corresponds to {@link java.sql.Types#BOOLEAN}.
     */
    private static class BooleanAccessor extends ExactNumericAccessor {
        private BooleanAccessor(Getter getter) {
            super(getter);
        }

        public boolean getBoolean() throws SQLException {
            Boolean o = (Boolean) getObject();
            return o != null && o;
        }

        public long getLong() throws SQLException {
            return getBoolean() ? 1 : 0;
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link Byte};
     * corresponds to {@link java.sql.Types#TINYINT}.
     */
    private static class ByteAccessor extends ExactNumericAccessor {
        private ByteAccessor(Getter getter) {
            super(getter);
        }

        public byte getByte() throws SQLException {
            Object obj = getObject();
            if (null == obj) {
                return 0;
            } else if (obj instanceof Integer) {
                return ((Integer) obj).byteValue();
            }
            return (Byte) obj;
        }

        public long getLong() throws SQLException {
            return getByte();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link Short};
     * corresponds to {@link java.sql.Types#SMALLINT}.
     */
    private static class ShortAccessor extends ExactNumericAccessor {
        private ShortAccessor(Getter getter) {
            super(getter);
        }

        public short getShort() throws SQLException {
            Object obj = getObject();
            if (null == obj) {
                return 0;
            } else if (obj instanceof Integer) {
                return ((Integer) obj).shortValue();
            }
            return (Short) obj;
        }

        public long getLong() throws SQLException {
            return getShort();
        }
    }

    /**
     * Accessor that assumes that the underlying value is an {@link Integer};
     * corresponds to {@link java.sql.Types#INTEGER}.
     */
    private static class IntAccessor extends ExactNumericAccessor {
        private IntAccessor(Getter getter) {
            super(getter);
        }

        public int getInt() throws SQLException {
            Integer o = (Integer) super.getObject();
            return o == null ? 0 : o;
        }

        public long getLong() throws SQLException {
            return getInt();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link Long};
     * corresponds to {@link java.sql.Types#BIGINT}.
     */
    private static class LongAccessor extends ExactNumericAccessor {
        private LongAccessor(Getter getter) {
            super(getter);
        }

        public long getLong() throws SQLException {
            Long o = (Long) super.getObject();
            return o == null ? 0 : o;
        }
    }

    /**
     * Accessor of values that are {@link Double} or null.
     */
    private abstract static class ApproximateNumericAccessor extends AccessorImpl {
        private ApproximateNumericAccessor(Getter getter) {
            super(getter);
        }

        public BigDecimal getBigDecimal(int scale) throws SQLException {
            final double v = getDouble();
            if (v == 0d && getter.wasNull()) {
                return null;
            }
            return BigDecimal.valueOf(v).setScale(scale, RoundingMode.DOWN);
        }

        public BigDecimal getBigDecimal() throws SQLException {
            final double v = getDouble();
            if (v == 0 && getter.wasNull()) {
                return null;
            }
            return BigDecimal.valueOf(v);
        }

        public abstract double getDouble() throws SQLException;

        public long getLong() throws SQLException {
            return (long) getDouble();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link Float};
     * corresponds to {@link java.sql.Types#FLOAT}.
     */
    private static class FloatAccessor extends ApproximateNumericAccessor {
        private FloatAccessor(Getter getter) {
            super(getter);
        }

        public float getFloat() throws SQLException {
            Float o = (Float) getObject();
            return o == null ? 0f : o;
        }

        public double getDouble() throws SQLException {
            return getFloat();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link Double};
     * corresponds to {@link java.sql.Types#DOUBLE}.
     */
    private static class DoubleAccessor extends ApproximateNumericAccessor {
        private DoubleAccessor(Getter getter) {
            super(getter);
        }

        public double getDouble() throws SQLException {
            Object obj = getObject();
            if (null == obj) {
                return 0d;
            } else if (obj instanceof BigDecimal) {
                return ((BigDecimal) obj).doubleValue();
            }
            return (Double) obj;
        }
    }

    /**
     * Accessor of exact numeric values. The subclass must implement the
     * {@link #getLong()} method.
     */
    private abstract static class BigNumberAccessor extends AccessorImpl {
        private BigNumberAccessor(Getter getter) {
            super(getter);
        }

        protected abstract Number getNumber() throws SQLException;

        public double getDouble() throws SQLException {
            Number number = getNumber();
            return number == null ? 0d : number.doubleValue();
        }

        public float getFloat() throws SQLException {
            Number number = getNumber();
            return number == null ? 0f : number.floatValue();
        }

        public long getLong() throws SQLException {
            Number number = getNumber();
            return number == null ? 0L : number.longValue();
        }

        public int getInt() throws SQLException {
            Number number = getNumber();
            return number == null ? 0 : number.intValue();
        }

        public short getShort() throws SQLException {
            Number number = getNumber();
            return number == null ? 0 : number.shortValue();
        }

        public byte getByte() throws SQLException {
            Number number = getNumber();
            return number == null ? 0 : number.byteValue();
        }

        public boolean getBoolean() throws SQLException {
            Number number = getNumber();
            return number != null && number.doubleValue() != 0;
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link BigDecimal};
     * corresponds to {@link java.sql.Types#DECIMAL}.
     */
    private static class BigDecimalAccessor extends BigNumberAccessor {
        private BigDecimalAccessor(Getter getter) {
            super(getter);
        }

        protected Number getNumber() throws SQLException {
            return (Number) getObject();
        }

        public BigDecimal getBigDecimal(int scale) throws SQLException {
            return (BigDecimal) getObject();
        }

        public BigDecimal getBigDecimal() throws SQLException {
            return (BigDecimal) getObject();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link Number};
     * corresponds to {@link java.sql.Types#NUMERIC}.
     *
     * <p>This is useful when numbers have been translated over JSON. JSON
     * converts a 0L (0 long) value to the string "0" and back to 0 (0 int).
     * So you cannot be sure that the source and target type are the same.
     */
    static class NumberAccessor extends BigNumberAccessor {
        private final int scale;

        NumberAccessor(Getter getter, int scale) {
            super(getter);
            this.scale = scale;
        }

        protected Number getNumber() throws SQLException {
            return (Number) super.getObject();
        }

        public BigDecimal getBigDecimal(int scale) throws SQLException {
            Number n = getNumber();
            if (n == null) {
                return null;
            }
            BigDecimal decimal = AvaticaSite.toBigDecimal(n);
            if (0 != scale) {
                return decimal.setScale(scale, RoundingMode.UNNECESSARY);
            }
            return decimal;
        }

        public BigDecimal getBigDecimal() throws SQLException {
            return getBigDecimal(scale);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link String};
     * corresponds to {@link java.sql.Types#CHAR}
     * and {@link java.sql.Types#VARCHAR}.
     */
    private static class StringAccessor extends AccessorImpl {
        private StringAccessor(Getter getter) {
            super(getter);
        }

        public String getString() throws SQLException {
            final Object obj = getObject();
            if (obj instanceof String) {
                return (String) obj;
            }
            return null == obj ? null : obj.toString();
        }

        @Override
        public byte[] getBytes() throws SQLException {
            return super.getBytes();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link String};
     * corresponds to {@link java.sql.Types#CHAR}.
     */
    private static class FixedStringAccessor extends StringAccessor {
        protected final Spacer spacer;

        private FixedStringAccessor(Getter getter, int length) {
            super(getter);
            this.spacer = new Spacer(length);
        }

        public String getString() throws SQLException {
            String s = super.getString();
            if (s == null) {
                return null;
            }
            return spacer.padRight(s);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link String};
     * corresponds to {@link java.sql.Types#CHAR}.
     */
    private static class StringFromCharAccessor extends FixedStringAccessor {
        private StringFromCharAccessor(Getter getter, int length) {
            super(getter, length);
        }

        public String getString() throws SQLException {
            Character s = (Character) super.getObject();
            if (s == null) {
                return null;
            }
            return spacer.padRight(s.toString());
        }
    }

    /**
     * Accessor that assumes that the underlying value is an array of
     * {@link org.apache.calcite.avatica.util.ByteString} values;
     * corresponds to {@link java.sql.Types#BINARY}
     * and {@link java.sql.Types#VARBINARY}.
     */
    private static class BinaryAccessor extends AccessorImpl {
        private BinaryAccessor(Getter getter) {
            super(getter);
        }

        // FIXME: Protobuf gets byte[]
        @Override
        public byte[] getBytes() throws SQLException {
            Object obj = getObject();
            if (null == obj) {
                return null;
            }
            if (obj instanceof ByteString) {
                return ((ByteString) obj).getBytes();
            } else if (obj instanceof String) {
                // Need to unwind the base64 for JSON
                return ByteString.parseBase64((String) obj);
            } else if (obj instanceof byte[]) {
                // Protobuf would have a byte array
                return (byte[]) obj;
            } else {
                throw new RuntimeException("Cannot handle " + obj.getClass() + " as bytes");
            }
        }

        @Override
        public String getString() throws SQLException {
            Object o = getObject();
            if (null == o) {
                return null;
            }
            if (o instanceof byte[]) {
                return new String((byte[]) o, StandardCharsets.UTF_8);
            } else if (o instanceof ByteString) {
                return ((ByteString) o).toString();
            }
            throw new IllegalStateException("Unhandled value type: " + o.getClass());
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@link String},
     * encoding {@link java.sql.Types#BINARY}
     * and {@link java.sql.Types#VARBINARY} values in Base64 format.
     */
    private static class BinaryFromStringAccessor extends StringAccessor {
        private BinaryFromStringAccessor(Getter getter) {
            super(getter);
        }

        @Override
        public Object getObject() throws SQLException {
            return super.getObject();
        }

        @Override
        public byte[] getBytes() throws SQLException {
            // JSON sends this as a base64-enc string, protobuf can do binary.
            Object obj = getObject();

            if (obj instanceof byte[]) {
                // If we already have bytes, just send them back.
                return (byte[]) obj;
            }

            return getBase64Decoded();
        }

        private byte[] getBase64Decoded() throws SQLException {
            final String string = super.getString();
            if (null == string) {
                return null;
            }
            // Need to base64 decode the string.
            return ByteString.parseBase64(string);
        }

        @Override
        public String getString() throws SQLException {
            final byte[] bytes = getBase64Decoded();
            if (null == bytes) {
                return null;
            }
            // Need to base64 decode the string.
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a DATE,
     * in its default representation {@code int};
     * corresponds to {@link java.sql.Types#DATE}.
     */
    private static class DateFromNumberAccessor extends NumberAccessor {
        private final Calendar localCalendar;

        private DateFromNumberAccessor(Getter getter, Calendar localCalendar) {
            super(getter, 0);
            this.localCalendar = localCalendar;
        }

        @Override
        public Object getObject() throws SQLException {
            return getDate(localCalendar);
        }

        @Override
        public Date getDate(Calendar calendar) throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return longToDate(v.longValue() * DateTimeUtils.MILLIS_PER_DAY, calendar);
        }

        @Override
        public Timestamp getTimestamp(Calendar calendar) throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return longToTimestamp(v.longValue() * DateTimeUtils.MILLIS_PER_DAY, calendar);
        }

        @Override
        public String getString() throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return dateAsString(v.intValue(), null);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a Time,
     * in its default representation {@code int};
     * corresponds to {@link java.sql.Types#TIME}.
     */
    private static class TimeFromNumberAccessor extends NumberAccessor {
        private final Calendar localCalendar;

        private TimeFromNumberAccessor(Getter getter, Calendar localCalendar) {
            super(getter, 0);
            this.localCalendar = localCalendar;
        }

        @Override
        public Object getObject() throws SQLException {
            return getTime(localCalendar);
        }

        @Override
        public Time getTime(Calendar calendar) throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return intToTime(v.intValue(), calendar);
        }

        @Override
        public Timestamp getTimestamp(Calendar calendar) throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return longToTimestamp(v.longValue(), calendar);
        }

        @Override
        public String getString() throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return timeAsString(v.intValue(), null);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a TIMESTAMP,
     * in its default representation {@code long};
     * corresponds to {@link java.sql.Types#TIMESTAMP}.
     */
    private static class TimestampFromNumberAccessor extends NumberAccessor {
        private final Calendar localCalendar;

        private TimestampFromNumberAccessor(Getter getter, Calendar localCalendar) {
            super(getter, 0);
            this.localCalendar = localCalendar;
        }

        @Override
        public Object getObject() throws SQLException {
            return getTimestamp(localCalendar);
        }

        @Override
        public Timestamp getTimestamp(Calendar calendar) throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return longToTimestamp(v.longValue(), calendar);
        }

        @Override
        public Date getDate(Calendar calendar) throws SQLException {
            final Timestamp timestamp = getTimestamp(calendar);
            if (timestamp == null) {
                return null;
            }
            return new Date(timestamp.getTime());
        }

        @Override
        public Time getTime(Calendar calendar) throws SQLException {
            final Timestamp timestamp = getTimestamp(calendar);
            if (timestamp == null) {
                return null;
            }
            return new Time(
                DateTimeUtils.floorMod(timestamp.getTime(), DateTimeUtils.MILLIS_PER_DAY)
            );
        }

        @Override
        public String getString() throws SQLException {
            final Number v = getNumber();
            if (v == null) {
                return null;
            }
            return timestampAsString(v.longValue(), null);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a DATE,
     * represented as a java.sql.Date;
     * corresponds to {@link java.sql.Types#DATE}.
     */
    private static class DateAccessor extends ObjectAccessor {
        private DateAccessor(Getter getter) {
            super(getter);
        }

        @Override
        public Date getDate(Calendar calendar) throws SQLException {
            java.sql.Date date = (Date) getObject();
            if (date == null) {
                return null;
            }
            if (calendar != null) {
                long v = date.getTime();
                v -= calendar.getTimeZone().getOffset(v);
                date = new Date(v);
            }
            return date;
        }

        @Override
        public String getString() throws SQLException {
            final int v = getInt();
            if (v == 0 && wasNull()) {
                return null;
            }
            return dateAsString(v, null);
        }

        @Override
        public long getLong() throws SQLException {
            Date date = getDate(null);
            return date == null ? 0L : (date.getTime() / DateTimeUtils.MILLIS_PER_DAY);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a TIME,
     * represented as a java.sql.Time;
     * corresponds to {@link java.sql.Types#TIME}.
     */
    private static class TimeAccessor extends ObjectAccessor {
        private TimeAccessor(Getter getter) {
            super(getter);
        }

        @Override
        public Time getTime(Calendar calendar) throws SQLException {
            Time date = (Time) getObject();
            if (date == null) {
                return null;
            }
            if (calendar != null) {
                long v = date.getTime();
                v -= calendar.getTimeZone().getOffset(v);
                date = new Time(v);
            }
            return date;
        }

        @Override
        public String getString() throws SQLException {
            final int v = getInt();
            if (v == 0 && wasNull()) {
                return null;
            }
            return timeAsString(v, null);
        }

        @Override
        public long getLong() throws SQLException {
            Time time = getTime(null);
            return time == null ? 0L : (time.getTime() % DateTimeUtils.MILLIS_PER_DAY);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a TIMESTAMP,
     * represented as a java.sql.Timestamp;
     * corresponds to {@link java.sql.Types#TIMESTAMP}.
     */
    private static class TimestampAccessor extends ObjectAccessor {
        private TimestampAccessor(Getter getter) {
            super(getter);
        }

        @Override
        public Timestamp getTimestamp(Calendar calendar) throws SQLException {
            // start-db modify start
            final Object obj = getObject();
            Timestamp timestamp = null;
            if (obj instanceof Long) {
                timestamp = new Timestamp((Long) obj);
            } else if (obj instanceof Timestamp) {
                timestamp = (Timestamp) obj;
            }
            // start-db modify end
            if (timestamp == null) {
                return null;
            }
            if (calendar != null) {
                long v = timestamp.getTime();
                v -= calendar.getTimeZone().getOffset(v);
                timestamp = new Timestamp(v);
            }
            return timestamp;
        }

        @Override
        public Date getDate(Calendar calendar) throws SQLException {
            final Timestamp timestamp = getTimestamp(calendar);
            if (timestamp == null) {
                return null;
            }
            return new Date(timestamp.getTime());
        }

        @Override
        public Time getTime(Calendar calendar) throws SQLException {
            final Timestamp timestamp = getTimestamp(calendar);
            if (timestamp == null) {
                return null;
            }
            return new Time(
                DateTimeUtils.floorMod(timestamp.getTime(), DateTimeUtils.MILLIS_PER_DAY)
            );
        }

        @Override
        public String getString() throws SQLException {
            final long v = getLong();
            if (v == 0 && wasNull()) {
                return null;
            }
            return timestampAsString(v, null);
        }

        @Override
        public long getLong() throws SQLException {
            Timestamp timestamp = getTimestamp(null);
            return timestamp == null ? 0 : timestamp.getTime();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a TIMESTAMP,
     * represented as a java.util.Date;
     * corresponds to {@link java.sql.Types#TIMESTAMP}.
     */
    private static class TimestampFromUtilDateAccessor extends ObjectAccessor {
        private final Calendar localCalendar;

        private TimestampFromUtilDateAccessor(Getter getter, Calendar localCalendar) {
            super(getter);
            this.localCalendar = localCalendar;
        }

        @Override
        public Timestamp getTimestamp(Calendar calendar) throws SQLException {
            java.util.Date date = (java.util.Date) getObject();
            if (date == null) {
                return null;
            }
            long v = date.getTime();
            if (calendar != null) {
                v -= calendar.getTimeZone().getOffset(v);
            }
            return new Timestamp(v);
        }

        @Override
        public Date getDate(Calendar calendar) throws SQLException {
            final Timestamp timestamp = getTimestamp(calendar);
            if (timestamp == null) {
                return null;
            }
            return new Date(timestamp.getTime());
        }

        @Override
        public Time getTime(Calendar calendar) throws SQLException {
            final Timestamp timestamp = getTimestamp(calendar);
            if (timestamp == null) {
                return null;
            }
            return new Time(
                DateTimeUtils.floorMod(timestamp.getTime(), DateTimeUtils.MILLIS_PER_DAY)
            );
        }

        @Override
        public String getString() throws SQLException {
            java.util.Date date = (java.util.Date) getObject();
            if (date == null) {
                return null;
            }
            return timestampAsString(date.getTime(), null);
        }

        @Override
        public long getLong() throws SQLException {
            Timestamp timestamp = getTimestamp(localCalendar);
            return timestamp == null ? 0 : timestamp.getTime();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@code int};
     * corresponds to {@link java.sql.Types#OTHER}.
     */
    private static class IntervalYearMonthAccessor extends IntAccessor {
        private final TimeUnitRange range;

        private IntervalYearMonthAccessor(Getter getter, TimeUnitRange range) {
            super(getter);
            this.range = range;
        }

        @Override
        public String getString() throws SQLException {
            final int v = getInt();
            if (v == 0 && wasNull()) {
                return null;
            }
            return DateTimeUtils.intervalYearMonthToString(v, range);
        }
    }

    /**
     * Accessor that assumes that the underlying value is a {@code long};
     * corresponds to {@link java.sql.Types#OTHER}.
     */
    private static class IntervalDayTimeAccessor extends LongAccessor {
        private final TimeUnitRange range;
        private final int scale;

        private IntervalDayTimeAccessor(Getter getter, TimeUnitRange range, int scale) {
            super(getter);
            this.range = range;
            this.scale = scale;
        }

        @Override
        public String getString() throws SQLException {
            final long v = getLong();
            if (v == 0 && wasNull()) {
                return null;
            }
            return DateTimeUtils.intervalDayTimeToString(v, range, scale);
        }
    }

    /**
     * Accessor that assumes that the underlying value is an ARRAY;
     * corresponds to {@link java.sql.Types#ARRAY}.
     */
    public static class ArrayAccessor extends AccessorImpl {
        final ColumnMetaData.AvaticaType componentType;
        final Accessor componentAccessor;
        final SlotGetter componentSlotGetter;
        final ArrayImpl.Factory factory;

        public ArrayAccessor(
            Getter getter,
            ColumnMetaData.AvaticaType componentType,
            Accessor componentAccessor,
            SlotGetter componentSlotGetter,
            ArrayImpl.Factory factory
        ) {
            super(getter);
            this.componentType = componentType;
            this.componentAccessor = componentAccessor;
            this.componentSlotGetter = componentSlotGetter;
            this.factory = factory;
        }

        @Override
        public Object getObject() throws SQLException {
            final Object object = super.getObject();
            if (object == null || object instanceof ArrayImpl) {
                return object;
            } else if (object instanceof List) {
                List<?> list = (List<?>) object;
                // Run the array values through the component accessor
                List<Object> convertedValues = new ArrayList<>(list.size());
                for (Object val : list) {
                    if (null == val) {
                        convertedValues.add(null);
                    } else {
                        // Set the current value in the SlotGetter so we can use the Accessor to
                        // coerce it.
                        componentSlotGetter.slot = val;
                        convertedValues.add(convertValue());
                    }
                }
                return convertedValues;
            }
            // The object can be java array in case of user-provided class for row storage.
            return AvaticaUtils.primitiveList(object);
        }

        private Object convertValue() throws SQLException {
            switch (componentType.id) {
                case Types.BOOLEAN:
                case Types.BIT:
                    return componentAccessor.getBoolean();
                case Types.TINYINT:
                    return componentAccessor.getByte();
                case Types.SMALLINT:
                    return componentAccessor.getShort();
                case Types.INTEGER:
                    return componentAccessor.getInt();
                case Types.BIGINT:
                    return componentAccessor.getLong();
                case Types.FLOAT:
                    return componentAccessor.getFloat();
                case Types.DOUBLE:
                    return componentAccessor.getDouble();
                case Types.ARRAY:
                    return componentAccessor.getArray();
                case Types.CHAR:
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.NCHAR:
                case Types.LONGNVARCHAR:
                    return componentAccessor.getString();
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                    return componentAccessor.getBytes();
                case Types.DECIMAL:
                    return componentAccessor.getBigDecimal();
                case Types.DATE:
                case Types.TIME:
                case Types.TIMESTAMP:
                case Types.STRUCT:
                case Types.JAVA_OBJECT:
                    return componentAccessor.getObject();
                default:
                    throw new IllegalStateException(
                        "Unhandled ARRAY component type: "
                            + componentType.rep
                            + ", id: "
                            + componentType.id
                    );
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public Array getArray() throws SQLException {
            final Object o = getObject();
            if (o == null) {
                return null;
            }
            if (o instanceof ArrayImpl) {
                return (ArrayImpl) o;
            }
            // If it's not an Array already, assume it is a List.
            return new ArrayImpl((List<Object>) o, this);
        }

        @Override
        public String getString() throws SQLException {
            final Array array = getArray();
            return array == null ? null : array.toString();
        }
    }

    /**
     * Accessor that assumes that the underlying value is a STRUCT;
     * corresponds to {@link java.sql.Types#STRUCT}.
     */
    private static class StructAccessor extends AccessorImpl {
        private final List<Accessor> fieldAccessors;

        private StructAccessor(Getter getter, List<Accessor> fieldAccessors) {
            super(getter);
            this.fieldAccessors = fieldAccessors;
        }

        @Override
        public Object getObject() throws SQLException {
            return getStruct();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getObject(Class<T> clz) throws SQLException {
            // getStruct() is not exposed on Accessor, only AccessorImpl. getObject(Class) is
            // exposed,
            // so we can make it do the right thing (call getStruct()).
            if (clz.equals(Struct.class)) {
                return (T) getStruct();
            }
            return super.getObject(clz);
        }

        @Override
        public Struct getStruct() throws SQLException {
            final Object o = super.getObject();
            if (o == null) {
                return null;
            } else if (o instanceof StructImpl) {
                return (StructImpl) o;
            } else if (o instanceof List) {
                return new StructImpl((List) o);
            } else {
                final List<Object> list = new ArrayList<>();
                for (Accessor fieldAccessor : fieldAccessors) {
                    list.add(fieldAccessor.getObject());
                }
                return new StructImpl(list);
            }
        }
    }

    /**
     * Accessor that assumes that the underlying value is an OBJECT;
     * corresponds to {@link java.sql.Types#JAVA_OBJECT}.
     */
    private static class ObjectAccessor extends AccessorImpl {
        private ObjectAccessor(Getter getter) {
            super(getter);
        }
    }

    /**
     * Gets a value from a particular field of the current record of this
     * cursor.
     */
    protected interface Getter {
        Object getObject() throws SQLException;

        boolean wasNull() throws SQLException;
    }

    /**
     * Abstract implementation of {@link Getter}.
     */
    protected abstract class AbstractGetter implements Getter {
        public boolean wasNull() throws SQLException {
            return wasNull[0];
        }
    }

    /**
     * Implementation of {@link Getter} that returns the current contents of
     * a mutable slot.
     */
    public class SlotGetter implements Getter {
        public Object slot;

        public Object getObject() throws SQLException {
            return slot;
        }

        public boolean wasNull() throws SQLException {
            return slot == null;
        }
    }

    /**
     * Implementation of {@link Getter} that returns the value of a given field
     * of the current contents of another getter.
     */
    public class StructGetter implements Getter {
        public final Getter getter;
        private final ColumnMetaData columnMetaData;

        public StructGetter(Getter getter, ColumnMetaData columnMetaData) {
            this.getter = getter;
            this.columnMetaData = columnMetaData;
        }

        public Object getObject() throws SQLException {
            try {
                final Object o = getter.getObject();
                if (o instanceof Object[]) {
                    Object[] objects = (Object[]) o;
                    return objects[columnMetaData.ordinal];
                }
                final Field field = o.getClass().getField(columnMetaData.label);
                return field.get(getter.getObject());
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new SQLException(e);
            }
        }

        public boolean wasNull() throws SQLException {
            return getObject() == null;
        }
    }
}

// End AbstractCursor.java
