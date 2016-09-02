/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.ql;

import com.questdb.Journal;
import com.questdb.JournalEntryWriter;
import com.questdb.JournalWriter;
import com.questdb.PartitionBy;
import com.questdb.ex.ParserException;
import com.questdb.factory.configuration.JournalMetadata;
import com.questdb.misc.ByteBuffers;
import com.questdb.misc.Files;
import com.questdb.misc.Rnd;
import com.questdb.misc.Unsafe;
import com.questdb.model.configuration.ModelConfiguration;
import com.questdb.ql.parser.QueryCompiler;
import com.questdb.ql.parser.QueryError;
import com.questdb.std.DirectInputStream;
import com.questdb.store.ColumnType;
import com.questdb.test.tools.JournalTestFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;

public class DDLTests {

    public static final JournalTestFactory factory = new JournalTestFactory(ModelConfiguration.MAIN.build(Files.makeTempDir()));
    private static final QueryCompiler compiler = new QueryCompiler();

    @After
    public void tearDown() throws Exception {
        Files.deleteOrException(new File(factory.getConfiguration().getJournalBase(), "x"));
        Files.deleteOrException(new File(factory.getConfiguration().getJournalBase(), "y"));
    }

    @Test
    public void testBadIntBuckets() throws Exception {
        try {
            compiler.execute(factory, "create table x (a INT index buckets -1, b BYTE, t TIMESTAMP, x SYMBOL) partition by MONTH");
            Assert.fail();
        } catch (ParserException ignore) {
            // good, pass
        }
    }

    @Test
    public void testCreateAllFieldTypes() throws Exception {
        compiler.execute(factory, "create table x (a INT, b BYTE, c SHORT, d LONG, e FLOAT, f DOUBLE, g DATE, h BINARY, t DATE, x SYMBOL, z STRING) timestamp(t) partition by MONTH record hint 100");

        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(11, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());

            Assert.assertEquals(ColumnType.SHORT, m.getColumn("c").getType());
            Assert.assertEquals(ColumnType.LONG, m.getColumn("d").getType());
            Assert.assertEquals(ColumnType.FLOAT, m.getColumn("e").getType());
            Assert.assertEquals(ColumnType.DOUBLE, m.getColumn("f").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("g").getType());
            Assert.assertEquals(ColumnType.BINARY, m.getColumn("h").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertEquals(ColumnType.STRING, m.getColumn("z").getType());

            Assert.assertEquals(8, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.MONTH, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateAsSelect() throws Exception {
        compiler.execute(factory, "create table y (a INT, b BYTE, c SHORT, d LONG, e FLOAT, f DOUBLE, g DATE, h BINARY, t DATE, x SYMBOL, z STRING) timestamp(t) partition by YEAR record hint 100");
        try (JournalWriter w = compiler.createWriter(factory, "create table x as (y order by t)")) {
            JournalMetadata m = w.getMetadata();
            Assert.assertEquals(11, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());

            Assert.assertEquals(ColumnType.SHORT, m.getColumn("c").getType());
            Assert.assertEquals(ColumnType.LONG, m.getColumn("d").getType());
            Assert.assertEquals(ColumnType.FLOAT, m.getColumn("e").getType());
            Assert.assertEquals(ColumnType.DOUBLE, m.getColumn("f").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("g").getType());
            Assert.assertEquals(ColumnType.BINARY, m.getColumn("h").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertEquals(ColumnType.STRING, m.getColumn("z").getType());
            Assert.assertEquals(8, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.NONE, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateAsSelectBin() throws Exception {
        int N = 10000;
        int SZ = 4096;
        ByteBuffer buf = ByteBuffer.allocateDirect(SZ);
        try {
            long addr = ByteBuffers.getAddress(buf);
            try (JournalWriter w = compiler.createWriter(factory, "create table x (a INT, b BINARY)")) {
                Rnd rnd = new Rnd();

                for (int i = 0; i < N; i++) {
                    long p = addr;
                    int n = (rnd.nextPositiveInt() % (SZ - 1)) / 8;
                    for (int j = 0; j < n; j++) {
                        Unsafe.getUnsafe().putLong(p, rnd.nextLong());
                        p += 8;
                    }

                    buf.limit(n * 8);
                    JournalEntryWriter ew = w.entryWriter();
                    ew.putInt(0, i);
                    ew.putBin(1, buf);
                    ew.append();
                    buf.clear();
                }
                w.commit();
            }

            compiler.execute(factory, "create table y as (x)");

            int count = 0;
            RecordSource rs = compiler.compile(factory, "y");
            RecordCursor cursor = rs.prepareCursor(factory);

            Rnd rnd = new Rnd();
            while (cursor.hasNext()) {
                Record rec = cursor.next();
                Assert.assertEquals(count, rec.getInt(0));

                long len = rec.getBinLen(1);
                DirectInputStream is = rec.getBin(1);
                is.copyTo(addr, 0, len);

                long p = addr;
                int n = (rnd.nextPositiveInt() % (SZ - 1)) / 8;
                for (int j = 0; j < n; j++) {
                    Assert.assertEquals(rnd.nextLong(), Unsafe.getUnsafe().getLong(p));
                    p += 8;
                }
                count++;
            }
            Assert.assertEquals(N, count);
        } finally {
            ByteBuffers.release(buf);
        }
    }

    @Test
    public void testCreateAsSelectPartitionBy() throws Exception {
        compiler.execute(factory, "create table y (a INT, b BYTE, c SHORT, d LONG, e FLOAT, f DOUBLE, g DATE, h BINARY, t DATE, x SYMBOL, z STRING) timestamp(t) partition by YEAR record hint 100");
        try (JournalWriter w = compiler.createWriter(factory, "create table x as (y order by t) partition by MONTH record hint 100")) {
            JournalMetadata m = w.getMetadata();
            Assert.assertEquals(11, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());

            Assert.assertEquals(ColumnType.SHORT, m.getColumn("c").getType());
            Assert.assertEquals(ColumnType.LONG, m.getColumn("d").getType());
            Assert.assertEquals(ColumnType.FLOAT, m.getColumn("e").getType());
            Assert.assertEquals(ColumnType.DOUBLE, m.getColumn("f").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("g").getType());
            Assert.assertEquals(ColumnType.BINARY, m.getColumn("h").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertEquals(ColumnType.STRING, m.getColumn("z").getType());
            Assert.assertEquals(8, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.MONTH, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateDefaultPartitionBy() throws Exception {
        compiler.execute(factory, "create table x (a INT index, b BYTE, t DATE, z STRING index buckets 40) record hint 100");
        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(4, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertTrue(m.getColumn("a").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(1, m.getColumn("a").getBucketCount());

            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.STRING, m.getColumn("z").getType());
            Assert.assertTrue(m.getColumn("z").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(63, m.getColumn("z").getBucketCount());

            Assert.assertEquals(-1, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.NONE, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateExistingTable() throws Exception {
        compiler.execute(factory, "create table x (a INT index buckets 25, b BYTE, t DATE, x SYMBOL index) timestamp(t) partition by MONTH");
        try {
            compiler.execute(factory, "create table x (a INT index buckets 25, b BYTE, t DATE, x SYMBOL index) timestamp(t) partition by MONTH");
            Assert.fail();
        } catch (ParserException e) {
            Assert.assertEquals(13, QueryError.getPosition());
        }
    }

    @Test
    public void testCreateFromDefPartitionNoTimestamp() throws Exception {
        try {
            compiler.execute(factory, "create table x (a INT, b BYTE, x SYMBOL), index(a buckets 25), index(x) partition by YEAR record hint 100");
            Assert.fail();
        } catch (ParserException e) {
            Assert.assertEquals(85, QueryError.getPosition());
        }
    }

    @Test
    public void testCreateIndexWithSuffix() throws Exception {
        compiler.execute(factory, "create table x (a INT, b BYTE, t DATE, x SYMBOL), index(a buckets 25), index(x) timestamp(t) partition by YEAR record hint 100");
        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(4, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertTrue(m.getColumn("a").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(31, m.getColumn("a").getBucketCount());

            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertTrue(m.getColumn("x").isIndexed());

            Assert.assertEquals(2, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.YEAR, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateIndexWithSuffixDefaultPartition() throws Exception {
        compiler.execute(factory, "create table x (a INT, b BYTE, t DATE, x SYMBOL), index(a buckets 25), index(x) timestamp(t) record hint 100");
        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(4, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertTrue(m.getColumn("a").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(31, m.getColumn("a").getBucketCount());

            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertTrue(m.getColumn("x").isIndexed());

            Assert.assertEquals(2, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.NONE, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateIndexedInt() throws Exception {
        compiler.execute(factory, "create table x (a INT index buckets 25, b BYTE, t DATE, x SYMBOL) timestamp(t) partition by MONTH record hint 100");
        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(4, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertTrue(m.getColumn("a").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(31, m.getColumn("a").getBucketCount());

            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertEquals(2, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.MONTH, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateIndexedIntDefaultBuckets() throws Exception {
        compiler.execute(factory, "create table x (a INT index, b BYTE, t DATE, x SYMBOL) timestamp(t) partition by MONTH record hint 100");
        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(4, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertTrue(m.getColumn("a").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(1, m.getColumn("a").getBucketCount());

            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertEquals(2, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.MONTH, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateIndexedString() throws Exception {
        compiler.execute(factory, "create table x (a INT index, b BYTE, t DATE, z STRING index buckets 40) timestamp(t) partition by MONTH record hint 100");
        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(4, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertTrue(m.getColumn("a").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(1, m.getColumn("a").getBucketCount());

            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.STRING, m.getColumn("z").getType());
            Assert.assertTrue(m.getColumn("z").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(63, m.getColumn("z").getBucketCount());

            Assert.assertEquals(2, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.MONTH, m.getPartitionBy());
        }
    }

    @Test
    public void testCreateIndexedSymbol() throws Exception {
        compiler.execute(factory, "create table x (a INT index buckets 25, b BYTE, t DATE, x SYMBOL index) timestamp(t) partition by MONTH");
        // validate journal
        try (Journal r = factory.reader("x")) {
            Assert.assertNotNull(r);
            JournalMetadata m = r.getMetadata();
            Assert.assertEquals(4, m.getColumnCount());
            Assert.assertEquals(ColumnType.INT, m.getColumn("a").getType());
            Assert.assertTrue(m.getColumn("a").isIndexed());
            // bucket is ceilPow2(value) - 1
            Assert.assertEquals(31, m.getColumn("a").getBucketCount());

            Assert.assertEquals(ColumnType.BYTE, m.getColumn("b").getType());
            Assert.assertEquals(ColumnType.DATE, m.getColumn("t").getType());
            Assert.assertEquals(ColumnType.SYMBOL, m.getColumn("x").getType());
            Assert.assertTrue(m.getColumn("x").isIndexed());
            Assert.assertEquals(2, m.getTimestampIndex());
            Assert.assertEquals(PartitionBy.MONTH, m.getPartitionBy());
        }
    }

    @Test
    public void testInvalidPartitionBy() throws Exception {
        try {
            compiler.execute(factory, "create table x (a INT index, b BYTE, t DATE, z STRING index buckets 40) partition by x record hint 100");
            Assert.fail();
        } catch (ParserException e) {
            Assert.assertEquals(85, QueryError.getPosition());
        }
    }

    @Test
    public void testInvalidPartitionBy2() throws Exception {
        try {
            compiler.execute(factory, "create table x (a INT index, b BYTE, t DATE, z STRING index buckets 40) partition by 1 record hint 100");
            Assert.fail();
        } catch (ParserException e) {
            Assert.assertEquals(85, QueryError.getPosition());
        }
    }

    @Test
    public void testUnsupportedTypeForIndex() throws Exception {
        try {
            compiler.execute(factory, "create table x (a INT, b BYTE, t DATE, x SYMBOL), index(t) partition by YEAR");
            Assert.fail();
        } catch (ParserException ignored) {
            // pass
        }
    }
}