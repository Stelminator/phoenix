package com.salesforce.phoenix.end2end.index;

import static com.salesforce.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_NAME;
import static com.salesforce.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_CAT_NAME;
import static com.salesforce.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_NAME_NAME;
import static com.salesforce.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_SCHEM_NAME;
import static com.salesforce.phoenix.jdbc.PhoenixDatabaseMetaData.TYPE_SCHEMA;
import static com.salesforce.phoenix.jdbc.PhoenixDatabaseMetaData.TYPE_TABLE;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;

import com.salesforce.phoenix.query.QueryConstants;
import com.salesforce.phoenix.schema.ColumnModifier;
import com.salesforce.phoenix.schema.ColumnNotFoundException;
import com.salesforce.phoenix.schema.PColumn;
import com.salesforce.phoenix.schema.PColumnFamily;
import com.salesforce.phoenix.schema.PDataType;
import com.salesforce.phoenix.schema.PRow;
import com.salesforce.phoenix.schema.PTable;
import com.salesforce.phoenix.schema.RowKeySchema;
import com.salesforce.phoenix.schema.ValueBitSet;
import com.salesforce.phoenix.util.IndexUtil;
import com.salesforce.phoenix.util.MetaDataUtil;
import com.salesforce.phoenix.util.SchemaUtil;

public class IndexTestUtil {

    // the normal db metadata interface is insufficient for all fields needed for an
    // index table test.
    private static final String SELECT_DATA_INDEX_ROW = "SELECT " + TABLE_CAT_NAME
            + " FROM "
            + TYPE_SCHEMA + ".\"" + TYPE_TABLE
            + "\" WHERE "
            + TABLE_SCHEM_NAME + "=? AND " + TABLE_NAME_NAME + "=? AND " + COLUMN_NAME + " IS NULL AND " + TABLE_CAT_NAME + "=?";
    
    public static ResultSet readDataTableIndexRow(Connection conn, String schemaName, String tableName, String indexName) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(SELECT_DATA_INDEX_ROW);
        stmt.setString(1, schemaName);
        stmt.setString(2, tableName);
        stmt.setString(3, indexName);
        return stmt.executeQuery();
    }
    
    
    private static void coerceDataValueToIndexValue(PColumn dataColumn, PColumn indexColumn, ImmutableBytesWritable ptr) {
        PDataType dataType = dataColumn.getDataType();
        // TODO: push to RowKeySchema? 
        ColumnModifier dataModifier = dataColumn.getColumnModifier();
        PDataType indexType = indexColumn.getDataType();
        ColumnModifier indexModifier = indexColumn.getColumnModifier();
        // We know ordinal position will match pk position, because you cannot
        // alter an index table.
        indexType.coerceBytes(ptr, dataType, dataModifier, indexModifier);
    }
    
    public static List<Mutation> generateIndexData(PTable indexTable, PTable dataTable, Mutation dataMutation) throws SQLException {
        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
        byte[] dataRowKey = dataMutation.getRow();
        int maxOffset = dataRowKey.length;
        RowKeySchema dataRowKeySchema = dataTable.getRowKeySchema();
        List<PColumn> dataPKColumns = dataTable.getPKColumns();
        int i = 0;
        int indexOffset = 0;
        ptr.set(dataRowKey);
        Boolean hasValue = dataRowKeySchema.first(ptr, i, maxOffset, ValueBitSet.EMPTY_VALUE_BITSET);
        if (dataTable.getBucketNum() != null) { // Skip salt column
            hasValue=dataRowKeySchema.next(ptr, ++i, maxOffset, ValueBitSet.EMPTY_VALUE_BITSET);
        }
        List<PColumn> indexPKColumns = indexTable.getPKColumns();
        List<PColumn> indexColumns = indexTable.getColumns();
        int nIndexColumns = indexPKColumns.size();
        int maxIndexValues = indexColumns.size() - nIndexColumns - indexOffset;
        BitSet indexValuesSet = new BitSet(maxIndexValues);
        byte[][] indexValues = new byte[indexColumns.size() - indexOffset][];
        while (hasValue != null) {
            if (hasValue) {
                PColumn dataColumn = dataPKColumns.get(i);
                PColumn indexColumn = indexTable.getColumn(IndexUtil.getIndexColumnName(dataColumn));
                coerceDataValueToIndexValue(dataColumn, indexColumn, ptr);
                indexValues[indexColumn.getPosition()-indexOffset] = ptr.copyBytes();
            }
            hasValue=dataRowKeySchema.next(ptr, ++i, maxOffset, ValueBitSet.EMPTY_VALUE_BITSET);
        }
        PRow row;
        long ts = MetaDataUtil.getClientTimeStamp(dataMutation);
        if (dataMutation instanceof Delete && dataMutation.getFamilyMap().values().isEmpty()) {
            indexTable.newKey(ptr, indexValues);
            row = indexTable.newRow(ts, ptr);
            row.delete();
        } else {
            // If no column families in table, then nothing to look for 
            if (!dataTable.getColumnFamilies().isEmpty()) {
                for (Map.Entry<byte[],List<KeyValue>> entry : dataMutation.getFamilyMap().entrySet()) {
                    PColumnFamily family = dataTable.getColumnFamily(entry.getKey());
                    for (KeyValue kv : entry.getValue()) {
                        byte[] cq = kv.getQualifier();
                        if (Bytes.compareTo(QueryConstants.EMPTY_COLUMN_BYTES, cq) != 0) {
                            try {
                                PColumn dataColumn = family.getColumn(cq);
                                PColumn indexColumn = indexTable.getColumn(IndexUtil.getIndexColumnName(family.getName().getString(), dataColumn.getName().getString()));
                                ptr.set(kv.getBuffer(),kv.getValueOffset(),kv.getValueLength());
                                coerceDataValueToIndexValue(dataColumn, indexColumn, ptr);
                                indexValues[indexColumn.getPosition()-indexOffset] = ptr.copyBytes();
                                if (!SchemaUtil.isPKColumn(indexColumn)) {
                                    indexValuesSet.set(indexColumn.getPosition()-nIndexColumns-indexOffset);
                                }
                            } catch (ColumnNotFoundException e) {
                                // Ignore as this means that the data column isn't in the index
                            }
                        }
                    }
                }
            }
            indexTable.newKey(ptr, indexValues);
            row = indexTable.newRow(ts, ptr);
            int pos = 0;
            while ((pos = indexValuesSet.nextSetBit(pos)) >= 0) {
                int index = nIndexColumns + indexOffset + pos++;
                PColumn indexColumn = indexColumns.get(index);
                row.setValue(indexColumn, indexValues[index]);
            }
        }
        return row.toRowMutations();
    }

}
